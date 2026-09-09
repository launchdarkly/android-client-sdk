package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
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
 * The format is byte-for-byte the one the iOS SDK writes, so the two can be reasoned about, and
 * debugged, as one thing.
 */
final class EventStore implements Closeable {
    /** Names a log closed off for delivery; the rest of the name is the batch's payload ID. */
    private static final String BATCH_PREFIX = "ready-";
    /** Names the log a single process appends to; the rest of the name is that process. */
    private static final String OPEN_PREFIX = "open-";
    private static final String DIRECTORY_NAME = "com.launchdarkly.events";

    /**
     * How many staged bytes may accumulate before one of them pays for a write.
     * <p>
     * This is what keeps a syscall off most recordings while bounding what a crash can take with it,
     * for an application that only evaluates flags and so never reaches a commit point of its own.
     */
    private static final int STAGING_THRESHOLD = 16 * 1024;

    private final File directory;
    private final File openLog;
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
    private int committedEvents;
    private int closedEvents;
    private boolean persistenceDisabled;
    private boolean commitScheduled;

    // Guarded by ioLock.
    private FileOutputStream output;
    /**
     * Batches held in memory because the filesystem would not take them.
     * <p>
     * Persistence failing should cost durability and nothing else, so the store falls back to what the
     * SDK did before it existed: hold the events, deliver them, lose them only if the process dies.
     */
    private final Map<String, HeldBatch> inMemoryBatches = new LinkedHashMap<>();

    EventStore(File directory, String processName, int capacity, LDLogger logger, Executor commitExecutor) {
        this.directory = directory;
        this.openLog = new File(directory, OPEN_PREFIX + logNameFor(processName));
        this.capacity = capacity >= 0 ? capacity : 1;
        this.logger = logger;
        this.commitExecutor = commitExecutor;
    }

