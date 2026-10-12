package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
 * one: the platform creates the no-backup directory the first time it is asked for it, and before API 28 the
 * process name is read from {@code /proc}.
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

        String processName();
    }

    /** How a stored file is read, so a test can make a read fail while the file is intact. */
    interface Reader {
        InputStream openForReading(File file) throws IOException;
    }

    private final Location location;
    private final Object ioLock;
    private final LDLogger logger;
    private final Reader reader;
    private final DiskEventLog.Opener opener;

    // Guarded by ioLock, and null until locateHoldingIoLock has run.
    private File directory;
    private DiskEventLog diskLog;

    /** Whether a log left by a previous run has been dealt with. Guarded by ioLock. */
    private boolean recoveryDone;

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

    /** A batch in the directory: the file it is, and how many events it holds. */
    private static final class Stored {
        final File file;
        final int events;

        Stored(File file, int events) {
            this.file = file;
            this.events = events;
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
            diskLog = new DiskEventLog(resolved, location.processName(), ioLock, opener, logger);
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
     *   last group is twelve characters, and twelve digits are more than an {@code int} holds.
     */
    private static int eventsInName(String fileName) {
        int split = fileName.lastIndexOf('-');
        if (split < BATCH_PREFIX.length()) {
            return -1;
        }
        String suffix = fileName.substring(split + 1);
        if (suffix.isEmpty()) {
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

    /** What an append did. */
    static final class Append {
        /** How many events a recovery closed off into batches of their own on the way. */
        final int recovered;
        /**
         * Whether every byte landed. False means none of them is in the log, and that the caller should
         * give up on the disk.
         */
        final boolean written;

        Append(int recovered, boolean written) {
            this.recovered = recovered;
            this.written = written;
        }
    }

    /**
     * Appends to the open log, first closing off one a previous run left behind.
     * <p>
     * Requires {@code ioLock}.
     */
    Append append(byte[] bytes) {
        assert Thread.holdsLock(ioLock);
        // Recovered before the log is opened, because it may still be the one a previous run died writing.
        // A commit on the caller's thread can get here before the processor's queued startup task has
        // recovered it, and appended to, this run's events would sit behind that run's torn last frame.
        int recovered = diskLog().isOpen() ? 0 : recoverInterruptedLog();
        try {
            diskLog().append(bytes);
            return new Append(recovered, true);
        } catch (IOException | RuntimeException e) {
            // Never let a full disk take the application down. The SDK gives up on persistence for the
            // rest of the session instead, which is how other SDKs have crashed their hosts.
            logger.warn("Giving up on persisting events: {}", LogValues.exceptionSummary(e));
            return new Append(recovered, false);
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
     */
    LogClose closeLog(int events) {
        assert Thread.holdsLock(ioLock);
        String payloadId = UUID.randomUUID().toString();
        File file = newBatchFile(payloadId, events);
        if (!diskLog().closeMovingTo(file)) {
            logger.warn("Could not close the event log for delivery");
            return new LogClose(null, diskLog().exists());
        }

        stored.put(payloadId, new Stored(file, events));
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
            stored.put(payloadId, new Stored(file, events));
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
     * @return the JSON request body for a batch, or null if it is gone or holds nothing this version can
     *   send, neither of which a later attempt changes
     * @throws IOException if the batch is there but could not be read this time
     */
    byte[] body(String payloadId, int events) throws IOException {
        assert Thread.holdsLock(ioLock);
        return EventLogFormat.assembleBody(readFile(batchFile(payloadId, events)));
    }

    /**
     * Forgets a batch. Requires {@code ioLock}.
     *
     * @return the events it stopped counting, which is zero for a batch that was already gone - one another
     *   process delivered, say, which a listing has stopped counting already
     */
    int remove(String payloadId, int events) {
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
        return removed == null ? 0 : removed.events;
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
     * Closes off any log left open by a previous run of this process, so its events join the batches to
     * deliver.
     * <p>
     * The events in it were recorded by a process that is gone, so there is no one left to add to it. Only
     * this process's own log is touched: another process's may still be open in a process that is alive.
     * <p>
     * Requires {@code ioLock}. Does its work at most once, and always before this process first opens its
     * log: after that, the log under this name holds this run's events, and closing it as a previous run's
     * would send them early and report them as recovered.
     *
     * @return how many events it closed off, which is zero on every call after the first
     */
    int recoverInterruptedLog() {
        assert Thread.holdsLock(ioLock);
        if (recoveryDone) {
            return 0;
        }
        recoveryDone = true;

        File log = diskLog().file();
        if (!log.exists()) {
            return 0;
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
            // Named without a count, which is what a batch whose events have never been counted is: the
            // listing that first manages to read it is what counts it.
            log.renameTo(new File(directory(), BATCH_PREFIX + UUID.randomUUID()));
            return 0;
        }
        if (events < 0) {
            // Unreadable, and a log that cannot be read cannot be appended to either.
            deleteQuietly(log);
            return 0;
        }
        if (events == 0) {
            deleteQuietly(log);
            return 0;
        }
        String payloadId = UUID.randomUUID().toString();
        File file = newBatchFile(payloadId, events);
        if (!log.renameTo(file)) {
            return 0;
        }
        stored.put(payloadId, new Stored(file, events));
        logger.info("Recovered {} event(s) that a previous run of this application did not deliver",
                events);
        return events;
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
