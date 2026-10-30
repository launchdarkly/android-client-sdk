package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The directory one store keeps its events in: the log this process appends to, and next to it the batches
 * that have been closed off for delivery.
 * <p>
 * Everything about the filesystem lives here: where the directory is, what the files in it are called, and
 * how they are listed, read, moved and deleted. A store the application told to keep events off the disk
 * has no {@code EventDirectory}, rather than one it is careful never to use.
 * <p>
 * The directory's location is worked out on first use rather than at construction. A store that never
 * reaches for the disk then makes no filesystem call at all, not even to find out where it would have
 * written. That matters because the store is built on the thread that called {@code LDClient.init}, usually the main
 * one: the platform creates the no-backup directory the first time it is asked for it.
 * <p>
 * Belongs to an {@link EventStore}, which makes every call holding its {@code ioLock}, exactly as
 * {@link DiskEventLog} belongs to this. The lock is handed down so that each call can check it is held,
 * rather than each level taking a lock of its own that would have to be ordered against the others.
 */
class EventDirectory {
    /**
     * Names a log closed off for delivery, whichever version named it. What follows is the version of the
     * rest of the name, {@link #BATCH_NAME_VERSION}, then the batch's payload ID and how many events are in
     * it: {@code ready-v1-<payloadId>.<count>}. Anything more a batch needs to say belongs in a frame type
     * readers skip, rather than in the name.
     * <p>
     * The count is in the name so that listing a batch this process did not close costs a directory entry
     * rather than a read of the whole file. That is what every batch is at startup: the ones a previous run
     * left behind, and the ones belonging to another process sharing the environment. Reading them all is
     * done holding {@code ioLock}, which a commit at a commit point then waits behind.
     * <p>
     * A batch is never appended to once it is closed, so the count is as good as the file is.
     */
    private static final String BATCH_PREFIX = "ready-";
    /**
     * The version of how the rest of a batch's name reads, so that a later version may change it without this
     * one misreading the count. A batch another version named is never listed, counted or sent: reading it is
     * that version's to do. Expiry still removes it, as it does any batch, so that a file this version does not
     * read cannot stay on the device for good. A payload ID is a UUID, which cannot start with a {@code v}.
     */
    private static final String BATCH_NAME_VERSION = "v1-";
    /** Names a batch this version named, and so reads. */
    private static final String OWN_BATCH_PREFIX = BATCH_PREFIX + BATCH_NAME_VERSION;
    private static final char NAME_FIELD_SEPARATOR = '.';
    /**
     * Names a log whose events are not yet in a batch: one its owner is appending to, or one left by an owner
     * that is gone, for recovery to close off. The rest of the name is the UUID it was opened under.
     */
    static final String LOG_PREFIX = "pending-";
    /**
     * Names a log while it is being opened, before it is locked and has its header. Recovery never looks at
     * these; one left by a process that died while opening it holds no events, and expiry removes it.
     */
    static final String OPENING_LOG_PREFIX = "new-";

    /**
     * The largest file this version reads. It writes none larger than {@link EventLogFormat#MAX_BATCH_SIZE},
     * so one past this is not its own, whatever it holds. Read anyway, it would be held in memory whole, and
     * one large enough would fail every pass that reached it, without ever being deleted. Twice the batch size,
     * so that a later version may raise its own a little without an earlier one losing what it wrote.
     */
    static final long MAX_READ_SIZE = 2L * EventLogFormat.MAX_BATCH_SIZE;

    /** A stored file larger than {@link #MAX_READ_SIZE}, which is never read and so can never be delivered. */
    static final class TooLargeException extends IOException {
        TooLargeException(long size) {
            super("a stored file of " + size + " bytes, larger than this version reads");
        }
    }
    private static final String DIRECTORY_NAME = "com.launchdarkly.events";

    /** Where one store keeps its files. */
    interface Location {
        File directory();
    }

    /** How a stored file is read, so a test can make a read fail while the file is intact. */
    interface Reader {
        FileInputStream openForReading(File file) throws IOException;
    }

    /**
     * How many events, and how many bytes, something stored holds: the two things capacity counts. With how
     * many of the events are summaries, which capacity leaves out, where this store wrote them.
     */
    static final class Tally {
        static final Tally NONE = new Tally(0, 0);

        final int events;
        /** How many of {@link #events} are known to be summaries; none for a batch this store did not close. */
        final int summaries;
        final long bytes;

        Tally(int events, long bytes) {
            this(events, 0, bytes);
        }

        Tally(int events, int summaries, long bytes) {
            this.events = events;
            this.summaries = summaries;
            this.bytes = bytes;
        }

        Tally plus(Tally other) {
            return new Tally(events + other.events, summaries + other.summaries, bytes + other.bytes);
        }
    }

    /** What discarding expired files did. */
    static final class Discarded {
        /** How many events went with them. */
        final int events;
        /** How much of that this process was counting, which is what stops counting against capacity. */
        final Tally counted;

        Discarded(int events, Tally counted) {
            this.events = events;
            this.counted = counted;
        }
    }

    private final Location location;
    private final Object ioLock;
    private final LDLogger logger;
    private final Reader reader;
    private final DiskEventLog.Opener opener;

