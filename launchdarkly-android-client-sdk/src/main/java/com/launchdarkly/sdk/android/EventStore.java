package com.launchdarkly.sdk.android;

import androidx.annotation.VisibleForTesting;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
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
    /** The most bytes summaries may take the store to. */
    private final long byteCapacity;
    /**
     * The most bytes full events may take the store to: the byte capacity less a fifth of it, held back so
     * that summaries, which bypass {@link #capacity}, still have room once full events are being refused.
     */
    private final long eventByteCapacity;
    /** How long a batch may go unwritten before it is discarded rather than delivered. */
    private final long maxAgeMillis;
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
     * Closed batches whose request is out, by payload ID, and the events in them as {@link #closedEvents}
     * counts them. Every one is also counted there: one is only added while it is counted, and leaves in the
     * same critical section that stops counting it, so {@link #sendingEvents} can never exceed
     * {@link #closedEvents}.
     */
    private final Map<String, EventDirectory.Tally> sendingBatches = new HashMap<>();
    private int sendingEvents;
    /**
     * The bytes of the events counted above, state for state. Staged bytes are {@link #bufferData} itself.
     * Changed in the same critical sections as the event counts beside them, so that bytes and events are
     * never counted in different places.
     */
    private long inTransitBytes;
    private long committedBytes;
    private long closedBytes;
    private long sendingBytes;
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

    /**
     * Whether the directories of other environments have been looked at for ones nobody uses any more,
     * which is done once per process. Only to be used while holding {@link #ioLock}.
     */
    private boolean hasDiscardedAbandonedEnvironments;

    EventStore(File directory, int capacity, boolean persistEvents, LDLogger logger) {
        this(directory, capacity, EventProcessorBuilder.DEFAULT_CAPACITY_BYTES,
                EventProcessorBuilder.DEFAULT_MAX_AGE_MILLIS, persistEvents, logger);
    }

    EventStore(final File directory, int capacity, long byteCapacity, long maxAgeMillis,
               boolean persistEvents, LDLogger logger) {
        this(() -> directory, capacity, byteCapacity, maxAgeMillis, persistEvents, logger);
    }

    private EventStore(EventDirectory.Location location, int capacity, long byteCapacity, long maxAgeMillis,
                       boolean persistEvents, LDLogger logger) {
        this.capacity = capacity >= 0 ? capacity : 1;
        this.byteCapacity = byteCapacity > 0 ? byteCapacity : EventProcessorBuilder.DEFAULT_CAPACITY_BYTES;
        this.eventByteCapacity = this.byteCapacity - this.byteCapacity / 5;
        this.maxAgeMillis = maxAgeMillis > 0 ? maxAgeMillis : EventProcessorBuilder.DEFAULT_MAX_AGE_MILLIS;
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
     * @param persistEvents whether the application asked for events to outlive the process
     */
    static EventStore create(
            File noBackupFilesDir,
            String mobileKey,
            int capacity,
            boolean persistEvents,
            LDLogger logger
    ) {
        return create(noBackupFilesDir, mobileKey, capacity, EventProcessorBuilder.DEFAULT_CAPACITY_BYTES,
                EventProcessorBuilder.DEFAULT_MAX_AGE_MILLIS, persistEvents, logger);
    }

    static EventStore create(
            File noBackupFilesDir,
            String mobileKey,
            int capacity,
            long byteCapacity,
            long maxAgeMillis,
            boolean persistEvents,
            LDLogger logger
    ) {
        return new EventStore(EventDirectory.defaultDirectory(noBackupFilesDir, mobileKey), capacity,
                byteCapacity, maxAgeMillis, persistEvents, logger);
    }

    /**
     * Creates the store for one environment of one process, leaving the platform to be asked where on
     * first use, from one of the store's own threads.
     *
     * @param platformState where the no-backup directory comes from
     * @param mobileKey identifies the environment, so several clients stay out of each other's way
     * @param byteCapacity the most bytes the store holds, a fifth of which only summaries may use
     * @param maxAgeMillis how long a batch may go unwritten before it is discarded
     * @param persistEvents whether the application asked for events to outlive the process
     */
    static EventStore create(
            final PlatformState platformState,
            String mobileKey,
            int capacity,
            long byteCapacity,
            long maxAgeMillis,
            boolean persistEvents,
            LDLogger logger
    ) {
        EventDirectory.Location location =
                () -> EventDirectory.defaultDirectory(platformState.getNoBackupFilesDir(), mobileKey);
        return new EventStore(location, capacity, byteCapacity, maxAgeMillis, persistEvents, logger);
    }

    // MARK: recording

    /**
     * A batch being sent is left out so that a flush makes room as soon as it sends, as it did when events
     * were only held in memory; otherwise an event recorded while the request is out is refused for a batch
     * LaunchDarkly may already have. A send that fails puts the batch back, so a full store can briefly
     * hold capacity plus that batch: one at a time, since deliveries run one after another, and no more than
     * {@link EventLogFormat#MAX_BATCH_SIZE}.
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
        return bufferedEventCount + inTransitEvents + committedEvents
                + (closedEvents - sendingEvents) + reservedEvents;
    }

    /**
     * Every byte counted against the byte capacity. Requires {@link #bufferLock}.
     * <p>
     * Events reserved while being encoded have no bytes yet; they are counted when they are staged.
     */
    private long countedBytesHoldingBufferLock() {
        return bufferData.size() + inTransitBytes + committedBytes + (closedBytes - sendingBytes);
    }

    /**
     * Whether full events can still be admitted by size, asked before an event is kept for encoding so
     * that one there is no room for is refused without the cost of encoding it. Staging checks again
     * against the event's actual size.
     */
    boolean hasRoomForEventBytes() {
        // A store kept off the disk holds events the way the SDK did before persistence, bounded by count
        // alone.
        if (disk == null) {
            return true;
        }
        synchronized (bufferLock) {
            return countedBytesHoldingBufferLock() < eventByteCapacity;
        }
    }

    /**
     * {@link #getPendingEventCount} and {@link #hasRoomForEventBytes} in one acquisition of the lock, for the
     * check every recorded event makes, where the lock is contended by the commit staging events.
     *
     * @param heldElsewhere events the caller holds that the store does not count yet
     * @param capacity the most events to hold, counting {@code heldElsewhere}
     * @return whether one more full event can be admitted, by count and by bytes
     */
    boolean admitsEvent(int heldElsewhere, int capacity) {
        synchronized (bufferLock) {
            return heldElsewhere + countedEventsHoldingBufferLock() < capacity
                    && (disk == null || countedBytesHoldingBufferLock() < eventByteCapacity);
        }
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
     * @return false if the event was dropped because capacity or the byte capacity is reached
     */
    boolean stage(byte[] serializedEvent) {
        return stage(serializedEvent, false);
    }

    /**
     * @param bypassingCapacity true for a summary, which must be recorded even when the store is full of
     *   events, and may use the bytes held back from them; where events are persisted it is still refused
     *   once the byte capacity itself is reached
     */
    boolean stage(byte[] serializedEvent, boolean bypassingCapacity) {
        return stage(serializedEvent, bypassingCapacity, false);
    }

    /**
     * Stages an event {@link #reserve} already counted, turning its reservation into a staged event in
     * one step. Bypasses capacity, which the reservation already answered to, but not the bytes left for
     * full events, which only the event's encoded size can answer.
     */
    boolean stageReserved(byte[] serializedEvent) {
        return stage(serializedEvent, true, true);
    }

    private boolean stage(byte[] serializedEvent, boolean bypassingCapacity, boolean reserved) {
        if (serializedEvent == null || serializedEvent.length == 0
                || serializedEvent.length > EventLogFormat.MAX_FRAME_SIZE) {
            return false;
        }
        long frameSize = (long) serializedEvent.length + EventLogFormat.FRAME_HEADER_SIZE;
        // Only a summary bypasses capacity without a reservation, and only a summary may use the bytes held
        // back.
        boolean isSummary = bypassingCapacity && !reserved;

        synchronized (bufferLock) {
            // In the same critical section as the append, so the event is never counted twice or not at all.
            if (reserved && reservedEvents > 0) {
                reservedEvents--;
            }
            if (!bypassingCapacity && countedEventsHoldingBufferLock() >= capacity) {
                return false;
            }
            if (disk != null
                    && countedBytesHoldingBufferLock() + frameSize > (isSummary ? byteCapacity : eventByteCapacity)) {
                return false;
            }
            EventLogFormat.writeFrame(bufferData, serializedEvent);
            bufferedEventCount++;
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
     * @return the batches this closed, oldest first: the one from the log, then those from memory; usually
     *   one, and empty when there is nothing to send. A log a commit filled was closed into a batch of its
     *   own as it filled, and is not among them; {@link #pendingBatches()} lists every batch.
     */
    List<Batch> closeBatch() {
        List<Batch> closed = new ArrayList<>(1);
        // Where the application asked for no disk there is no log to close, and so nothing to close it
        // alongside.
        if (disk == null) {
            closed.addAll(closeInMemoryBatches());
            return closed;
        }
        synchronized (ioLock) {
            commitHoldingIoLock();

            int events;
            long bytes;
            int stillBuffered;
            synchronized (bufferLock) {
                events = committedEvents;
                bytes = committedBytes;
                stillBuffered = bufferedEventCount;
            }

            if (events > 0) {
                addIfClosed(closed, closeOpenLogHoldingIoLock(events, bytes));
            }
            // Only reachable once persistence has been given up on, or a full log could not be closed; a
            // healthy commit leaves nothing staged behind. Closed after the log, so the older events are
            // delivered first.
            if (stillBuffered > 0) {
                closed.addAll(closeInMemoryBatches());
            }
            return closed;
        }
    }

    private static void addIfClosed(List<Batch> closed, Batch batch) {
        if (batch != null) {
            closed.add(batch);
        }
    }

    /**
     * Requires {@code ioLock}.
     *
     * @param bytes the frames committed to the log, which with the file header are the batch's size
     */
    private Batch closeOpenLogHoldingIoLock(int events, long bytes) {
        long batchBytes = bytes + EventLogFormat.FILE_HEADER_SIZE;
        EventDirectory.LogClose closed = disk.closeLog(events, batchBytes);
        if (closed.batch == null) {
            if (!closed.logSurvives) {
                // The log itself is gone, so its events are too. They have to stop counting against
                // capacity or the store would refuse events for the rest of the session.
                synchronized (bufferLock) {
                    committedEvents = 0;
                    committedBytes = 0;
                }
            }
            return null;
        }

        synchronized (bufferLock) {
            committedEvents = 0;
            committedBytes = 0;
            closedEvents += events;
            closedBytes += batchBytes;
        }
        return closed.batch;
    }

    /**
     * Closes whatever is staged into batches that never reach a file, as many as it takes to keep each
     * within {@link EventLogFormat#MAX_BATCH_SIZE}.
     * <p>
     * Called under {@code ioLock} where there is a disk, so that it and the log are closed as one step,
     * and on its own where there is not.
     *
     * @return the batches, oldest first
     */
    private List<Batch> closeInMemoryBatches() {
        byte[] frames;
        int events;
        synchronized (bufferLock) {
            frames = bufferData.toByteArray();
            events = bufferedEventCount;
            bufferData.reset();
            bufferedEventCount = 0;
            inTransitEvents += events;
            inTransitBytes += frames.length;
        }
        List<Batch> closed = new ArrayList<>(1);
        int held = 0;
        int heldEvents = 0;
        try {
            // Outside bufferLock, because it copies every staged frame; inTransitEvents is what keeps
            // them counted meanwhile.
            while (held < frames.length) {
                int end = EventLogFormat.endOfFramesFitting(frames, held,
                        EventLogFormat.MAX_BATCH_SIZE - EventLogFormat.FILE_HEADER_SIZE);
                int batchEvents = EventLogFormat.frameCount(frames, held, end);
                byte[] body = EventLogFormat.assembleBody(EventLogFormat.withFileHeader(frames, held, end));
                if (body == null) {
                    break;
                }
                String payloadId = UUID.randomUUID().toString();
                synchronized (heldLock) {
                    inMemoryBatches.put(payloadId, new HeldBatch(body, batchEvents));
                    synchronized (bufferLock) {
                        inTransitEvents -= batchEvents;
                        inTransitBytes -= end - held;
                        closedEvents += batchEvents;
                        closedBytes += body.length;
                    }
                }
                closed.add(new Batch(payloadId, batchEvents));
                held = end;
                heldEvents += batchEvents;
            }
        } finally {
            if (held < frames.length) {
                synchronized (bufferLock) {
                    inTransitEvents -= events - heldEvents;
                    inTransitBytes -= frames.length - held;
                }
            }
        }
        return closed;
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
                addHeldBatchesAndRecount(batches, disk.storedBytes());
            }
        } else {
            batches = new ArrayList<>();
            addHeldBatchesAndRecount(batches, 0);
        }
        return batches;
    }

    /**
     * Adds the batches the filesystem would not take, after the files: anything held was closed by this run
     * and so is newer than whatever a previous run left on the disk. Then counts everything listed as
     * closed, which is how batches another process closed or delivered come to be counted or not.
     * <p>
     * Requires {@code ioLock} where there is a disk, so that the count is of the listing as it stands.
     *
     * @param storedBytes the bytes in the batches the listing found on the disk
     */
    private void addHeldBatchesAndRecount(List<Batch> batches, long storedBytes) {
        synchronized (heldLock) {
            long totalBytes = storedBytes;
            for (Map.Entry<String, HeldBatch> held : inMemoryBatches.entrySet()) {
                batches.add(new Batch(held.getKey(), held.getValue().eventCount));
                totalBytes += held.getValue().body.length;
            }
            int total = 0;
            Set<String> listed = new HashSet<>();
            for (Batch batch : batches) {
                total += batch.eventCount;
                listed.add(batch.payloadId);
            }
            synchronized (bufferLock) {
                closedEvents = total;
                closedBytes = totalBytes;
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
     * A batch that is there to read and holds nothing this version can send: damaged, or written in a
     * format it does not read. Unlike a batch that is gone, its events are lost, and unlike any other
     * {@link IOException}, no later attempt changes that.
     */
    static final class UnreadableBatchException extends IOException {
        UnreadableBatchException(String payloadId) {
            super("batch " + payloadId + " holds nothing this version can send");
        }
    }

    /**
     * @return the JSON request body for a batch, or null if it is gone or holds no events, neither of which
     *   loses anything
     * @throws UnreadableBatchException if the batch is there but holds nothing this version can send
     * @throws IOException if the batch is there but could not be read this time, which a later attempt may
     *   manage
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
                    closedBytes -= held.body.length;
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
            EventDirectory.Tally counted = disk.remove(batch.payloadId, batch.eventCount);
            synchronized (bufferLock) {
                closedEvents -= counted.events;
                closedBytes -= counted.bytes;
                forgetSendingHoldingBufferLock(batch.payloadId);
            }
        }
    }

    /**
     * Stops counting a batch against capacity while its request is out. Ended by {@link #remove} or
     * {@link #stoppedSending}.
     * <p>
     * Does nothing for a batch this store no longer counts - one {@link #remove} forgot, or a listing
     * dropped because another process delivered it - since leaving it out would take its events from the
     * batches still counted.
     */
    void startedSending(Batch batch) {
        if (disk == null) {
            startedSendingHoldingIoLock(batch.payloadId);
            return;
        }
        synchronized (ioLock) {
            startedSendingHoldingIoLock(batch.payloadId);
        }
    }

    /**
     * Requires {@code ioLock} where there is a disk, so that the batch cannot stop being counted between
     * finding it counted and leaving it out.
     */
    private void startedSendingHoldingIoLock(String payloadId) {
        synchronized (heldLock) {
            EventDirectory.Tally counted;
            HeldBatch held = inMemoryBatches.get(payloadId);
            if (held != null) {
                counted = new EventDirectory.Tally(held.eventCount, held.body.length);
            } else if (disk != null) {
                counted = disk.counted(payloadId);
            } else {
                counted = null;
            }
            if (counted == null) {
                return;
            }
            synchronized (bufferLock) {
                if (!sendingBatches.containsKey(payloadId)) {
                    sendingBatches.put(payloadId, counted);
                    sendingEvents += counted.events;
                    sendingBytes += counted.bytes;
                }
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
        EventDirectory.Tally sending = sendingBatches.remove(payloadId);
        if (sending != null) {
            sendingEvents -= sending.events;
            sendingBytes -= sending.bytes;
        }
    }

    /**
     * Closes every log no live process holds, so their events join the batches to deliver.
     * <p>
     * The events in them were recorded by processes that are gone, so there is no one left to add to them.
     * A log a live process holds, this store's own included, is left alone.
     */
    void preparePayloadsFromInterruptedLogs() {
        if (disk == null) {
            return;
        }
        synchronized (ioLock) {
            countRecoveredHoldingIoLock(disk.preparePayloadsFromInterruptedLogs());
        }
    }

    /**
     * Counts events a recovery closed off into batches of their own, which belong to the closed batches
     * from the moment the recovery moved them there.
     * <p>
     * Requires {@code ioLock}.
     */
    private void countRecoveredHoldingIoLock(EventDirectory.Tally recovered) {
        if (recovered.events <= 0) {
            return;
        }
        synchronized (bufferLock) {
            closedEvents += recovered.events;
            closedBytes += recovered.bytes;
        }
    }

    /**
     * Deletes stored events older than the maximum age, so that what an application can no longer deliver
     * does not keep the device's storage, or this store's capacity, for ever.
     * <p>
     * Its own directory first, under {@code ioLock}; then, once per process, the directories of environments
     * nothing has written to for as long, outside it, since none of that is this store's.
     *
     * @return how many events were discarded, which the caller counts as dropped
     */
    int discardExpired() {
        if (disk == null) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        int discarded;
        File directory = null;
        synchronized (ioLock) {
            Set<String> sending;
            synchronized (bufferLock) {
                sending = new HashSet<>(sendingBatches.keySet());
            }
            EventDirectory.Discarded result = disk.discard(cutoff, sending);
            // In the same critical section as the delete, as for any other path that loses events without
            // delivering them, so the store cannot go on counting what is gone.
            synchronized (bufferLock) {
                closedEvents -= result.counted.events;
                closedBytes -= result.counted.bytes;
            }
            discarded = result.events;
            if (!hasDiscardedAbandonedEnvironments) {
                hasDiscardedAbandonedEnvironments = true;
                directory = disk.directory();
            }
        }
        if (directory != null) {
            discarded += EventDirectory.discardAbandonedEnvironments(directory, cutoff);
        }
        return discarded;
    }

    // MARK: writing

    /**
     * Requires {@code ioLock}.
     * <p>
     * Closes the open log into a batch wherever the next frame would take it past
     * {@link EventLogFormat#MAX_BATCH_SIZE}, so a commit can land in several batches.
     */
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
            inTransitBytes += bytes.length;
        }

        int written = 0;
        int writtenEvents = 0;
        try {
            while (written < bytes.length) {
                long room;
                synchronized (bufferLock) {
                    room = EventLogFormat.MAX_BATCH_SIZE - EventLogFormat.FILE_HEADER_SIZE - committedBytes;
                }
                if (EventLogFormat.frameSize(bytes, written) > room) {
                    if (!closeFullLogHoldingIoLock()) {
                        break;
                    }
                    continue;
                }
                int end = EventLogFormat.endOfFramesFitting(bytes, written, room);
                int landing = EventLogFormat.frameCount(bytes, written, end);
                boolean isWhole = written == 0 && end == bytes.length;
                if (!append(isWhole ? bytes : Arrays.copyOfRange(bytes, written, end))) {
                    break;
                }
                // Counted where they landed in the same critical section that stops counting them in
                // transit, so a capacity check never sees them in both places or in neither.
                synchronized (bufferLock) {
                    inTransitEvents -= landing;
                    inTransitBytes -= end - written;
                    committedEvents += landing;
                    committedBytes += end - written;
                }
                written = end;
                writtenEvents += landing;
            }
        } finally {
            if (written < bytes.length) {
                synchronized (bufferLock) {
                    inTransitEvents -= events - writtenEvents;
                    inTransitBytes -= bytes.length - written;
                    // Put back, to be delivered from memory rather than lost.
                    byte[] laterFrames = bufferData.toByteArray();
                    bufferData.reset();
                    bufferData.write(bytes, written, bytes.length - written);
                    writeQuietly(bufferData, laterFrames);
                    bufferedEventCount += events - writtenEvents;
                }
            }
        }
    }

    /**
     * Closes an open log that has no room for the next frame, so that the frame starts a batch of its own.
     * Requires {@code ioLock}.
     *
     * @return whether the next append starts a new log; false if this one could not be closed, which
     *   leaves the frames to be held in memory rather than grow it past the size of a batch
     */
    private boolean closeFullLogHoldingIoLock() {
        int events;
        long bytes;
        synchronized (bufferLock) {
            events = committedEvents;
            bytes = committedBytes;
        }
        closeOpenLogHoldingIoLock(events, bytes);
        synchronized (bufferLock) {
            return committedBytes == 0;
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
        if (!disk.append(bytes)) {
            synchronized (bufferLock) {
                persistEvents = false;
            }
            return false;
        }
        return true;
    }

    /** Overridable so a test can make a write fail partway through a session, as a full disk does. */
    @VisibleForTesting
    FileOutputStream openForAppending(File file) throws IOException {
        return new FileOutputStream(file, true);
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
