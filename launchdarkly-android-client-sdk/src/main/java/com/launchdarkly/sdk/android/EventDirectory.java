package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The directory one store keeps its events in: the log this process appends to, and beside it the batches
 * that have been closed off for delivery.
 * <p>
 * Everything about the filesystem lives here - where the directory is, what the files in it are called, and
 * how they are listed, read, moved and deleted - so that {@link EventStore} is left with the buffer, the
 * capacity arithmetic, and the batches it holds in memory, none of which need a disk at all. A store the
 * application told to keep events off the disk has no {@code EventDirectory}, rather than one it is careful
 * never to use.
 * <p>
 * Where the directory is, is worked out on first use rather than at construction, so that a store which never
 * reaches for the disk makes no filesystem call at all, not even to find out where it would have written.
 * That matters because the store is built on the thread that called {@code LDClient.init}, usually the main
 * one: the platform creates the no-backup directory the first time it is asked for it.
 * <p>
 * Belongs to an {@link EventStore}, which makes every call holding its {@code ioLock}, exactly as
 * {@link DiskEventLog} belongs to this. The lock is handed down so that each call can check it is held,
 * rather than each level taking a lock of its own that would have to be ordered against the others.
 */
class EventDirectory {
    /**
     * Names a log closed off for delivery. The rest of the name is the batch's payload ID and how many
     * events are in it, {@code ready-<payloadId>-<count>}.
     * <p>
     * The count is in the name so that listing a batch this process did not close costs a directory entry
     * rather than a read of the whole file. That is what every batch is at startup: the ones a previous run
     * left behind, and the ones belonging to another process sharing the environment. Reading them all is
     * done holding {@code ioLock}, which a commit at a commit point then waits behind.
     * <p>
     * A batch is never appended to once it is closed, so the count is as good as the file is.
     */
    private static final String BATCH_PREFIX = "ready-";
    private static final String DIRECTORY_NAME = "com.launchdarkly.events";

    /** Where one store keeps its files. */
    interface Location {
        File directory();
    }

    /** How a stored file is read, so a test can make a read fail while the file is intact. */
    interface Reader {
        InputStream openForReading(File file) throws IOException;
    }

    /** How many events, and how many bytes, something stored holds: the two things capacity counts. */
    static final class Tally {
        static final Tally NONE = new Tally(0, 0);

        final int events;
        final long bytes;

        Tally(int events, long bytes) {
            this.events = events;
            this.bytes = bytes;
        }

        Tally plus(Tally other) {
            return new Tally(events + other.events, bytes + other.bytes);
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

    /**
     * What this process knows about each batch in the directory, by payload ID, which is how everything
     * outside this class names one.
     * <p>
     * The file is remembered rather than composed from the payload ID, because the count is part of the name
     * and a batch closed by a version that did not write one has no count to compose. The count is
     * remembered so that such a batch is read at most once.
     * <p>
     * Only to be used while holding {@code ioLock}.
     */
    private final Map<String, Stored> stored = new HashMap<>();

    /** A batch in the directory: the file it is, how many events it holds, and how big it is. */
    private static final class Stored {
        final File file;
        final int events;
        final long bytes;

        Stored(File file, int events, long bytes) {
            this.file = file;
            this.events = events;
            this.bytes = bytes;
        }

        Tally tally() {
            return new Tally(events, bytes);
        }
    }

    /**
     * Batches LaunchDarkly has accepted whose file the filesystem would not let go of.
     * <p>
     * Left out of every listing, because a batch that is delivered and merely still on the disk would otherwise
     * be found again by the next delivery, and the one after that, and sent each time. The delete is tried once
     * more on each listing, so a directory that stops being read-only clears itself.
     * <p>
     * Only for this session: a later run, which may well be able to delete the file, treats it as it would any
     * other batch a previous run left behind. LaunchDarkly discards a repeated payload ID, so the cost of that is
     * a wasted request rather than events counted twice.
     * <p>
     * Only to be used while holding {@code ioLock}.
     */
    private final Set<String> deliveredButUndeleted = new HashSet<>();

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
        return new File(directory(), BATCH_PREFIX + payloadId + "-" + events);
    }