    // Guarded by ioLock, and null until locateHoldingIoLock has run.
    private File directory;
    private DiskEventLog diskLog;
    /** Whether {@link #openInThisProcess} counts this store, from locating the directory until closing. */
    private boolean countedAsOpen;

    /**
     * What this process knows about each batch in the directory, by payload ID, which is how everything
     * outside this class names one.
     * <p>
     * The file is remembered rather than rebuilt from the payload ID, because the count is part of the name
     * and a batch named without one has no count to rebuild it with. The count is remembered so that such
     * a batch is read at most once.
     * <p>
     * Only to be used while holding {@code ioLock}.
     */
    private final Map<String, Stored> stored = new HashMap<>();

    /**
     * A batch in the directory: the file it is, how many events it holds, how many of them this store knows
     * to be summaries, how big it is, and when it was last written, which is what orders batches oldest first.
     */
    private static final class Stored {
        final File file;
        final int events;
        final int summaries;
        final long bytes;
        final long modified;

        Stored(File file, int events, int summaries, long bytes, long modified) {
            this.file = file;
            this.events = events;
            this.summaries = summaries;
            this.bytes = bytes;
            this.modified = modified;
        }

        Tally tally() {
            return new Tally(events, summaries, bytes);
        }
    }

    /**
     * Batches LaunchDarkly has accepted whose file the filesystem would not let go of.
     * <p>
     * Left out of every listing, because a batch that is delivered and merely still on the disk would otherwise
     * be found again by the next delivery, and the one after that, and sent each time. Each listing tries to
     * delete it once more, so a directory that stops being read-only clears itself.
     * <p>
     * Only for this session: a later run, which may well be able to delete the file, treats it as it would any
     * other batch a previous run left behind. LaunchDarkly discards a repeated payload ID, so the cost of that is
     * a wasted request rather than events counted twice.
     * <p>
     * Only to be used while holding {@code ioLock}.
     */
    private final Set<String> deliveredButUndeleted = new HashSet<>();

    /**
     * The batches this store has a request out for, by payload ID: the file each was locked under, so the lock
     * is released from that file even once the batch has been removed or dropped from a listing, and how many
     * of this store's deliveries are sending it.
     * <p>
     * Only to be used while holding {@code ioLock}.
     */
    private final Map<String, Sending> sending = new HashMap<>();

    private static final class Sending {
        final File file;
        int senders;

        Sending(File file) {
            this.file = file;
        }
    }

    /**
     * The directories of the stores open in this process, by absolute path, with how many stores have each
     * open. Another environment's sweep leaves these alone, however long it is since they were written: a
     * store that is open may be about to write, or be sending a batch older than the cutoff.
     */
    private static final Map<String, Integer> openInThisProcess = new HashMap<>();

    EventDirectory(Location location, Object ioLock, LDLogger logger, Reader reader,
                   DiskEventLog.Opener opener) {
        this.location = location;
        this.ioLock = ioLock;
        this.logger = logger;
        this.reader = reader;
        this.opener = opener;
    }

    /** Requires {@code ioLock}. */
    private void locateHoldingIoLock() {
        assert Thread.holdsLock(ioLock);
        if (directory == null) {
            File resolved = location.directory();
            diskLog = new DiskEventLog(resolved, ioLock, opener, logger);
            directory = resolved;
            countedAsOpen = true;
            synchronized (openInThisProcess) {
                Integer stores = openInThisProcess.get(resolved.getAbsolutePath());
                openInThisProcess.put(resolved.getAbsolutePath(), stores == null ? 1 : stores + 1);
            }
        }
    }

    private static boolean isOpenInThisProcess(File environment) {
        synchronized (openInThisProcess) {
            return openInThisProcess.containsKey(environment.getAbsolutePath());
        }
    }

    /**
     * Where the batches are kept, worked out the first time the directory is reached for.
     * <p>
     * Requires {@code ioLock}.
     */
    File directory() {
        locateHoldingIoLock();
        return directory;
    }

    /** Requires {@code ioLock}. */
    private DiskEventLog diskLog() {
        locateHoldingIoLock();
        return diskLog;
    }

    /**
     * A batch is identified by its payload ID rather than by a path, so that a batch listed from the
     * directory and the same batch as it was closed are one thing.
     * <p>
     * Requires {@code ioLock}.
     *
     * @param events what the caller holding the batch knows it to contain, which is what names it where
     *   this process has neither closed nor listed it - as when it delivers a batch another process closed
     */
    private File batchFile(String payloadId, int events) {
        Stored known = stored.get(payloadId);
        return known == null ? newBatchFile(payloadId, events) : known.file;
    }

    /** Requires {@code ioLock}. */
    private File newBatchFile(String payloadId, int events) {
        return new File(directory(), OWN_BATCH_PREFIX + payloadId + NAME_FIELD_SEPARATOR + events);
    }

    /** A name for a batch of events not yet counted, which the first listing to read it counts. */
    private File newUncountedBatchFile() {
        return new File(directory(), OWN_BATCH_PREFIX + UUID.randomUUID());
    }