    /**
     * Creates the store for one environment of one process.
     *
     * @param noBackupFilesDir where the platform lets the SDK keep files it must not lose
     * @param mobileKey identifies the environment, so several clients stay out of each other's way
     * @param processName identifies the process, so several processes stay out of each other's way
     */
    static EventStore create(
            File noBackupFilesDir,
            String mobileKey,
            String processName,
            int capacity,
            LDLogger logger
    ) {
        File directory = new File(new File(noBackupFilesDir, DIRECTORY_NAME),
                environmentDirectoryName(mobileKey));
        return new EventStore(directory, processName, capacity, logger, defaultCommitExecutor());
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
     * Names the log file belonging to one process.
     * <p>
     * The readable part is the process name with anything awkward in a filename replaced, which is what
     * makes a directory of these diagnosable by a human. That reduction is not one-to-one, though:
     * {@code com.app:remote} and {@code com.app.remote} are both legal process names and both come out as
     * {@code com_app_remote}. Two processes sharing one log is the single thing this name exists to
     * prevent, so the digest of the original name is appended to guarantee they never do.
     */
    static String logNameFor(String processName) {
        String name = processName == null ? "" : processName.trim();
        if (name.isEmpty()) {
            name = "unknown";
        }
        StringBuilder safe = new StringBuilder(name.length() + 9);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return safe.append('-').append(hexDigest(name, 4)).toString();
    }

    /**
     * @param bytes how many bytes of the digest to render, so a caller can trade length for headroom
     * @return the first {@code bytes} bytes of the input's SHA-256, in lowercase hex
     */
    private static String hexDigest(String input, int bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(Charset.forName("UTF-8")));
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

    private static Executor defaultCommitExecutor() {
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
     *   memory, written to the open log, or sitting in a closed batch
     */
    int getPendingEventCount() {
        synchronized (bufferLock) {
            return bufferedEventCount + committedEvents + closedEvents;
        }
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
        if (serializedEvent == null || serializedEvent.length == 0
                || serializedEvent.length > Format.MAX_FRAME_SIZE) {
            return false;
        }

        boolean needsCommit;
        synchronized (bufferLock) {
            if (!bypassingCapacity
                    && bufferedEventCount + committedEvents + closedEvents >= capacity) {
                return false;
            }
            Format.writeFrame(bufferData, serializedEvent);
            bufferedEventCount++;
            needsCommit = !persistenceDisabled && bufferData.size() >= STAGING_THRESHOLD && !commitScheduled;
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
        synchronized (ioLock) {
            commitHoldingIoLock();
        }
    }

    // MARK: delivery

    /**
     * Commits, then closes the open log into a batch to deliver.
     *
     * @return the batch, or null when there is nothing to send
     */
    Batch closeBatch() {
        synchronized (ioLock) {
            commitHoldingIoLock();

            int events;
            int stillBuffered;
            synchronized (bufferLock) {
                events = committedEvents;
                stillBuffered = bufferedEventCount;
            }

            if (events > 0) {
                return closeOpenLogHoldingIoLock(events);
            }
            if (stillBuffered > 0) {
                // Only reachable once persistence has been given up on; a healthy commit leaves nothing
                // staged behind.
                return closeInMemoryBatchHoldingIoLock();
            }
            return null;
        }
    }

    /** Requires {@code ioLock}. */
    private Batch closeOpenLogHoldingIoLock(int events) {
        closeOutputHoldingIoLock();

        String payloadId = UUID.randomUUID().toString();
        if (!openLog.renameTo(batchFile(payloadId))) {
            logger.warn("Could not close the event log for delivery");
            if (!openLog.exists()) {
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
        return new Batch(payloadId, events);
    }

    /** Requires {@code ioLock}. */
    private Batch closeInMemoryBatchHoldingIoLock() {
        byte[] frames;
        int events;
        synchronized (bufferLock) {
            frames = bufferData.toByteArray();
            events = bufferedEventCount;
            bufferData.reset();
            bufferedEventCount = 0;
        }
        byte[] body = Format.assembleBody(Format.withFileHeader(frames));
        if (body == null) {
            return null;
        }
        String payloadId = UUID.randomUUID().toString();
        inMemoryBatches.put(payloadId, new HeldBatch(body, events));
        synchronized (bufferLock) {
            closedEvents += events;
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
        synchronized (ioLock) {
            List<Batch> batches = new ArrayList<>();
            File[] files = directory.listFiles();
            if (files != null) {
                List<File> ready = new ArrayList<>();
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
                for (File file : ready) {
                    int events = Format.eventCount(readFile(file));
                    if (events < 0) {
                        // Written by a version whose format this one does not read, or damaged beyond
                        // what the torn-tail recovery tolerates. Either way it can never be delivered.
                        deleteQuietly(file);
                        continue;
                    }
                    batches.add(new Batch(file.getName().substring(BATCH_PREFIX.length()), events));
                }
            }
            for (Map.Entry<String, HeldBatch> held : inMemoryBatches.entrySet()) {
                batches.add(new Batch(held.getKey(), held.getValue().eventCount));
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
    }

    /**
     * @return the JSON request body for a batch, or null if it has since become unreadable
     */
    byte[] body(Batch batch) {
        synchronized (ioLock) {
            HeldBatch held = inMemoryBatches.get(batch.payloadId);
            if (held != null) {
                return held.body;
            }
            return Format.assembleBody(readFile(batchFile(batch.payloadId)));
        }
    }

    /**
     * Forgets a batch, which is only correct once LaunchDarkly has accepted it or permanently refused it.
     */
    void remove(Batch batch) {
        synchronized (ioLock) {
            if (inMemoryBatches.remove(batch.payloadId) == null) {
                deleteQuietly(batchFile(batch.payloadId));
            }
            synchronized (bufferLock) {
                closedEvents = Math.max(0, closedEvents - batch.eventCount);
            }
        }
    }

    /**
     * Closes any log this process left open on a previous run, so its events join the batches to deliver.
     * <p>
     * The events in it were recorded by a process that is gone, so there is no one left to add to it. Only
     * this process's own log is touched: another process's may still be open in a process that is alive.
     */
    void recoverInterruptedLog() {
        synchronized (ioLock) {
            if (!openLog.exists()) {
                return;
            }
            int events = Format.eventCount(readFile(openLog));
            if (events < 0) {
                // Unreadable, and a log that cannot be read cannot be appended to either.
                deleteQuietly(openLog);
                return;
            }
            if (events == 0) {
                deleteQuietly(openLog);
                return;
            }
            String payloadId = UUID.randomUUID().toString();
            if (openLog.renameTo(batchFile(payloadId))) {
                logger.info("Recovered {} event(s) that a previous run of this application did not deliver",
                        events);
            }
        }
    }

    // MARK: writing

    /** Requires {@code ioLock}. */
    private void commitHoldingIoLock() {
        byte[] bytes;
        int events;
        synchronized (bufferLock) {
            if (persistenceDisabled || bufferedEventCount == 0) {
                // Staged bytes are left where they are: with nowhere durable to put them, memory is
                // better than dropping them.
                return;
            }
            bytes = bufferData.toByteArray();
            events = bufferedEventCount;
            bufferData.reset();
            bufferedEventCount = 0;
        }

        if (append(bytes)) {
            synchronized (bufferLock) {
                committedEvents += events;
            }
        } else {
            // Put back, to be delivered from memory rather than lost.
            synchronized (bufferLock) {
                byte[] laterFrames = bufferData.toByteArray();
                bufferData.reset();
                writeQuietly(bufferData, bytes);
                writeQuietly(bufferData, laterFrames);
                bufferedEventCount += events;
            }
        }
    }

    /**
     * Requires {@code ioLock}.
     *
     * @return false if the bytes could not be written, in which case persistence is given up on
     */
    private boolean append(byte[] bytes) {
        try {
            FileOutputStream stream = outputForAppendingHoldingIoLock();
            if (stream == null) {
                return false;
            }
            stream.write(bytes);
            return true;
        } catch (IOException | RuntimeException e) {
            // Never let a full disk take the application down. The SDK gives up on persistence for the
            // rest of the session instead, which is how other SDKs have crashed their hosts.
            logger.warn("Giving up on persisting events: {}", LogValues.exceptionSummary(e));
            closeOutputHoldingIoLock();
            synchronized (bufferLock) {
                persistenceDisabled = true;
            }
            return false;
        }
    }

    /** Requires {@code ioLock}. */
    private FileOutputStream outputForAppendingHoldingIoLock() throws IOException {
        if (output != null) {
            return output;
        }
        synchronized (bufferLock) {
            if (persistenceDisabled) {
                return null;
            }
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("could not create " + directory);
        }
        boolean isNew = !openLog.exists() || openLog.length() == 0;
        // Append mode is what makes each write land at the end of the file as one step, so that a
        // process cannot splice its bytes into the middle of what another wrote.
        FileOutputStream stream = new FileOutputStream(openLog, true);
        if (isNew) {
            stream.write(Format.fileHeader());
        }
        output = stream;
        return output;
    }

    /** Requires {@code ioLock}. */
    private void closeOutputHoldingIoLock() {
        if (output == null) {
            return;
        }
        try {
            output.close();
        } catch (IOException e) {
            logger.debug("Could not close the event log: {}", LogValues.exceptionSummary(e));
        }
        output = null;
    }

    @Override
    public void close() {
        synchronized (ioLock) {
            commitHoldingIoLock();
            closeOutputHoldingIoLock();
        }
    }

    private File batchFile(String payloadId) {
        return new File(directory, BATCH_PREFIX + payloadId);
    }

    File getDirectory() {
        return directory;
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
     * @return the file's bytes, or null if it could not be read
     */
    private byte[] readFile(File file) {
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(chunk)) > 0) {
                contents.write(chunk, 0, read);
            }
        } catch (IOException e) {
            return null;
        }
        return contents.toByteArray();
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
     * The on-disk shape of an event log.
     * <p>
     * A log opens with a magic and a format version, and holds a sequence of frames:
     * <pre>
     *     +- 4 bytes -+- 2 bytes -+     +- 2 bytes -+-  4 bytes  -+- n bytes -+
     *     |   LDEV    |  version  | ... |   type    |  length (n) |  payload  |
     *     +-----------+-----------+     +-----------+-------------+-----------+
     * </pre>
     * The length prefix is what makes a log recoverable: a reader can tell a frame the writer finished
     * from one it did not, without having to parse the event to find where it ends. The version is what
     * makes an upgrade safe - a log this version does not understand is discarded rather than misread -
     * and the frame type leaves room for a later version to write something new into a log this one
     * still reads.
     */
    static final class Format {
        /** Bump this whenever the framing or the meaning of a frame changes. */
        static final int VERSION = 1;
        static final byte[] MAGIC = "LDEV".getBytes(Charset.forName("US-ASCII"));
        static final int FILE_HEADER_SIZE = 6;
        static final int FRAME_HEADER_SIZE = 6;
        /** The only frame type written today; a reader skips a frame whose type it does not know. */
        static final int EVENT_FRAME = 1;
        /** A ceiling on a single frame, so a corrupt length cannot make recovery allocate wildly. */
        static final int MAX_FRAME_SIZE = 8 * 1024 * 1024;

        private Format() {
        }

        static byte[] fileHeader() {
            byte[] header = new byte[FILE_HEADER_SIZE];
            System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
            header[4] = (byte) (VERSION >> 8);
            header[5] = (byte) VERSION;
            return header;
        }

        static void writeFrame(ByteArrayOutputStream target, byte[] serializedEvent) {
            target.write((byte) (EVENT_FRAME >> 8));
            target.write((byte) EVENT_FRAME);
            int length = serializedEvent.length;
            target.write((byte) (length >> 24));
            target.write((byte) (length >> 16));
            target.write((byte) (length >> 8));
            target.write((byte) length);
            writeQuietly(target, serializedEvent);
        }

        /** Frames on their own, as a log: only for the in-memory fallback, which has no file. */
        static byte[] withFileHeader(byte[] frames) {
            byte[] log = new byte[FILE_HEADER_SIZE + frames.length];
            System.arraycopy(fileHeader(), 0, log, 0, FILE_HEADER_SIZE);
            System.arraycopy(frames, 0, log, FILE_HEADER_SIZE, frames.length);
            return log;
        }

        /**
         * @return how many event frames the log holds, or -1 if it is not a log this version reads
         */
        static int eventCount(byte[] log) {
            if (!isReadable(log)) {
                return -1;
            }
            int events = 0;
            int cursor = FILE_HEADER_SIZE;
            while (cursor + FRAME_HEADER_SIZE <= log.length) {
                int type = readUInt16(log, cursor);
                int length = readInt32(log, cursor + 2);
                int start = cursor + FRAME_HEADER_SIZE;
                if (length <= 0 || length > MAX_FRAME_SIZE || start + length > log.length) {
                    // A process that died partway through a write leaves a torn frame at the end.
                    // Everything before it is intact, so recovery keeps that and drops only the tail.
                    break;
                }
                if (type == EVENT_FRAME) {
                    events++;
                }
                cursor = start + length;
            }
            return events;
        }

        /**
         * Assembles the JSON array LaunchDarkly expects out of the frames, without parsing the events:
         * they were serialized on the way in and are shipped exactly as they were recorded.
         *
         * @return the request body, or null if the log is unreadable or holds no events
         */
        static byte[] assembleBody(byte[] log) {
            if (!isReadable(log)) {
                return null;
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream(log.length + 2);
            body.write('[');
            boolean isFirst = true;
            int cursor = FILE_HEADER_SIZE;
            while (cursor + FRAME_HEADER_SIZE <= log.length) {
                int type = readUInt16(log, cursor);
                int length = readInt32(log, cursor + 2);
                int start = cursor + FRAME_HEADER_SIZE;
                if (length <= 0 || length > MAX_FRAME_SIZE || start + length > log.length) {
                    break;
                }
                if (type == EVENT_FRAME) {
                    if (!isFirst) {
                        body.write(',');
                    }
                    body.write(log, start, length);
                    isFirst = false;
                }
                cursor = start + length;
            }
            if (isFirst) {
                return null;
            }
            body.write(']');
            return body.toByteArray();
        }

        private static boolean isReadable(byte[] log) {
            if (log == null || log.length < FILE_HEADER_SIZE) {
                return false;
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (log[i] != MAGIC[i]) {
                    return false;
                }
            }
            return readUInt16(log, MAGIC.length) == VERSION;
        }

        private static int readUInt16(byte[] bytes, int offset) {
            return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
        }

        private static int readInt32(byte[] bytes, int offset) {
            return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                    | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
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
        synchronized (ioLock) {
            List<byte[]> logs = new ArrayList<>();
            File[] files = directory.listFiles();
            if (files != null) {
                List<File> sorted = new ArrayList<>();
                for (File file : files) {
                    if (file.getName().startsWith(BATCH_PREFIX)) {
                        sorted.add(file);
                    }
                }
                Collections.sort(sorted, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });
                for (File file : sorted) {
                    logs.add(readFile(file));
                }
            }
            if (openLog.exists()) {
                logs.add(readFile(openLog));
            }
            synchronized (bufferLock) {
                if (bufferedEventCount > 0) {
                    logs.add(Format.withFileHeader(bufferData.toByteArray()));
                }
            }

            List<byte[]> payloads = new ArrayList<>();
            for (byte[] log : logs) {
                collectPayloads(log, payloads);
            }
            return payloads;
        }
    }

    private static void collectPayloads(byte[] log, List<byte[]> into) {
        if (!Format.isReadable(log)) {
            return;
        }
        int cursor = Format.FILE_HEADER_SIZE;
        while (cursor + Format.FRAME_HEADER_SIZE <= log.length) {
            int type = Format.readUInt16(log, cursor);
            int length = Format.readInt32(log, cursor + 2);
            int start = cursor + Format.FRAME_HEADER_SIZE;
            if (length <= 0 || length > Format.MAX_FRAME_SIZE || start + length > log.length) {
                break;
            }
            if (type == Format.EVENT_FRAME) {
                into.add(Arrays.copyOfRange(log, start, start + length));
            }
            cursor = start + length;
        }
    }
}
