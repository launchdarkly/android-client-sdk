package com.launchdarkly.sdk.android;

import androidx.annotation.VisibleForTesting;

import com.launchdarkly.logging.LDLogger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 * {@link DiskEventLog}; the directory the two of them live in is {@link EventDirectory}. This class is
 * what decides when events move between memory, that directory, and the batches closed off for delivery,
 * and what counts them against capacity on the way. It has no directory at all where the application asked
 * for events to be kept off the disk, which is what keeps the filesystem out of that case entirely.
 */
class EventStore implements Closeable {
    /**
     * Where the events are kept, or null where the application asked for them to be kept off the disk.
     * <p>
     * Everything about the filesystem is behind this, so that the store itself is the buffer, the capacity
     * arithmetic, and the batches it holds in memory. A store with no disk runs the way the SDK ran before
     * persistence existed, and never touches the filesystem, not even to look for a previous run's events.
     */
    private final EventDirectory disk;
    private final int capacity;
    private final LDLogger logger;

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
    /**
     * Events in closed batches: those the {@link #disk} is holding plus those in {@link #inMemoryBatches}.
     * Changed only in the critical section that changes one of those, so that it cannot drift from them; a
     * total worked out from them and written afterwards would overwrite a close or a removal in between.
     */
    private int closedEvents;
    /**
     * Closed batches whose request is out, by payload ID, and the events in them. Every one is also in
     * {@link #closedEvents}, and leaves both in the same critical section.
     */
    private final Map<String, Integer> sendingBatches = new HashMap<>();
    private int sendingEvents;
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
     * Guards {@link #inMemoryBatches}.
     * <p>
     * Its own lock rather than {@code ioLock}, so that a store the application told to keep off the disk -
     * where held batches are the only ones there are - never waits on the lock disk I/O is serialized
     * under. Taken inside {@code ioLock} where there is a disk, never the other way round, and taken
     * before {@code bufferLock}, never after it.
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

    EventStore(final File directory, final String processName, int capacity, boolean persistEvents,
               LDLogger logger) {
        this(new EventDirectory.Location() {
            @Override
            public File directory() {
                return directory;
            }

            @Override
            public String processName() {
                return processName;
            }
        }, capacity, persistEvents, logger);
    }

    private EventStore(EventDirectory.Location location, int capacity, boolean persistEvents,
                       LDLogger logger) {
        this.capacity = capacity >= 0 ? capacity : 1;
        this.logger = logger;
        // An application that has not asked for persistence gets the same store running the same way it
        // runs once a write has failed: events are held, delivered, and lost only if the process dies.
        // It also skips every read, so batches a run with persistence turned on left behind stay on disk
        // until persistence is turned back on.
        this.persistEvents = persistEvents;
        this.disk = persistEvents
                ? new EventDirectory(location, ioLock, logger, this::openForReading, this::openForAppending)
                : null;
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
        return new EventStore(EventDirectory.defaultDirectory(noBackupFilesDir, mobileKey), processName,
                capacity, persistEvents, logger);
    }

    /**
     * Creates the store for one environment of one process, leaving the platform to be asked where on
     * first use, from one of the store's own threads.
     *
     * @param platformState where the no-backup directory and the process name come from
     * @param mobileKey identifies the environment, so several clients stay out of each other's way
     * @param persistEvents whether the application asked for events to outlive the process
     */
    static EventStore create(
            final PlatformState platformState,
            String mobileKey,
            int capacity,
            boolean persistEvents,
            LDLogger logger
    ) {
        EventDirectory.Location location = new EventDirectory.Location() {
            @Override
            public File directory() {
                return EventDirectory.defaultDirectory(platformState.getNoBackupFilesDir(), mobileKey);
            }

            @Override
            public String processName() {
                return platformState.getProcessName();
            }
        };
        return new EventStore(location, capacity, persistEvents, logger);
    }

    // MARK: recording

    /**
     * A batch being sent is left out so that a flush makes room as soon as it sends, as it did when events
     * were only held in memory; otherwise an event recorded while the request is out is refused for a batch
     * LaunchDarkly may already have. A send that fails puts the batch back, so a full store can briefly
     * hold capacity plus that batch.
     *
     * @return serialized events counted against capacity: those recorded and not yet accepted by
     *   LaunchDarkly, whether staged in memory, on their way out of it, written to the open log, or sitting
     *   in a closed batch - except a batch whose request is out
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
        // Clamped because a batch another process delivered can be marked as sending after a listing
        // stopped counting it.
        return bufferedEventCount + inTransitEvents + committedEvents
                + Math.max(0, closedEvents - sendingEvents) + reservedEvents;
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
     * Staging is a copy into a buffer and never writes, however much accumulates; {@link #commit()} is what
     * makes the event outlive the process, so how many bytes one write carries is the caller's decision.
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

        synchronized (bufferLock) {
            if (!bypassingCapacity && countedEventsHoldingBufferLock() >= capacity) {
                return false;
            }
            EventLogFormat.writeFrame(bufferData, serializedEvent);
            bufferedEventCount++;
            if (reserved && reservedEvents > 0) {
                reservedEvents--;
            }
        }
        return true;
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
        if (disk == null) {
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
        EventDirectory.LogClose closed = disk.closeLog(events);
        if (closed.batch == null) {
            if (!closed.logSurvives) {
                // The log itself is gone, so its events are too. They have to stop counting against
                // capacity or the store would refuse events for the rest of the session.
                synchronized (bufferLock) {
                    committedEvents = 0;
                }
            }
            return null;
        }

        synchronized (bufferLock) {
            committedEvents = 0;
            closedEvents += events;
        }
        return closed.batch;
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
                synchronized (bufferLock) {
                    inTransitEvents -= events;
                    closedEvents += events;
                }
            }
            held = true;
        } finally {
            if (!held) {
                synchronized (bufferLock) {
                    inTransitEvents -= events;
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
        if (disk != null) {
            synchronized (ioLock) {
                batches = disk.listBatches();
                addHeldBatchesAndRecount(batches);
            }
        } else {
            batches = new ArrayList<>();
            addHeldBatchesAndRecount(batches);
        }
        return batches;
    }

    /**
     * Adds the batches the filesystem would not take, after the files: anything held was closed by this run
     * and so is newer than whatever a previous run left on the disk. Then counts everything listed as
     * closed, which is how batches another process closed or delivered come to be counted or not.
     * <p>
     * Requires {@code ioLock} where there is a disk, so that the count is of the listing as it stands.
     */
    private void addHeldBatchesAndRecount(List<Batch> batches) {
        synchronized (heldLock) {
            for (Map.Entry<String, HeldBatch> held : inMemoryBatches.entrySet()) {
                batches.add(new Batch(held.getKey(), held.getValue().eventCount));
            }
            int total = 0;
            Set<String> listed = new HashSet<>();
            for (Batch batch : batches) {
                total += batch.eventCount;
                listed.add(batch.payloadId);
            }
            synchronized (bufferLock) {
                closedEvents = total;
                // One no longer listed is no longer counted, so it cannot be left out of the count either.
                for (String payloadId : new ArrayList<>(sendingBatches.keySet())) {
                    if (!listed.contains(payloadId)) {
                        forgetSendingHoldingBufferLock(payloadId);
                    }
                }
            }
        }
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
        if (disk == null) {
            return null;
        }

        synchronized (ioLock) {
            return disk.body(batch.payloadId, batch.eventCount);
        }
    }

    /**
     * Forgets a batch, which is only correct once LaunchDarkly has accepted it or permanently refused it.
     */
    void remove(Batch batch) {
        // Only a count this call takes away stops counting. Two deliveries can both get to the same batch,
        // and a listing drops a batch another process delivered before this one gets to remove it.
        synchronized (heldLock) {
            HeldBatch held = inMemoryBatches.remove(batch.payloadId);
            if (held != null) {
                synchronized (bufferLock) {
                    closedEvents -= held.eventCount;
                    forgetSendingHoldingBufferLock(batch.payloadId);
                }
                return;
            }
        }

        if (disk == null) {
            synchronized (bufferLock) {
                forgetSendingHoldingBufferLock(batch.payloadId);
            }
            return;
        }
        synchronized (ioLock) {
            int counted = disk.remove(batch.payloadId, batch.eventCount);
            synchronized (bufferLock) {
                closedEvents -= counted;
                forgetSendingHoldingBufferLock(batch.payloadId);
            }
        }
    }

    /**
     * Stops counting a batch against capacity while its request is out. Ended by {@link #remove} or
     * {@link #stoppedSending}.
     */
    void startedSending(Batch batch) {
        synchronized (bufferLock) {
            if (!sendingBatches.containsKey(batch.payloadId)) {
                sendingBatches.put(batch.payloadId, batch.eventCount);
                sendingEvents += batch.eventCount;
            }
        }
    }

    /**
     * Counts a batch against capacity again, after a send that did not end with it removed. Does nothing
     * for a batch {@link #remove} already forgot.
     */
    void stoppedSending(Batch batch) {
        synchronized (bufferLock) {
            forgetSendingHoldingBufferLock(batch.payloadId);
        }
    }

    /**
     * Requires {@link #bufferLock}.
     */
    private void forgetSendingHoldingBufferLock(String payloadId) {
        Integer events = sendingBatches.remove(payloadId);
        if (events != null) {
            sendingEvents -= events;
        }
    }

    /**
     * Closes any log this process left open on a previous run, so its events join the batches to deliver.
     * <p>
     * The events in it were recorded by a process that is gone, so there is no one left to add to it. Only
     * this process's own log is touched: another process's may still be open in a process that is alive.
     */
    void recoverInterruptedLog() {
        if (disk == null) {
            return;
        }
        synchronized (ioLock) {
            countRecoveredHoldingIoLock(disk.recoverInterruptedLog());
        }
    }

    /**
     * Counts events a recovery closed off into batches of their own, which belong to the closed batches
     * from the moment the recovery moved them there.
     * <p>
     * Requires {@code ioLock}.
     */
    private void countRecoveredHoldingIoLock(int events) {
        if (events <= 0) {
            return;
        }
        synchronized (bufferLock) {
            closedEvents += events;
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
        EventDirectory.Append appended = disk.append(bytes);
        countRecoveredHoldingIoLock(appended.recovered);
        if (!appended.written) {
            synchronized (bufferLock) {
                persistEvents = false;
            }
            return false;
        }
        return true;
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
            if (disk != null) {
                disk.closeOpenLog();
            }
        }
    }

    /** @return where the events are kept, or null where the application asked for no disk */
    File getDirectory() {
        if (disk == null) {
            return null;
        }
        synchronized (ioLock) {
            return disk.directory();
        }
    }

    private static void writeQuietly(ByteArrayOutputStream target, byte[] bytes) {
        try {
            target.write(bytes);
        } catch (IOException e) {
            // ByteArrayOutputStream.write does not throw; this only satisfies the compiler.
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

        List<byte[]> payloads = new ArrayList<>();
        if (disk != null) {
            synchronized (ioLock) {
                payloads.addAll(disk.storedPayloads());
            }
        }
        synchronized (bufferLock) {
            if (bufferedEventCount > 0) {
                payloads.addAll(EventLogFormat.eventPayloads(
                        EventLogFormat.withFileHeader(bufferData.toByteArray())));
            }
        }
        return payloads;
    }
}