    /** Whether a file is a batch some other version named, which this one leaves to expiry. */
    private static boolean isAnotherVersionsBatch(String fileName) {
        return fileName.startsWith(BATCH_PREFIX) && !fileName.startsWith(OWN_BATCH_PREFIX);
    }

    /**
     * The count in the name of a batch this version named, {@code ready-v1-<payloadId>.<count>}: the second
     * field, by position.
     * <p>
     * Fields are split at {@link #NAME_FIELD_SEPARATOR}, which a payload ID never contains, so nothing
     * about the count's own characters is needed to tell it apart. Fields after it are ignored.
     *
     * @return the count, or -1 where the name has none or it does not parse - a batch a recovery could not
     *   read, which is read once to count it
     */
    private static int eventsInName(String fileName) {
        String[] fields = fileName.substring(OWN_BATCH_PREFIX.length()).split("\\" + NAME_FIELD_SEPARATOR, -1);
        if (fields.length < 2 || fields[1].isEmpty()) {
            return -1;
        }
        for (int i = 0; i < fields[1].length(); i++) {
            char c = fields[1].charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            return Integer.parseInt(fields[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The payload ID in the name of a batch this version named: the first field, whatever follows it. */
    private static String payloadIdInName(String fileName) {
        String rest = fileName.substring(OWN_BATCH_PREFIX.length());
        int end = rest.indexOf(NAME_FIELD_SEPARATOR);
        return end < 0 ? rest : rest.substring(0, end);
    }

    /** A name for a new log in {@code directory}, under a UUID no other writer picks. */
    static File newLogFile(File directory) {
        return new File(directory, LOG_PREFIX + UUID.randomUUID());
    }

    /** Where {@code log} is created and given its header, before it is moved to its own name. */
    static File openingFileFor(File log) {
        return new File(log.getParentFile(), OPENING_LOG_PREFIX + log.getName().substring(LOG_PREFIX.length()));
    }

    // ---- writing ----

    /**
     * Appends to the open log, opening a new one where none is open.
     * <p>
     * Requires {@code ioLock}.
     *
     * @return whether every byte landed. False means none of them is in the log, and that the caller should
     *   give up on the disk.
     */
    boolean append(byte[] bytes) {
        assert Thread.holdsLock(ioLock);
        try {
            diskLog().append(bytes);
            return true;
        } catch (IOException | RuntimeException e) {
            // Never let a full disk take the application down. The SDK gives up on persistence for the
            // rest of the session instead, which is how other SDKs have crashed their hosts.
            logger.warn("Giving up on persisting events: {}", LogValues.exceptionSummary(e));
            return false;
        }
    }

    /** What closing the open log produced. */
    static final class LogClose {
        /** The batch the log became, or null where the close failed. */
        final EventStore.Batch batch;
        /**
         * Where the close failed, whether the log is still there to try again with; it is then still the
         * open log, and its events still count as the open log's. Where it is not - a
         * directory the platform reclaimed, say - its events are gone, and they have to stop counting
         * against capacity, or the store would refuse events for the rest of the session.
         */
        final boolean logSurvives;
        /**
         * Where the log is gone, whether nothing could have delivered its events: it was still held, so no
         * recovery could take it. One no longer held, as persistence given up on leaves it, may have been
         * moved into a batch by a recovery, which delivers it.
         */
        final boolean deletedUnsent;

        private LogClose(EventStore.Batch batch, boolean logSurvives, boolean deletedUnsent) {
            this.batch = batch;
            this.logSurvives = logSurvives;
            this.deletedUnsent = deletedUnsent;
        }
    }

    /**
     * Closes the open log into a batch, so the next append starts a new one.
     * <p>
     * Requires {@code ioLock}.
     *
     * @param events how many events the log holds, which the caller has counted as it staged them
     * @param summaries how many of {@code events} are summaries
     * @param bytes how big the log is, which the caller has counted as it wrote it
     */
    LogClose closeLog(int events, int summaries, long bytes) {
        assert Thread.holdsLock(ioLock);
        String payloadId = UUID.randomUUID().toString();
        File file = newBatchFile(payloadId, events);
        // Asked before the move, which closes a log it finds gone.
        boolean held = diskLog().isHeld();
        if (!diskLog().closeMovingTo(file)) {
            logger.warn("Could not close the event log for delivery");
            boolean survives = diskLog().exists();
            return new LogClose(null, survives, !survives && held);
        }

        stored.put(payloadId, new Stored(file, events, summaries, bytes, System.currentTimeMillis()));
        return new LogClose(new EventStore.Batch(payloadId, events), true, false);
    }

    /**
     * Closes the descriptor, leaving everything where it is, and stops counting the directory as open in this
     * process. Requires {@code ioLock}.
     */
    void closeOpenLog() {
        assert Thread.holdsLock(ioLock);
        // Not diskLog(): a store that never wrote has never asked the platform where it lives, and shutting
        // down is no reason to start.
        if (diskLog != null) {
            diskLog.closeOutput();
        }
        if (countedAsOpen) {
            countedAsOpen = false;
            synchronized (openInThisProcess) {
                String path = directory.getAbsolutePath();
                Integer stores = openInThisProcess.get(path);
                if (stores != null && stores > 1) {
                    openInThisProcess.put(path, stores - 1);
                } else {
                    openInThisProcess.remove(path);
                }
            }
        }
    }

    /**
     * Locks a batch against expiry, in this process and every other, for as long as a request for it is out.
     * Each call is ended by one {@link #stoppedSending}. Requires {@code ioLock}.
     *
     * @param events what the caller holding the batch knows it to contain, as for {@link #payload}
     */
    void startedSending(String payloadId, int events) {
        assert Thread.holdsLock(ioLock);
        Sending batch = sending.get(payloadId);
        if (batch == null) {
            batch = new Sending(batchFile(payloadId, events));
            sending.put(payloadId, batch);
        }
        batch.senders++;
        BatchLocks.startedSending(batch.file);
    }

    /** Ends one {@link #startedSending}. Requires {@code ioLock}. */
    void stoppedSending(String payloadId) {
        assert Thread.holdsLock(ioLock);
        Sending batch = sending.get(payloadId);
        if (batch == null) {
            return;
        }
        BatchLocks.stoppedSending(batch.file);
        if (--batch.senders == 0) {
            sending.remove(payloadId);
        }
    }

    // ---- listing and reading ----

    /**
     * Requires {@code ioLock}.
     * <p>
     * Sizes come from the directory entries, so that counting the directory's bytes reads no file either.
     * <p>
     * A directory that is not there holds nothing. One that is there and cannot be listed - out of file
     * descriptors, or no longer readable - is not empty, so it answers with the batches the last listing or
     * close counted, as they were counted, and says the listing is incomplete.
     *
     * @return the batches the directory holds, oldest first, which is the order they are delivered in
     */
    EventStore.Listing listBatches() {
        assert Thread.holdsLock(ioLock);
        List<ReadyFile> ready = readyFilesOldestFirst();
        if (ready == null) {
            logger.info("Could not list stored events, will try again at the next delivery");
            return new EventStore.Listing(knownBatchesOldestFirst(), false);
        }
        List<EventStore.Batch> batches = new ArrayList<>();
        for (ReadyFile entry : ready) {
            File file = entry.file;
            int events = eventsInName(file.getName());
            String payloadId = payloadIdInName(file.getName());
            if (isDeliveredButUndeleted(payloadId, file)) {
                continue;
            }
            // Reading is only for a batch whose name does not count it, which is one a recovery could not read,
            // and then only the once.
            byte[] log = null;
            if (events < 0) {
                Stored known = stored.get(payloadId);
                if (known != null) {
                    events = known.events;
                } else {
                    try {
                        log = readFile(file);
                    } catch (TooLargeException e) {
                        // Never read, so never counted. Listed as holding one event, the fewest it can, so
                        // that the delivery reaching it drops it and counts the loss.
                        events = 1;
                    } catch (IOException e) {
                        // Left for a later listing rather than deleted: the read may pass, and the events in
                        // it may be intact. Without a count it cannot be delivered now.
                        logger.warn("Could not read stored events, will try again at the next delivery: {}",
                                LogValues.exceptionSummary(e));
                        continue;
                    }
                    if (log != null) {
                        events = EventLogFormat.eventCount(log);
                    } else if (events < 0) {
                        // Delivered and removed by another process since the listing.
                        continue;
                    }
                }
                if (events < 0) {
                    // Written by a version whose format this one does not read, or damaged beyond what the
                    // torn-tail recovery tolerates. Either way it can never be delivered, but it held events.
                    // Listed as holding one, the fewest it can, so that the delivery reaching it drops it and
                    // counts the loss, as for one too large to read.
                    events = 1;
                }
            }
            // What the close knew of its summaries is kept; nothing in the file says.
            Stored previous = stored.get(payloadId);
            int summaries = previous != null && previous.events == events ? previous.summaries : 0;
            // A batch just read is as big as what was read. length() is a stat of its own, which answers 0 where
            // it cannot look, and no batch is empty: it has a header at least.
            long bytes = log != null ? log.length : file.length();
            if (bytes <= 0) {
                bytes = previous != null ? previous.bytes : EventLogFormat.FILE_HEADER_SIZE;
            }
            stored.put(payloadId, new Stored(file, events, summaries, bytes, entry.modified));
            batches.add(new EventStore.Batch(payloadId, events));
        }

        // A batch that left the directory without going through remove() would otherwise keep its count
        // for the rest of the session.
        Set<String> listed = new HashSet<>();
        for (EventStore.Batch batch : batches) {
            listed.add(batch.payloadId);
        }
        stored.keySet().retainAll(listed);
        return new EventStore.Listing(batches, true);
    }

    /**
     * The batches the last listing or close counted, oldest first: every batch this store closed itself, but
     * none another process closed or a previous run left since. Requires {@code ioLock}.
     */
    private List<EventStore.Batch> knownBatchesOldestFirst() {
        List<Map.Entry<String, Stored>> known = new ArrayList<>(stored.entrySet());
        Collections.sort(known, (a, b) -> Long.compare(a.getValue().modified, b.getValue().modified));
        List<EventStore.Batch> batches = new ArrayList<>();
        for (Map.Entry<String, Stored> batch : known) {
            batches.add(new EventStore.Batch(batch.getKey(), batch.getValue().events));
        }
        return batches;
    }

    /** The summaries known to be in the batches the last listing or close counted. Requires {@code ioLock}. */
    int storedSummaries() {
        assert Thread.holdsLock(ioLock);
        int summaries = 0;
        for (Stored batch : stored.values()) {
            summaries += batch.summaries;
        }
        return summaries;
    }

    /** The bytes in every batch the last listing or close counted. Requires {@code ioLock}. */
    long storedBytes() {
        assert Thread.holdsLock(ioLock);
        long bytes = 0;
        for (Stored batch : stored.values()) {
            bytes += batch.bytes;
        }
        return bytes;
    }

    /**
     * What this process counts for a batch, or null if it counts nothing for it: one removed, or dropped by
     * a listing it was no longer in. Requires {@code ioLock}.
     */
    Tally counted(String payloadId) {
        assert Thread.holdsLock(ioLock);
        Stored known = stored.get(payloadId);
        return known == null ? null : known.tally();
    }

    /**
     * Whether a batch has already been delivered and only its file is left, trying once more on the way to
     * delete the file.
     * <p>
     * Requires {@code ioLock}.
     */
    private boolean isDeliveredButUndeleted(String payloadId, File file) {
        if (!deliveredButUndeleted.contains(payloadId)) {
            return false;
        }
        tryDelete(file);
        if (!file.exists()) {
            deliveredButUndeleted.remove(payloadId);
        }
        return true;
    }

    /**
     * Requires {@code ioLock}.
     *
     * @return the JSON payload for a batch, assembled in the array the file was read into; or null if it is
     *   gone or holds no events
     * @throws EventStore.UnreadableBatchException if the batch is there but holds nothing this version can
     *   send, which includes one larger than {@link #MAX_READ_SIZE}
     * @throws IOException if the batch is there but could not be read this time
     */
    EventLogFormat.Payload payload(String payloadId, int events) throws IOException {
        assert Thread.holdsLock(ioLock);
        byte[] log;
        try {
            log = readFile(batchFile(payloadId, events));
        } catch (TooLargeException e) {
            throw new EventStore.UnreadableBatchException(payloadId);
        }
        if (log == null) {
            return null;
        }
        // No more than the batch was closed holding. Writing that failed and could not be truncated
        // leaves whole frames after them, whose events are delivered from memory instead, and would
        // otherwise be sent twice.
        EventLogFormat.Payload payload =
                EventLogFormat.assemblePayloadInPlace(log, events > 0 ? events : Integer.MAX_VALUE);
        // A log this version reads that holds no events has nothing to lose, unless its first frame is
        // damaged. Where there is no payload the log is as it was read.
        if (payload == null && (EventLogFormat.eventCount(log) != 0 || EventLogFormat.isDamaged(log))) {
            throw new EventStore.UnreadableBatchException(payloadId);
        }
        return payload;
    }

    /**
     * Removes a batch. Requires {@code ioLock}.
     *
     * @return what it stopped counting, which is nothing for a batch that was already gone - one another
     *   process delivered, say, which a listing has stopped counting already
     */
    Tally remove(String payloadId, int events) {
        assert Thread.holdsLock(ioLock);
        File file = batchFile(payloadId, events);
        tryDelete(file);
        if (file.exists()) {
            // Its events stop counting all the same: LaunchDarkly has them, and holding capacity for a file this
            // process may never be allowed to delete would cost the session events it could still have recorded.
            logger.debug("Could not delete a delivered batch; it will not be sent again this session");
            deliveredButUndeleted.add(payloadId);
        }
        Stored removed = stored.remove(payloadId);
        return removed == null ? Tally.NONE : removed.tally();
    }

    /**
     * Every event in the directory, encoded exactly as it will be sent. Requires {@code ioLock}.
     * <p>
     * For tests and for diagnosing a store rather than for the recording path: it reads every file.
     */
    List<byte[]> storedPayloads() {
        assert Thread.holdsLock(ioLock);
        List<byte[]> logs = new ArrayList<>();
        List<ReadyFile> ready = readyFilesOldestFirst();
        for (ReadyFile entry : ready != null ? ready : Collections.<ReadyFile>emptyList()) {
            logs.add(readFileOrNull(entry.file));
        }
        if (diskLog().exists()) {
            logs.add(readFileOrNull(diskLog().file()));
            // Closing the descriptor that read it released this process's lock on it.
            diskLog().relockAfterReading();
        }

        List<byte[]> payloads = new ArrayList<>();
        for (byte[] log : logs) {
            payloads.addAll(EventLogFormat.eventPayloads(log));
        }
        return payloads;
    }

    /** A batch file, and when it was last written as of the listing that found it. */
    private static final class ReadyFile {
        final File file;
        final long modified;

        ReadyFile(File file) {
            this.file = file;
            this.modified = file.lastModified();
        }
    }

    /**
     * Requires {@code ioLock}.
     * <p>
     * Each file's modification time is read once, before the sort, so that the order cannot change under it.
     *
     * @return every batch file in the directory, oldest first; empty if the directory is not there, and null if
     *   it is there and could not be listed
     */
    private List<ReadyFile> readyFilesOldestFirst() {
        List<ReadyFile> ready = new ArrayList<>();
        File[] files = directory().listFiles();
        if (files == null) {
            // File says null for an I/O error as well as for a directory that is not there.
            return directory().exists() ? null : ready;
        }
        for (File file : files) {
            if (file.getName().startsWith(OWN_BATCH_PREFIX)) {
                ready.add(new ReadyFile(file));
            }
        }
        Collections.sort(ready, (a, b) -> Long.compare(a.modified, b.modified));
        return ready;
    }

    /**
     * @return the file's bytes, or null if it no longer exists
     * @throws TooLargeException if it is larger than {@link #MAX_READ_SIZE}, which will not pass
     * @throws IOException if it exists but could not be read. That may pass, and the file is intact:
     *   a process out of file descriptors fails to open it with the same exception a missing file
     *   gets, which is why existence is checked rather than the exception's type.
     */
    private byte[] readFile(File file) throws IOException {
        try (FileInputStream in = reader.openForReading(file)) {
            return readAll(in);
        } catch (IOException e) {
            if (!file.exists()) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Reads into an array of the file's size, so a batch is held once rather than in a growing buffer and a
     * copy of it. The size is the open descriptor's, not File.length(): that is a stat of its own, which
     * answers 0 rather than failing, and an intact batch read as empty is removed as delivered by another
     * process, its events lost without being counted.
     */
    private static byte[] readAll(FileInputStream in) throws IOException {
        long size = in.getChannel().size();
        if (size > MAX_READ_SIZE) {
            throw new TooLargeException(size);
        }
        byte[] contents;
        try {
            contents = new byte[(int) size];
        } catch (OutOfMemoryError e) {
            // Memory is short for now, which may pass, and the file is intact: tried again by a later pass
            // rather than deleted, and rather than ending this one.
            throw new IOException("not enough memory to read " + size + " bytes", e);
        }
        int filled = 0;
        while (filled < contents.length) {
            int read = in.read(contents, filled, contents.length - filled);
            if (read < 0) {
                break;
            }
            filled += read;
        }
        return filled == contents.length ? contents : Arrays.copyOf(contents, filled);
    }

    /** For inspecting the store, where a file that cannot be read is simply left out. */
    private byte[] readFileOrNull(File file) {
        try {
            return readFile(file);
        } catch (IOException e) {
            return null;
        }
    }

    private void tryDelete(File file) {
        if (file.exists() && !file.delete()) {
            logger.debug("Could not delete {}", file);
        }
    }

    // ---- recovery ----

    /**
     * Every log in the directory that no live process holds, for recovery to read with {@link #read} and
     * close off with {@link #preparePayload}, so their events join the batches to deliver.
     * <p>
     * Whoever recorded the events in such a log is gone, so there is no one left to add to it. That may be a
     * previous run of this process, another process of the application, or a store in this one that has since
     * closed. A log some live process holds, this store's own included, is left alone. None of those listed
     * becomes held later: a log is opened under a name of its own and never reopened once closed.
     * <p>
     * Requires {@code ioLock}.
     */
    List<File> interruptedLogs() {
        assert Thread.holdsLock(ioLock);
        List<File> logs = new ArrayList<>();
        File[] files = directory().listFiles();
        if (files == null) {
            return logs;
        }
        for (File file : files) {
            if (file.getName().startsWith(LOG_PREFIX) && !DiskEventLog.isHeldByLiveProcess(file)) {
                logs.add(file);
            }
        }
        return logs;
    }

    /** What {@link #read} found in a log a previous run left. */
    static final class InterruptedLog {
        final File file;
        /** Null where it could be read: it is gone, or {@link #events} and {@link #bytes} say what it holds. */
        final IOException failure;
        final boolean gone;
        /** As {@link EventLogFormat#eventCount}. */
        final int events;
        /**
         * Whether it holds something this version cannot read back, which is lost: frames after {@link #events}
         * that are torn or fail their checksum, or the whole log, in a format this version does not read.
         */
        final boolean damaged;
        final long bytes;

        private InterruptedLog(File file, IOException failure, boolean gone, int events, boolean damaged,
                               long bytes) {
            this.file = file;
            this.failure = failure;
            this.gone = gone;
            this.events = events;
            this.damaged = damaged;
            this.bytes = bytes;
        }
    }

    /** What {@link #preparePayload} did with a log a previous run left. */
    static final class Recovery {
        static final Recovery NONE = new Recovery(Tally.NONE, 0);

        /** What it closed off into a batch to deliver. */
        final Tally prepared;
        /**
         * How many events it found lost to damage. One for a damaged log: nothing in it says how many the damage
         * took, and it took at least one.
         */
        final int lost;

        Recovery(Tally prepared, int lost) {
            this.prepared = prepared;
            this.lost = lost;
        }
    }

    /**
     * Reads and counts one of the {@link #interruptedLogs}. Needs no lock: no one appends to the log, and nothing
     * this store counts changes here, so a commit is not held up behind the read.
     */
    InterruptedLog read(File log) {
        try {
            byte[] contents = readFile(log);
            if (contents == null) {
                return new InterruptedLog(log, null, true, 0, false, 0);
            }
            int events = EventLogFormat.eventCount(contents);
            // Shorter than a header, it is a log whose first write never reached the disk, so nothing was durable
            // in it to lose.
            boolean damaged = events < 0
                    ? contents.length >= EventLogFormat.FILE_HEADER_SIZE
                    : EventLogFormat.isDamaged(contents);
            return new InterruptedLog(log, null, false, events, damaged, contents.length);
        } catch (TooLargeException e) {
            // Not this version's, so deleted and counted lost as a log in a format it does not read is.
            return new InterruptedLog(log, null, false, -1, true, 0);
        } catch (IOException e) {
            return new InterruptedLog(log, e, false, 0, false, 0);
        }
    }

    /**
     * Closes off a log {@link #read} has been through into a batch to deliver. Requires {@code ioLock}, so that
     * the rename and what this store counts for the batch are one step to a listing, which would otherwise
     * count it twice.
     *
     * @return what it closed off, and what it found lost
     */
    Recovery preparePayload(InterruptedLog interrupted) {
        assert Thread.holdsLock(ioLock);
        File log = interrupted.file;
        if (interrupted.gone || !log.exists()) {
            // Recovered by another store or process since it was listed.
            return Recovery.NONE;
        }
        if (interrupted.failure != null) {
            // Deleting it would lose events that may be intact. Named without a count, which is what a batch
            // whose events have never been counted is, it is read and counted by whichever listing first
            // manages to.
            if (log.renameTo(newUncountedBatchFile())) {
                logger.warn("Could not read events from a previous run, will try again at the next delivery: {}",
                        LogValues.exceptionSummary(interrupted.failure));
            } else if (log.exists()) {
                // Left where it is, unlocked and appended to by no one, for the next launch to recover.
                logger.warn("Could not read events from a previous run, will try again at the next launch: {}",
                        LogValues.exceptionSummary(interrupted.failure));
            }
            return Recovery.NONE;
        }
        int events = interrupted.events;
        if (events <= 0) {
            tryDelete(log);
            if (!interrupted.damaged) {
                // Holds nothing.
                return Recovery.NONE;
            }
            logger.error("Dropping an event log from a previous run that is damaged, or in a format this version"
                    + " does not read; the events in it are lost");
            return new Recovery(Tally.NONE, 1);
        }
        String payloadId = UUID.randomUUID().toString();
        File file = newBatchFile(payloadId, events);
        if (!log.renameTo(file)) {
            if (log.exists()) {
                // Left where it is for a later run: no one appends to it, so nothing is lost by waiting.
                logger.warn("Could not prepare {} event(s) from a previous run, will try again at the next launch",
                        events);
            }
            return Recovery.NONE;
        }
        Stored recovered = new Stored(file, events, 0, interrupted.bytes, file.lastModified());
        stored.put(payloadId, recovered);
        logger.debug("Prepared {} event(s) from a previous run of this application for delivery", events);
        if (!interrupted.damaged) {
            return new Recovery(recovered.tally(), 0);
        }
        // The batch is named for the frames that read back, so the damage after them is never sent.
        logger.error("Dropped the damaged event(s) after the first {} in a log from a previous run", events);
        return new Recovery(recovered.tally(), 1);
    }

    // ---- expiry ----

    /**
     * Deletes the batches in this directory last written before {@code cutoffMillis}, and the logs no live
     * process holds that have not been written since then either. Requires {@code ioLock}.
     * <p>
     * A batch's modification time is when its newest event was written, since a batch is renamed rather than
     * rewritten when it is closed, so a batch is discarded only once everything in it is that old. A log is
     * usually recovered rather than left to grow old, so one that gets here is one recovery could not move.
     * A batch another version named is deleted too, without being read or counted.
     *
     * A batch whose request is out is left alone however old it is, whichever store or process sent it; see
     * {@link BatchLocks}.
     *
     * @param sending batches whose request is out from this store
     */
    Discarded discard(long cutoffMillis, Set<String> sending) {
        assert Thread.holdsLock(ioLock);
        File[] files = directory().listFiles();
        int events = 0;
        Tally counted = Tally.NONE;
        if (files == null) {
            return new Discarded(0, counted);
        }
        for (File file : files) {
            // Expiry comes first in every delivery, so a file that fails it is left for a later pass rather than
            // ending this one, and every one after it.
            try {
                if (isWrittenSince(file, cutoffMillis)) {
                    continue;
                }
                String name = file.getName();
                if (isAnotherVersionsBatch(name)) {
                    // Never counted, so nothing to stop counting, and its events were never this version's to lose.
                    BatchLocks.deleteUnlessBeingSent(file);
                } else if (name.startsWith(OWN_BATCH_PREFIX)) {
                    int named = eventsInName(name);
                    String payloadId = payloadIdInName(name);
                    if (sending.contains(payloadId) || deliveredButUndeleted.contains(payloadId)
                            || BatchLocks.isSentInThisProcess(file)) {
                        continue;
                    }
                    Stored known = stored.get(payloadId);
                    int held = named >= 0 ? named : known != null ? known.events : eventsReading(file);
                    if (held < 0) {
                        continue;
                    }
                    if (!BatchLocks.deleteUnlessBeingSent(file)) {
                        if (!file.exists()) {
                            // Delivered by another process since the listing: no longer counted, and not dropped.
                            Stored removed = stored.remove(payloadId);
                            if (removed != null) {
                                counted = counted.plus(removed.tally());
                            }
                        }
                        continue;
                    }
                    events += held;
                    Stored removed = stored.remove(payloadId);
                    if (removed != null) {
                        counted = counted.plus(removed.tally());
                    }
                } else {
                    events += discardIfAbandonedLog(file);
                }
            } catch (RuntimeException e) {
                logger.debug("Could not discard expired events, will try again at the next delivery: {}",
                        LogValues.exceptionSummary(e));
            }
        }
        return new Discarded(events, counted);
    }

    /**
     * Empties and removes the directories of other environments that nothing has written to since
     * {@code cutoffMillis}: those of a mobile key the application no longer uses, which no client will look
     * in again.
     * <p>
     * Age decides rather than the keys the application configures, because another process of the same
     * application may be using a key this one does not configure. Nothing is touched here that this store
     * reads or writes, so it needs no lock. A directory is removed only if it is empty, so a log another
     * process starts in it meanwhile keeps the directory and itself.
     * <p>
     * Only where {@code current} is where the SDK puts environments, so that a store handed some other
     * directory never goes through that directory's siblings. The directory of a store open in this process
     * is left alone, and so is a batch some live process is sending; see {@link BatchLocks}.
     *
     * @return how many events were discarded
     */
    static int discardAbandonedEnvironments(File current, long cutoffMillis) {
        File root = current.getParentFile();
        if (root == null || !DIRECTORY_NAME.equals(root.getName())) {
            return 0;
        }
        File[] environments = root.listFiles();
        if (environments == null) {
            return 0;
        }
        int events = 0;
        for (File environment : environments) {
            if (environment.equals(current) || !environment.isDirectory() || isOpenInThisProcess(environment)) {
                continue;
            }
            File[] files = environment.listFiles();
            if (files != null) {
                for (File file : files) {
                    // As in discard(): a file that fails is left for a later pass.
                    try {
                        if (isWrittenSince(file, cutoffMillis)) {
                            continue;
                        }
                        String name = file.getName();
                        if (isAnotherVersionsBatch(name)) {
                            BatchLocks.deleteUnlessBeingSent(file);
                        } else if (name.startsWith(OWN_BATCH_PREFIX)) {
                            if (BatchLocks.isSentInThisProcess(file)) {
                                continue;
                            }
                            int named = eventsInName(name);
                            int held = named >= 0 ? named : eventsReading(file);
                            if (held >= 0 && BatchLocks.deleteUnlessBeingSent(file)) {
                                events += held;
                            }
                        } else {
                            events += discardIfAbandonedLog(file);
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            // Removes the directory only if it is empty.
            delete(environment);
        }
        return events;
    }

    /**
     * A file whose modification time is unknown counts as written since, so it is never taken for an old one.
     */
    private static boolean isWrittenSince(File file, long cutoffMillis) {
        long modified = file.lastModified();
        return modified <= 0 || modified >= cutoffMillis;
    }

    /**
     * Deletes a log no live process holds, or one a process died opening, which holds no events.
     *
     * @return how many events went with it
     */
    private static int discardIfAbandonedLog(File file) {
        String name = file.getName();
        if (name.startsWith(OPENING_LOG_PREFIX)) {
            delete(file);
            return 0;
        }
        if (!name.startsWith(LOG_PREFIX) || DiskEventLog.isHeldByLiveProcess(file)) {
            return 0;
        }
        int held = eventsReading(file);
        return held >= 0 && delete(file) ? held : 0;
    }

    /**
     * @return how many events a file being discarded holds, or -1 if it could not be read, in which case it is
     *   left for a later pass: deleted now, its events would go uncounted. One larger than
     *   {@link #MAX_READ_SIZE} counts as one, the fewest it can hold: it will never be read, and left, it would
     *   be tried by every pass.
     */
    // Files.newInputStream needs API 26.
    @SuppressWarnings("IOStreamConstructor")
    private static int eventsReading(File file) {
        byte[] contents;
        try (FileInputStream in = new FileInputStream(file)) {
            contents = readAll(in);
        } catch (TooLargeException e) {
            return 1;
        } catch (IOException e) {
            return -1;
        }
        return Math.max(0, EventLogFormat.eventCount(contents));
    }

    private static boolean delete(File file) {
        return file.delete() || !file.exists();
    }

    // ---- naming ----

    /** The directory the store for one environment belongs in. */
    static File defaultDirectory(File noBackupFilesDir, String mobileKey) {
        return new File(new File(noBackupFilesDir, DIRECTORY_NAME), environmentDirectoryName(mobileKey));
    }

    /**
     * Names the directory for one environment, hashed so the mobile key itself is never a filename.
     * Not {@link LDUtil#urlSafeBase64Hash(String)}, for the reasons {@link LDUtil#hexDigest} gives.
     */
    private static String environmentDirectoryName(String mobileKey) {
        // Half a digest, which is far more than enough to keep two mobile keys apart.
        return LDUtil.hexDigest(mobileKey, 16);
    }
}
