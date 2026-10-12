package com.launchdarkly.sdk.android;

import androidx.annotation.VisibleForTesting;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * A crash-durable append log of serialized analytics events.
 * <p>
 * Events are appended to an open log, which is closed into a batch when the event processor is ready to
 * deliver one. A batch is deleted only once LaunchDarkly has accepted it, so a delivery interrupted by
 * the process dying is retried on the next run rather than lost.
 * <p>
 * Bytes reach the kernel through an ordinary write and are never {@code fsync}ed. What this defends
 * against is the process dying - which is what an uncaught exception, an ANR kill, a native crash, and
 * the system reclaiming a backgrounded application all are - and a process cannot take back bytes the
 * kernel already holds. Only losing the kernel itself, to a panic or a power cut, can discard them, and
 * covering that would mean an {@code fsync} per event, which costs milliseconds where this costs
 * microseconds.
 * <p>
 * <b>Several clients.</b> An application configured for more than one environment gets one
 * {@code LDClient} and one store per mobile key, and the mobile key's hash is part of the directory, so
 * two environments never read or write each other's events.
 * <p>
 * <b>Several processes.</b> An application may declare components in more than one process, and each
 * gets its own copy of the SDK that shares nothing but the filesystem. Only the open log is per-process,
 * named after the process appending to it, which is what keeps two processes from interleaving frames in
 * one file and keeps one process's startup recovery from stealing a log another is still writing to.
 * Closed batches are deliberately left shared: a process that recorded events and then died may never
 * run again, and a batch nobody but that process could see would never be delivered. If two processes
 * do deliver the same batch, its payload ID is the same both times, and LaunchDarkly discards a repeated
 * payload ID rather than counting the events twice.
 * <p>
 * What a log looks like on disk is {@link EventLogFormat}; the log this process appends to is
 * {@link DiskEventLog}. This class is what decides when events move between memory, that log, and the
 * batches closed off for delivery, and what counts them against capacity on the way.
 */
class EventStore implements Closeable {
    /** Names a log closed off for delivery; the rest of the name is the batch's payload ID. */
    private static final String BATCH_PREFIX = "ready-";
    private static final String DIRECTORY_NAME = "com.launchdarkly.events";

    /**
     * How many staged bytes may accumulate before one of them pays for a write.
     * <p>
     * This is what keeps a syscall off most recordings while bounding what a crash can take with it,
     * for an application that only evaluates flags and so never reaches a commit point of its own.
     */
    private static final int STAGING_THRESHOLD = 16 * 1024;

    /**
     * Where the store keeps its files, asked for on first use rather than at construction.
     * <p>
     * The store is built on the thread that called {@code LDClient.init}, usually the main one, and
     * answering touches the disk: the platform creates the no-backup directory the first time it is asked
     * for it, and before API 28 the process name is read from {@code /proc}.
     */
    private final Location location;
    // Guarded by ioLock, and null until resolveLocationHoldingIoLock has run.
    private File directory;
    private DiskEventLog diskLog;
    private final int capacity;
    private final LDLogger logger;
    /**
     * Where a commit runs when no caller is waiting on it.
     * <p>
     * Evaluating a flag must not put a write on whichever thread evaluated it, and that thread is
     * usually the main one. A caller at a commit point still commits on its own thread, because for
     * them having returned is the guarantee.
     */
    private final Executor commitExecutor;

    /**
     * Guards the buffer and the counters. Held for a copy and never across a write, so an
     * evaluation recording an event waits on another thread's array copy at worst, never on the disk.
     */
    private final Object bufferLock = new Object();
    /**
     * Serializes writes so that two threads committing at once cannot interleave a frame. Taken before
     * {@link #bufferLock}, never after it.
     */
    private final Object ioLock = new Object();

