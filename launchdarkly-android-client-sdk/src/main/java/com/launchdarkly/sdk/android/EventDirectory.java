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
    /** Names a log closed off for delivery; the rest of the name is the batch's payload ID. */
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
     * How many events are in each batch this process closed or recovered, so that listing them does not have
     * to read them back.
     * <p>
     * A batch is never appended to once it is closed, so a count taken at the close holds until the batch is
     * delivered. Without this, listing reads every batch file in full while holding {@code ioLock}, and a
     * commit at a commit point can end up waiting behind those reads.
     * <p>
     * Only to be used while holding {@code ioLock}.
     */
    private final Map<String, Integer> eventCounts = new HashMap<>();

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
     */
    private File batchFile(String payloadId) {
        return new File(directory(), BATCH_PREFIX + payloadId);
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
        if (!diskLog().closeMovingTo(batchFile(payloadId))) {
            logger.warn("Could not close the event log for delivery");
            return new LogClose(null, diskLog().exists());
        }

        eventCounts.put(payloadId, events);
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
            String payloadId = file.getName().substring(BATCH_PREFIX.length());
            // Reading is only for a batch this process has not counted: one a previous run left behind, or
            // one belonging to another process sharing the environment.
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
            batches.add(new EventStore.Batch(payloadId, events));
        }

        // A batch that left the directory without going through remove() would otherwise keep its count
        // for the rest of the session.
        Set<String> listed = new HashSet<>();
        for (EventStore.Batch batch : batches) {
            listed.add(batch.payloadId);
        }
        eventCounts.keySet().retainAll(listed);
        return batches;
    }

    /**
     * Requires {@code ioLock}.
     *
     * @return the JSON request body for a batch, or null if it is gone or holds nothing this version can
     *   send, neither of which a later attempt changes
     * @throws IOException if the batch is there but could not be read this time
     */
    byte[] body(String payloadId) throws IOException {
        assert Thread.holdsLock(ioLock);
        return EventLogFormat.assembleBody(readFile(batchFile(payloadId)));
    }

    /**
     * Forgets a batch. Requires {@code ioLock}.
     *
     * @return the events it stopped counting, which is zero for a batch that was already gone - one another
     *   process delivered, say, which a listing has stopped counting already
     */
    int remove(String payloadId) {
        assert Thread.holdsLock(ioLock);
        deleteQuietly(batchFile(payloadId));
        Integer counted = eventCounts.remove(payloadId);
        return counted == null ? 0 : counted;
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
            log.renameTo(batchFile(UUID.randomUUID().toString()));
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
        if (!log.renameTo(batchFile(payloadId))) {
            return 0;
        }
        eventCounts.put(payloadId, events);
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