    /**
     * A batch as its filename gives it: everything between the prefix and the count is the payload ID.
     *
     * @return the count the name ends with, or -1 where it has none, which is how a version that did not
     *   write one named its batches. A payload ID cannot be mistaken for a count: it is a UUID, whose
     *   last group is twelve characters. Twelve digits are more than an {@code int} holds unless they
     *   start with a zero, which a count never does.
     */
    private static int eventsInName(String fileName) {
        int split = fileName.lastIndexOf('-');
        if (split < BATCH_PREFIX.length()) {
            return -1;
        }
        String suffix = fileName.substring(split + 1);
        if (suffix.isEmpty() || (suffix.length() > 1 && suffix.charAt(0) == '0')) {
            return -1;
        }
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The payload ID a filename carries, given what {@link #eventsInName} made of it. */
    private static String payloadIdInName(String fileName, int events) {
        return events < 0
                ? fileName.substring(BATCH_PREFIX.length())
                : fileName.substring(BATCH_PREFIX.length(), fileName.lastIndexOf('-'));
    }

    // MARK: writing

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
         * Where the close failed, whether the log is still there to try again with. Where it is not - a
         * directory the platform reclaimed, say - its events are gone, and they have to stop counting
         * against capacity or the store would refuse events for the rest of the session.
         */
        final boolean logSurvives;

        private LogClose(EventStore.Batch batch, boolean logSurvives) {
            this.batch = batch;
            this.logSurvives = logSurvives;
        }
    }

    /**
     * Closes the open log into a batch, so the next append starts a new one.
     * <p>
     * Requires {@code ioLock}.
     *
     * @param events how many events the log holds, which the caller has counted as it staged them
     * @param bytes how big the log is, which the caller has counted as it wrote it
     */
    LogClose closeLog(int events, long bytes) {
        assert Thread.holdsLock(ioLock);
        String payloadId = UUID.randomUUID().toString();
        File file = newBatchFile(payloadId, events);
        if (!diskLog().closeMovingTo(file)) {
            logger.warn("Could not close the event log for delivery");
            return new LogClose(null, diskLog().exists());
        }

        stored.put(payloadId, new Stored(file, events, bytes));
        return new LogClose(new EventStore.Batch(payloadId, events), true);
    }

    /** Closes the descriptor, leaving everything where it is. Requires {@code ioLock}. */
    void closeOpenLog() {
        assert Thread.holdsLock(ioLock);
        // Not diskLog(): a store that never wrote has never asked the platform where it lives, and shutting
        // down is no reason to start.
        if (diskLog != null) {
            diskLog.closeOutput();
        }
    }

    // MARK: listing and reading

    /**
     * Requires {@code ioLock}.
     * <p>
     * Sizes come from the directory entries, so that counting the directory's bytes reads no file either.
     *
     * @return the batches the directory holds, oldest first, which is the order they are delivered in
     */
    List<EventStore.Batch> listBatches() {
        assert Thread.holdsLock(ioLock);
        List<EventStore.Batch> batches = new ArrayList<>();
        for (File file : readyFilesOldestFirst()) {
            int events = eventsInName(file.getName());
            String payloadId = payloadIdInName(file.getName(), events);
            if (isDeliveredButUndeleted(payloadId, file)) {
                continue;
            }
            // Reading is only for a batch whose name does not count it, which a version that named them
            // without a count may have left behind, and then only the once.
            if (events < 0) {
                Stored known = stored.get(payloadId);
                if (known != null) {
                    events = known.events;
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
            }
            stored.put(payloadId, new Stored(file, events, file.length()));
            batches.add(new EventStore.Batch(payloadId, events));
        }

        // A batch that left the directory without going through remove() would otherwise keep its count
        // for the rest of the session.
        Set<String> listed = new HashSet<>();
        for (EventStore.Batch batch : batches) {
            listed.add(batch.payloadId);
        }
        stored.keySet().retainAll(listed);
        return batches;
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

    /** The bytes in a batch, as far as this process has counted them. Requires {@code ioLock}. */
    long bytes(String payloadId) {
        assert Thread.holdsLock(ioLock);
        Stored known = stored.get(payloadId);
        return known == null ? 0 : known.bytes;
    }

    /**
     * Whether a batch has already been delivered and only its file is left, trying the delete that failed once
     * more on the way.
     * <p>
     * Requires {@code ioLock}.
     */
    private boolean isDeliveredButUndeleted(String payloadId, File file) {
        if (!deliveredButUndeleted.contains(payloadId)) {
            return false;
        }
        deleteQuietly(file);
        if (!file.exists()) {
            deliveredButUndeleted.remove(payloadId);
        }
        return true;
    }

    /**
     * Requires {@code ioLock}.
     *
     * @return the JSON request body for a batch, or null if it is gone or holds no events
     * @throws EventStore.UnreadableBatchException if the batch is there but holds nothing this version can
     *   send
     * @throws IOException if the batch is there but could not be read this time
     */
    byte[] body(String payloadId, int events) throws IOException {
        assert Thread.holdsLock(ioLock);
        byte[] log = readFile(batchFile(payloadId, events));
        if (log == null) {
            return null;
        }
        byte[] body = EventLogFormat.assembleBody(log);
        // A log this version reads that holds no events has nothing to lose, unless its first frame is
        // damaged.
        if (body == null && (EventLogFormat.eventCount(log) != 0 || EventLogFormat.isDamaged(log))) {
            throw new EventStore.UnreadableBatchException(payloadId);
        }
        return body;
    }

    /**
     * Forgets a batch. Requires {@code ioLock}.
     *
     * @return what it stopped counting, which is nothing for a batch that was already gone - one another
     *   process delivered, say, which a listing has stopped counting already
     */
    Tally remove(String payloadId, int events) {
        assert Thread.holdsLock(ioLock);
        File file = batchFile(payloadId, events);
        deleteQuietly(file);
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
        for (File file : readyFilesOldestFirst()) {
            logs.add(readFileOrNull(file));
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

    /**
     * Requires {@code ioLock}.
     *
     * @return every batch file in the directory, oldest first
     */
    private List<File> readyFilesOldestFirst() {
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

    /**
     * @return the file's bytes, or null if it no longer exists
     * @throws IOException if it exists but could not be read. That may pass, and the file is intact:
     *   a process out of file descriptors fails to open it with the same exception a missing file
     *   gets, which is why existence is checked rather than the exception's type.
     */
    private byte[] readFile(File file) throws IOException {
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (InputStream in = reader.openForReading(file)) {
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

    private void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            logger.debug("Could not delete {}", file);
        }
    }

    // MARK: recovery

    /**
     * Closes off every log in the directory that no live process holds, so their events join the batches to
     * deliver.
     * <p>
     * Whoever recorded the events in such a log is gone, so there is no one left to add to it: a previous run
     * of this process, another process of the application, or a store in this one that has since closed. A
     * log some live process holds, this store's own included, is left alone.
     * <p>
     * Requires {@code ioLock}.
     *
     * @return what it closed off
     */
    Tally recoverInterruptedLog() {
        assert Thread.holdsLock(ioLock);
        File[] files = directory().listFiles();
        if (files == null) {
            return Tally.NONE;
        }
        Tally recovered = Tally.NONE;
        for (File file : files) {
            if (file.getName().startsWith(DiskEventLog.PREFIX) && !DiskEventLog.isHeldByLiveProcess(file)) {
                recovered = recovered.plus(recoverLog(file));
            }
        }
        if (recovered.events > 0) {
            logger.info("Recovered {} event(s) that a previous run of this application did not deliver",
                    recovered.events);
        }
        return recovered;
    }

    /** Closes off one log no live process holds. Requires {@code ioLock}. */
    private Tally recoverLog(File log) {
        int events;
        long bytes;
        try {
            byte[] contents = readFile(log);
            if (contents == null) {
                // Recovered by another process since the listing.
                return Tally.NONE;
            }
            events = EventLogFormat.eventCount(contents);
            bytes = contents.length;
        } catch (IOException e) {
            // Deleting it would lose events that may be intact. Named without a count, which is what a batch
            // whose events have never been counted is, it is read and counted by whichever listing first
            // manages to.
            logger.warn("Could not read events from a previous run, will try again later: {}",
                    LogValues.exceptionSummary(e));
            log.renameTo(new File(directory(), BATCH_PREFIX + UUID.randomUUID()));
            return Tally.NONE;
        }
        if (events <= 0) {
            // Holds nothing, or nothing this version reads.
            deleteQuietly(log);
            return Tally.NONE;
        }
        String payloadId = UUID.randomUUID().toString();
        File file = newBatchFile(payloadId, events);
        if (!log.renameTo(file)) {
            // Left where it is for a later run: no one appends to it, so nothing is lost by waiting.
            return Tally.NONE;
        }
        Stored recovered = new Stored(file, events, bytes);
        stored.put(payloadId, recovered);
        return recovered.tally();
    }

    // MARK: expiry

    /**
     * Deletes the batches in this directory last written before {@code cutoffMillis}, and the logs no live
     * process holds that have not been written since then either. Requires {@code ioLock}.
     * <p>
     * A batch's modification time is when its newest event was written, since a batch is renamed rather than
     * rewritten when it is closed, so a batch is discarded only once everything in it is that old. A log is
     * usually recovered rather than left to grow old, so one that gets here is one recovery could not move.
     *
     * @param sending batches whose request is out, which are left alone however old they are
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
            if (!isOlderThan(file, cutoffMillis)) {
                continue;
            }
            String name = file.getName();
            if (name.startsWith(BATCH_PREFIX)) {
                int named = eventsInName(name);
                String payloadId = payloadIdInName(name, named);
                if (sending.contains(payloadId) || deliveredButUndeleted.contains(payloadId)) {
                    continue;
                }
                Stored known = stored.get(payloadId);
                int held = named >= 0 ? named : known != null ? known.events : eventsReading(file);
                if (!delete(file)) {
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
     * directory never goes through that directory's siblings.
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
            if (environment.equals(current) || !environment.isDirectory()) {
                continue;
            }
            File[] files = environment.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!isOlderThan(file, cutoffMillis)) {
                        continue;
                    }
                    String name = file.getName();
                    if (name.startsWith(BATCH_PREFIX)) {
                        int named = eventsInName(name);
                        int held = named >= 0 ? named : eventsReading(file);
                        if (delete(file)) {
                            events += held;
                        }
                    } else {
                        events += discardIfAbandonedLog(file);
                    }
                }
            }
            // Removes the directory only if it is empty.
            environment.delete();
        }
        return events;
    }

    /** A file whose modification time is unknown is never taken for an old one. */
    private static boolean isOlderThan(File file, long cutoffMillis) {
        long modified = file.lastModified();
        return modified > 0 && modified < cutoffMillis;
    }

    /**
     * Deletes a log no live process holds, or one a process died opening, which holds no events.
     *
     * @return how many events went with it
     */
    private static int discardIfAbandonedLog(File file) {
        String name = file.getName();
        if (name.startsWith(DiskEventLog.OPENING_PREFIX)) {
            delete(file);
            return 0;
        }
        if (!name.startsWith(DiskEventLog.PREFIX) || DiskEventLog.isHeldByLiveProcess(file)) {
            return 0;
        }
        int held = eventsReading(file);
        return delete(file) ? held : 0;
    }

    private static int eventsReading(File file) {
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(chunk)) > 0) {
                contents.write(chunk, 0, read);
            }
        } catch (IOException e) {
            return 0;
        }
        return Math.max(0, EventLogFormat.eventCount(contents.toByteArray()));
    }

    private static boolean delete(File file) {
        return file.delete() || !file.exists();
    }

    // MARK: naming

    /** The directory the store for one environment belongs in. */
    static File defaultDirectory(File noBackupFilesDir, String mobileKey) {
        return new File(new File(noBackupFilesDir, DIRECTORY_NAME), environmentDirectoryName(mobileKey));
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
}