    // Guarded by bufferLock.
    private final ByteArrayOutputStream bufferData = new ByteArrayOutputStream();
    private int bufferedEventCount;
    /**
     * Events taken out of the buffer to be written to the log, or assembled into a batch held in
     * memory, and not yet counted where they land. Both happen outside this lock, and for as long as
     * they take the events are in neither place; without this they would be counted nowhere, and
     * capacity would admit that many more.
     */
    private int inTransitEvents;
    private int committedEvents;
    private int closedEvents;
    /**
     * Events a caller has taken to encode and promised to stage, counted against capacity until each is
     * staged. Staging one moves it from here to {@link #bufferedEventCount} under one lock, so an event
     * on its way in is counted exactly once throughout.
     */
    private int reservedEvents;
    /**
     * Whether events are being written to disk, which is what the application asked for until a write
     * fails and the store gives up on persistence for the rest of the session.
     * <p>
     * Written under {@link #bufferLock} but volatile, so {@link #isPersisting} can answer without it:
     * every {@code track} asks, and a commit takes that lock once for each event it stages.
     */
    private volatile boolean persistEvents;
    /**
     * Whether the application asked for persistence at all. Unlike {@link #persistEvents} this does not
     * change when a write fails, because batches written before the failure still have to be read back.
     * Without it the store never touches the filesystem, not even to look for a previous run's events.
     */
    private final boolean usesDisk;
    private boolean commitScheduled;

    // Guarded by ioLock.
    private boolean recoveryDone;
    /**
     * Guards {@link #inMemoryBatches}.
     * <p>
     * Its own lock rather than {@code ioLock}, so that a store the application told to keep off the disk -
     * where held batches are the only ones there are - never waits on the lock disk I/O is serialized
     * under. Taken inside {@code ioLock} where there is a disk, never the other way round.
     */
    private final Object heldLock = new Object();
    /**
     * Batches held in memory because the filesystem would not take them, or because it was never asked.
     * <p>
     * Persistence failing should cost durability and nothing else, so the store falls back to what the
     * SDK did before it existed: hold the events, deliver them, lose them only if the process dies.
     * <p>
     * Only to be used while holding {@link #heldLock}.
     */
    private final Map<String, HeldBatch> inMemoryBatches = new LinkedHashMap<>();

    /**
     * How many events are in each batch this process closed or recovered, so that listing them does not
     * have to read them back.
     * <p>
     * A batch is never appended to once it is closed, so a count taken at the close holds until the batch
     * is delivered. Without this, listing reads every batch file in full while holding {@code ioLock}, and
     * a commit at a commit point can end up waiting behind those reads.
     * <p>
     * Only to be used while holding {@code ioLock}.
     */
    private final Map<String, Integer> eventCounts = new HashMap<>();

    /** Where one store keeps its files. */
    interface Location {
        File directory();

        String processName();
    }

    EventStore(final File directory, final String processName, int capacity, boolean persistEvents,
               LDLogger logger, Executor commitExecutor) {
        this(new Location() {
            @Override
            public File directory() {
                return directory;
            }

            @Override
            public String processName() {
                return processName;
            }
        }, capacity, persistEvents, logger, commitExecutor);
    }

    private EventStore(Location location, int capacity, boolean persistEvents, LDLogger logger,
                       Executor commitExecutor) {
        this.location = location;
        this.capacity = capacity >= 0 ? capacity : 1;
        this.logger = logger;
        this.commitExecutor = commitExecutor;
        // An application that has not asked for persistence gets the same store running the same way it
        // runs once a write has failed: events are held, delivered, and lost only if the process dies.
        // It also skips every read, so batches a run with persistence turned on left behind stay on disk
        // until persistence is turned back on.
        this.persistEvents = persistEvents;
        this.usesDisk = persistEvents;
    }

    /**
     * Creates the store for one environment of one process.
     *
     * @param noBackupFilesDir where the platform lets the SDK keep files it must not lose
     * @param mobileKey identifies the environment, so several clients stay out of each other's way
     * @param processName identifies the process, so several processes stay out of each other's way
     * @param persistEvents whether the application asked for events to outlive the process
     */
    static EventStore create(
            File noBackupFilesDir,
            String mobileKey,
            String processName,
            int capacity,
            boolean persistEvents,
            LDLogger logger
    ) {
        File directory = new File(new File(noBackupFilesDir, DIRECTORY_NAME),
                environmentDirectoryName(mobileKey));
        return new EventStore(directory, processName, capacity, persistEvents, logger,
                defaultCommitExecutor());
    }

    /**
     * Creates the store for one environment of one process, leaving the platform to be asked where on
     * first use, from one of the store's own threads.
     *
     * @param platformState where the no-backup directory and the process name come from
     * @param mobileKey identifies the environment, so several clients stay out of each other's way
     * @param persistEvents whether the application asked for events to outlive the process
     * @param commitExecutor where a commit no caller waits on runs, shared with whatever else commits
     *   to this store so that writes queue behind one another rather than behind a delivery
     */
    static EventStore create(
            final PlatformState platformState,
            String mobileKey,
            int capacity,
            boolean persistEvents,
            LDLogger logger,
            Executor commitExecutor
    ) {
        final String environmentDirectory = environmentDirectoryName(mobileKey);
        Location location = new Location() {
            @Override
            public File directory() {
                return new File(new File(platformState.getNoBackupFilesDir(), DIRECTORY_NAME),
                        environmentDirectory);
            }

            @Override
            public String processName() {
                return platformState.getProcessName();
            }
        };
        return new EventStore(location, capacity, persistEvents, logger, commitExecutor);
    }

    /**
     * Names the directory for one environment, hashed so the mobile key itself is never a filename.
     * <p>
     * Deliberately not {@link LDUtil#urlSafeBase64Hash(String)}, which goes through the Android
     * framework's Base64 and so is unavailable to plain JVM tests, and which pads with a character
     * that has no business in a path.
     */
    private static String environmentDirectoryName(String mobileKey) {
        // Half a digest, which is far more than enough to keep two mobile keys apart.
        return hexDigest(mobileKey, 16);
    }

    /**
     * @param bytes how many bytes of the digest to render, so a caller can trade length for headroom
     * @return the first {@code bytes} bytes of the input's SHA-256, in lowercase hex
     */
    static String hexDigest(String input, int bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes * 2);
            for (int i = 0; i < bytes; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xf, 16));
                hex.append(Character.forDigit(hash[i] & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e); // shouldn't be possible; SHA-256 is built in
        }
    }

    /** The single daemon thread a store's commits run on when no caller is waiting for them. */
    static ExecutorService defaultCommitExecutor() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "LaunchDarkly-event-store");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    // MARK: recording

    /**
     * @return serialized events recorded but not yet accepted by LaunchDarkly, whether staged in
     *   memory, on their way out of it, written to the open log, or sitting in a closed batch
     */
    int getPendingEventCount() {
        synchronized (bufferLock) {
            return countedEventsHoldingBufferLock();
        }
    }

    /**
     * Every event counted against capacity. The one place the counters are added up, so that neither
     * reader can miss one of them. Requires {@link #bufferLock}.
     */
    private int countedEventsHoldingBufferLock() {
        return bufferedEventCount + inTransitEvents + committedEvents + closedEvents + reservedEvents;
    }

    /**
     * Counts events against capacity before they are staged, for a caller that has taken them from
     * somewhere capacity was already counting them and will encode them before staging.
     * <p>
     * Each {@link #stageReserved} uses one up, and {@link #releaseReservations} gives back any that
     * never made it, such as an event that could not be serialized.
     */
    void reserve(int events) {
        synchronized (bufferLock) {
            reservedEvents += events;
        }
    }

    /** Gives back every reservation not used up by {@link #stageReserved}. */
    void releaseReservations() {
        synchronized (bufferLock) {
            reservedEvents = 0;
        }
    }

    /**
     * @return whether a commit writes anything, which is false where persistence is off and once a write
     *   has failed and the store has given up on it
     */
    boolean isPersisting() {
        return persistEvents;
    }

    /**
     * Stages an already serialized event.
     * <p>
     * Staging is a copy into a buffer; {@link #commit()} is what makes the event outlive the process.
     *
     * @return false if the event was dropped because capacity is reached
     */
    boolean stage(byte[] serializedEvent) {
        return stage(serializedEvent, false);
    }

    /**
     * @param bypassingCapacity true for an event that must be recorded even when the store is full
     */
    boolean stage(byte[] serializedEvent, boolean bypassingCapacity) {
        return stage(serializedEvent, bypassingCapacity, false);
    }

    /**
     * Stages an event {@link #reserve} already counted, turning its reservation into a staged event in
     * one step. Bypasses capacity, which the reservation already answered to.
     */
    boolean stageReserved(byte[] serializedEvent) {
        return stage(serializedEvent, true, true);
    }

    private boolean stage(byte[] serializedEvent, boolean bypassingCapacity, boolean reserved) {
        if (serializedEvent == null || serializedEvent.length == 0
                || serializedEvent.length > EventLogFormat.MAX_FRAME_SIZE) {
            return false;
        }

        boolean needsCommit;
        synchronized (bufferLock) {
            if (!bypassingCapacity && countedEventsHoldingBufferLock() >= capacity) {
                return false;
            }
            EventLogFormat.writeFrame(bufferData, serializedEvent);
            bufferedEventCount++;
            if (reserved && reservedEvents > 0) {
                reservedEvents--;
            }
            needsCommit = persistEvents && bufferData.size() >= STAGING_THRESHOLD && !commitScheduled;
            if (needsCommit) {
                commitScheduled = true;
            }
        }

        // Handed to another thread rather than done here. Recording an event is something a flag
        // evaluation does, and an evaluation is expected to be a memory operation: the caller is usually
        // the main thread, where a write that happens to find a busy filesystem is a stall the
        // application can do nothing about.
        if (needsCommit) {
            try {
                commitExecutor.execute(this::commitScheduled);
            } catch (RuntimeException e) {
                // A rejected commit only means these bytes wait for the next one.
                synchronized (bufferLock) {
                    commitScheduled = false;
                }
            }
        }
        return true;
    }

    private void commitScheduled() {
        // Cleared before the commit rather than after, so an event staged while this write is in flight
        // asks for another one instead of finding a commit apparently already on its way.
        synchronized (bufferLock) {
            commitScheduled = false;
        }
        commit();
    }

    /**
     * Hands every staged byte to the kernel, so the events recorded so far survive the process dying.
     */
    void commit() {
        // Answered before ioLock, which a delivery can hold for as long as it takes to read a batch
        // back: with persistence off there is nothing to write, so nothing worth waiting for.
        synchronized (bufferLock) {
            if (!persistEvents) {
                return;
            }
        }
        synchronized (ioLock) {
            commitHoldingIoLock();
        }
    }

    // MARK: delivery

    /**
     * Commits, then closes the open log into a batch to deliver, and whatever is still staged in memory
     * into another.
     * <p>
     * Both can hold events at once. A write that fails after earlier ones succeeded gives up on
     * persistence with those earlier events in the log and the failed ones back in memory, and closing
     * only the log would leave the rest for a delivery that reports success without them.
     *
     * @return the batch closed from the log, or the one closed from memory where the log had nothing;
     *   null when there is nothing to send
     */
    Batch closeBatch() {
        // Where the application asked for no disk there is no log to close, and so nothing to close it
        // alongside.
        if (!usesDisk) {
            return closeInMemoryBatch();
        }
        synchronized (ioLock) {
            commitHoldingIoLock();

            int events;
            int stillBuffered;
            synchronized (bufferLock) {
                events = committedEvents;
                stillBuffered = bufferedEventCount;
            }

            Batch fromLog = events > 0 ? closeOpenLogHoldingIoLock(events) : null;
            // Only reachable once persistence has been given up on; a healthy commit leaves nothing
            // staged behind. Closed after the log, so the older events are delivered first.
            Batch fromMemory = stillBuffered > 0 ? closeInMemoryBatch() : null;
            return fromLog != null ? fromLog : fromMemory;
        }
    }

    /** Requires {@code ioLock}. */
    private Batch closeOpenLogHoldingIoLock(int events) {
        String payloadId = UUID.randomUUID().toString();
        if (!diskLog().closeMovingTo(batchFile(payloadId))) {
            logger.warn("Could not close the event log for delivery");
            if (!diskLog().exists()) {
                // The log itself is gone, so its events are too. They have to stop counting against
                // capacity or the store would refuse events for the rest of the session.
                synchronized (bufferLock) {
                    committedEvents = 0;
                }
            }
            return null;
        }

        eventCounts.put(payloadId, events);

        synchronized (bufferLock) {
            committedEvents = 0;
            closedEvents += events;
        }
        return new Batch(payloadId, events);
    }

    /**
     * Closes whatever is staged into a batch that never reaches a file.
     * <p>
     * Called under {@code ioLock} where there is a disk, so that it and the log are closed as one step,
     * and on its own where there is not.
     */
    private Batch closeInMemoryBatch() {
        byte[] frames;
        int events;
        synchronized (bufferLock) {
            frames = bufferData.toByteArray();
            events = bufferedEventCount;
            bufferData.reset();
            bufferedEventCount = 0;
            inTransitEvents += events;
        }
        String payloadId = UUID.randomUUID().toString();
        boolean held = false;
        try {
            // Outside bufferLock, because it copies every staged frame; inTransitEvents is what keeps
            // them counted meanwhile.
            byte[] body = EventLogFormat.assembleBody(EventLogFormat.withFileHeader(frames));
            if (body == null) {
                return null;
            }
            synchronized (heldLock) {
                inMemoryBatches.put(payloadId, new HeldBatch(body, events));
            }
            held = true;
        } finally {
            synchronized (bufferLock) {
                inTransitEvents -= events;
                if (held) {
                    closedEvents += events;
                }
            }
        }
        return new Batch(payloadId, events);
    }

    /** A batch the filesystem would not take, kept where the SDK used to keep all of them. */
    private static final class HeldBatch {
        final byte[] body;
        final int eventCount;

        HeldBatch(byte[] body, int eventCount) {
            this.body = body;
            this.eventCount = eventCount;
        }
    }

    /**
     * @return batches awaiting delivery, oldest first, including any left by a previous run
     */
    List<Batch> pendingBatches() {
        // Listed in one critical section where there is a disk, so that the files and what is held cannot
        // be counted either side of a close that moves events between them.
        List<Batch> batches;
        if (usesDisk) {
            synchronized (ioLock) {
                batches = batchesOnDiskHoldingIoLock();
                addHeldBatches(batches);
            }
        } else {
            batches = new ArrayList<>();
            addHeldBatches(batches);
        }

        int total = 0;
        for (Batch batch : batches) {
            total += batch.eventCount;
        }
        synchronized (bufferLock) {
            closedEvents = total;
        }
        return batches;
    }

    /**
     * The batches the filesystem would not take, after the files: anything held was closed by this run and
     * so is newer than whatever a previous run left on the disk.
     */
    private void addHeldBatches(List<Batch> batches) {
        synchronized (heldLock) {
            for (Map.Entry<String, HeldBatch> held : inMemoryBatches.entrySet()) {
                batches.add(new Batch(held.getKey(), held.getValue().eventCount));
            }
        }
    }

    /**
     * Requires {@code ioLock}.
     *
     * @return the batches the directory holds, oldest first
     */
    private List<Batch> batchesOnDiskHoldingIoLock() {
        List<Batch> batches = new ArrayList<>();
        for (File file : readyFilesOldestFirstHoldingIoLock()) {
            String payloadId = file.getName().substring(BATCH_PREFIX.length());
            // Reading is only for a batch this process has not counted: one a previous run left behind,
            // or one belonging to another process sharing the environment.
            Integer counted = eventCounts.get(payloadId);
            int events;
            if (counted != null) {
                events = counted;
            } else {
                byte[] log;
                try {
                    log = readFile(file);
                } catch (IOException e) {
                    // Left for a later listing rather than deleted: the read may pass, and the events in
                    // it may be intact. Without a count it cannot be delivered now.
                    logger.warn("Could not read stored events, will try again later: {}",
                            LogValues.exceptionSummary(e));
                    continue;
                }
                if (log == null) {
                    // Delivered and removed by another process since the listing.
                    continue;
                }
                events = EventLogFormat.eventCount(log);
            }
            if (events < 0) {
                // Written by a version whose format this one does not read, or damaged beyond what the
                // torn-tail recovery tolerates. Either way it can never be delivered.
                deleteQuietly(file);
                continue;
            }
            eventCounts.put(payloadId, events);
            batches.add(new Batch(payloadId, events));
        }

        // A batch that left the directory without going through remove() would otherwise keep its count
        // for the rest of the session.
        Set<String> listed = new HashSet<>();
        for (Batch batch : batches) {
            listed.add(batch.payloadId);
        }
        eventCounts.keySet().retainAll(listed);
        return batches;
    }

    /**
     * @return the JSON request body for a batch, or null if it is gone or holds nothing this version can
     *   send, neither of which a later attempt changes
     * @throws IOException if the batch is there but could not be read this time
     */
    byte[] body(Batch batch) throws IOException {
        synchronized (heldLock) {
            HeldBatch held = inMemoryBatches.get(batch.payloadId);
            if (held != null) {
                return held.body;
            }
        }

        // A batch is either held or on the disk, never both and never moved between them, so a batch that
        // is not held in a store with no disk does not exist.
        if (!usesDisk) {
            return null;
        }

        synchronized (ioLock) {
            return EventLogFormat.assembleBody(readFile(batchFile(batch.payloadId)));
        }
    }

    /**
     * Forgets a batch, which is only correct once LaunchDarkly has accepted it or permanently refused it.
     */
    void remove(Batch batch) {
        boolean wasHeld;
        synchronized (heldLock) {
            wasHeld = inMemoryBatches.remove(batch.payloadId) != null;
        }

        if (!wasHeld && usesDisk) {
            synchronized (ioLock) {
                deleteQuietly(batchFile(batch.payloadId));
                eventCounts.remove(batch.payloadId);
            }
        }

        synchronized (bufferLock) {
            closedEvents = Math.max(0, closedEvents - batch.eventCount);
        }
    }

    /**
     * Closes any log this process left open on a previous run, so its events join the batches to deliver.
     * <p>
     * The events in it were recorded by a process that is gone, so there is no one left to add to it. Only
     * this process's own log is touched: another process's may still be open in a process that is alive.
     */
    void recoverInterruptedLog() {
        if (!usesDisk) {
            return;
        }
        synchronized (ioLock) {
            recoverInterruptedLogHoldingIoLock();
        }
    }

    /**
     * Requires {@code ioLock}. Runs at most once, and always before this process first opens its log:
     * after that, the log under this name holds this run's events, and closing it as a previous run's
     * would send them early and report them as recovered.
     */
    private void recoverInterruptedLogHoldingIoLock() {
        if (recoveryDone) {
            return;
        }
        recoveryDone = true;
        File log = diskLog().file();
        if (!log.exists()) {
            return;
        }
        int events;
        try {
            events = EventLogFormat.eventCount(readFile(log));
        } catch (IOException e) {
            // It cannot stay under this name, which this process is about to append its own events to,
            // and deleting it would lose events that may be intact. Closed uncounted instead, it is read
            // and counted by whichever listing first manages to.
            logger.warn("Could not read events from a previous run, will try again later: {}",
                    LogValues.exceptionSummary(e));
            log.renameTo(batchFile(UUID.randomUUID().toString()));
            return;
        }
        if (events < 0) {
            // Unreadable, and a log that cannot be read cannot be appended to either.
            deleteQuietly(log);
            return;
        }
        if (events == 0) {
            deleteQuietly(log);
            return;
        }
        String payloadId = UUID.randomUUID().toString();
        if (log.renameTo(batchFile(payloadId))) {
            eventCounts.put(payloadId, events);
            logger.info("Recovered {} event(s) that a previous run of this application did not deliver",
                    events);
        }
    }

    // MARK: writing

    /** Requires {@code ioLock}. */
    private void commitHoldingIoLock() {
        byte[] bytes;
        int events;
        synchronized (bufferLock) {
            if (!persistEvents || bufferedEventCount == 0) {
                // Staged bytes are left where they are: with nowhere durable to put them, memory is
                // better than dropping them.
                return;
            }
            bytes = bufferData.toByteArray();
            events = bufferedEventCount;
            bufferData.reset();
            bufferedEventCount = 0;
            inTransitEvents += events;
        }

        boolean written = false;
        try {
            written = append(bytes);
        } finally {
            // Counted where they landed in the same critical section that stops counting them in
            // transit, so a capacity check never sees them in both places or in neither.
            synchronized (bufferLock) {
                inTransitEvents -= events;
                if (written) {
                    committedEvents += events;
                } else {
                    // Put back, to be delivered from memory rather than lost.
                    byte[] laterFrames = bufferData.toByteArray();
                    bufferData.reset();
                    writeQuietly(bufferData, bytes);
                    writeQuietly(bufferData, laterFrames);
                    bufferedEventCount += events;
                }
            }
        }
    }

    /**
     * Requires {@code ioLock}.
     *
     * @return false if the bytes could not be written, in which case persistence is given up on
     */
    private boolean append(byte[] bytes) {
        synchronized (bufferLock) {
            if (!persistEvents) {
                return false;
            }
        }
        // Before the log is opened, because it may still be the one a previous run died writing. A commit
        // on the caller's thread can get here before the processor's queued startup task has recovered it,
        // and appended to, this run's events would sit behind that run's torn last frame.
        if (!diskLog().isOpen()) {
            recoverInterruptedLogHoldingIoLock();
        }
        try {
            diskLog().append(bytes);
            return true;
        } catch (IOException | RuntimeException e) {
            // Never let a full disk take the application down. The SDK gives up on persistence for the
            // rest of the session instead, which is how other SDKs have crashed their hosts.
            logger.warn("Giving up on persisting events: {}", LogValues.exceptionSummary(e));
            synchronized (bufferLock) {
                persistEvents = false;
            }
            return false;
        }
    }

    /** Overridable so a test can make a write fail partway through a session, as a full disk does. */
    @VisibleForTesting
    FileOutputStream openForAppending(File log) throws IOException {
        return new FileOutputStream(log, true);
    }

    @Override
    public void close() {
        synchronized (ioLock) {
            commitHoldingIoLock();
            // Not diskLog(): a store that never wrote has never asked the platform where it lives, and
            // shutting down is no reason to start.
            if (diskLog != null) {
                diskLog.closeOutput();
            }
        }
    }

    /** Requires {@code ioLock}. */
    private void resolveLocationHoldingIoLock() {
        if (directory == null) {
            File resolved = location.directory();
            diskLog = new DiskEventLog(resolved, location.processName(), ioLock, this::openForAppending,
                    logger);
            directory = resolved;
        }
    }

    /** Requires {@code ioLock}. */
    private File directory() {
        resolveLocationHoldingIoLock();
        return directory;
    }

    /** Requires {@code ioLock}. */
    private DiskEventLog diskLog() {
        resolveLocationHoldingIoLock();
        return diskLog;
    }

    /** Requires {@code ioLock}. */
    private File batchFile(String payloadId) {
        return new File(directory(), BATCH_PREFIX + payloadId);
    }

    /**
     * Requires {@code ioLock}.
     *
     * @return every batch file in the directory, oldest first, which is the order they are delivered in
     */
    private List<File> readyFilesOldestFirstHoldingIoLock() {
        List<File> ready = new ArrayList<>();
        File[] files = directory().listFiles();
        if (files == null) {
            return ready;
        }
        for (File file : files) {
            if (file.getName().startsWith(BATCH_PREFIX)) {
                ready.add(file);
            }
        }
        Collections.sort(ready, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        return ready;
    }

    File getDirectory() {
        synchronized (ioLock) {
            return directory();
        }
    }

    private void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            logger.debug("Could not delete {}", file);
        }
    }

    private static void writeQuietly(ByteArrayOutputStream target, byte[] bytes) {
        try {
            target.write(bytes);
        } catch (IOException e) {
            // ByteArrayOutputStream.write does not throw; this only satisfies the compiler.
        }
    }

    /**
     * @return the file's bytes, or null if it no longer exists
     * @throws IOException if it exists but could not be read. That may pass, and the file is intact:
     *   a process out of file descriptors fails to open it with the same exception a missing file
     *   gets, which is why existence is checked rather than the exception's type.
     */
    private byte[] readFile(File file) throws IOException {
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (InputStream in = openForReading(file)) {
            int read;
            while ((read = in.read(chunk)) > 0) {
                contents.write(chunk, 0, read);
            }
        } catch (IOException e) {
            if (!file.exists()) {
                return null;
            }
            throw e;
        }
        return contents.toByteArray();
    }

    /** For inspecting the store, where a file that cannot be read is simply left out. */
    private byte[] readFileOrNull(File file) {
        try {
            return readFile(file);
        } catch (IOException e) {
            return null;
        }
    }

    /** Overridable so a test can make a read fail while the file is intact. */
    @VisibleForTesting
    InputStream openForReading(File file) throws IOException {
        return new FileInputStream(file);
    }

    /**
     * A group of serialized events closed off for delivery.
     * <p>
     * The identifier doubles as the payload ID sent to LaunchDarkly, so a batch retried after the process
     * died mid-delivery is recognized upstream as the same delivery rather than counted twice.
     */
    static final class Batch {
        final String payloadId;
        final int eventCount;

        Batch(String payloadId, int eventCount) {
            this.payloadId = payloadId;
            this.eventCount = eventCount;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Batch)) {
                return false;
            }
            Batch o = (Batch) other;
            return payloadId.equals(o.payloadId) && eventCount == o.eventCount;
        }

        @Override
        public int hashCode() {
            return payloadId.hashCode() * 31 + eventCount;
        }

        @Override
        public String toString() {
            return "Batch(" + payloadId + ", " + eventCount + " events)";
        }
    }

    /**
     * Every event the store is holding, serialized exactly as it will be sent.
     * <p>
     * Reading the log means committing what is staged, so this is not free and is meant for tests and for
     * diagnosing a store rather than for the recording path.
     */
    List<byte[]> pendingEventPayloads() {
        commit();

        List<byte[]> logs = new ArrayList<>();
        if (usesDisk) {
            synchronized (ioLock) {
                for (File file : readyFilesOldestFirstHoldingIoLock()) {
                    logs.add(readFileOrNull(file));
                }
                if (diskLog().exists()) {
                    logs.add(readFileOrNull(diskLog().file()));
                }
            }
        }
        synchronized (bufferLock) {
            if (bufferedEventCount > 0) {
                logs.add(EventLogFormat.withFileHeader(bufferData.toByteArray()));
            }
        }

        List<byte[]> payloads = new ArrayList<>();
        for (byte[] log : logs) {
            payloads.addAll(EventLogFormat.eventPayloads(log));
        }
        return payloads;
    }
}
