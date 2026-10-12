package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The file of events on disk this process appends to, not to be confused with diagnostic logging.
 * <p>
 * One per process rather than one per directory, so that only one process ever writes a given log and
 * recovery only ever touches its own. With one shared log, a second process starting up would close
 * off, as if a previous run had left it, a log the first is still appending to through its open stream,
 * and the first's later events would land in a batch already sent or deleted. Another process's log is
 * left for that process to recover the next time it runs.
 * <p>
 * Belongs to an {@link EventStore}, which makes every call holding its {@code ioLock}. The lock is handed
 * in so that each call can check it is held, rather than this taking a lock of its own that would have
 * to be ordered against the store's.
 */
final class DiskEventLog {
    /** Names a log a single process appends to; the rest of the name is that process. */
    static final String PREFIX = "open-";

    /** How the log is opened, so a test can make a write fail partway through a session, as a full disk does. */
    interface Opener {
        FileOutputStream openForAppending(File log) throws IOException;
    }

    private final File directory;
    private final File file;
    private final Object ioLock;
    private final Opener opener;
    private final LDLogger logger;
    // Guarded by ioLock.
    private FileOutputStream output;

    DiskEventLog(File directory, String processName, Object ioLock, Opener opener, LDLogger logger) {
        this.directory = directory;
        this.file = new File(directory, PREFIX + nameFor(processName));
        this.ioLock = ioLock;
        this.opener = opener;
        this.logger = logger;
    }

    /**
     * Names the log belonging to one process.
     * <p>
     * The readable part is the process name with anything awkward in a filename replaced, which is what
     * makes a directory of these diagnosable by a human. That reduction is not one-to-one, though:
     * {@code com.app:remote} and {@code com.app.remote} are both legal process names and both come out as
     * {@code com_app_remote}. Two processes sharing one log is the single thing this name exists to
     * prevent, so the digest of the original name is appended to guarantee they never do.
     */
    static String nameFor(String processName) {
        String name = processName == null ? "" : processName.trim();
        if (name.isEmpty()) {
            name = "unknown";
        }
        StringBuilder safe = new StringBuilder(name.length() + 9);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return safe.append('-').append(EventStore.hexDigest(name, 4)).toString();
    }

    /** Where the log is, whether or not it exists yet. */
    File file() {
        return file;
    }

    /** Requires {@code ioLock}. */
    boolean isOpen() {
        assert Thread.holdsLock(ioLock);
        return output != null;
    }

    /** Requires {@code ioLock}. */
    boolean exists() {
        assert Thread.holdsLock(ioLock);
        return file.exists();
    }

    /**
     * Appends to the log, opening it first where it is not open.
     * <p>
     * Requires {@code ioLock}.
     *
     * @throws IOException if the log could not be opened or written to; none of {@code bytes} is then left
     *   in the log, and the log is closed, so the next append starts from a fresh open
     */
    void append(byte[] bytes) throws IOException {
        assert Thread.holdsLock(ioLock);
        FileOutputStream stream = outputForAppending();
        long start = -1;
        try {
            start = stream.getChannel().size();
            stream.write(bytes);
        } catch (IOException | RuntimeException e) {
            // A write that fails partway can leave whole frames behind. The caller keeps every one of
            // the bytes in memory to deliver from there, so frames left here as well would be sent
            // twice: beside the memory copy when the log is closed this session, or by recovery on the
            // next launch.
            if (start >= 0) {
                cutBack(stream, start);
            }
            closeOutput();
            throw e;
        }
    }

    /** Cuts the log back to {@code length}, where a failed write left it longer. */
    private void cutBack(FileOutputStream stream, long length) {
        try {
            stream.getChannel().truncate(length);
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not cut back a failed write to the event log: {}",
                    LogValues.exceptionSummary(e));
        }
    }

    /**
     * Moves the log to {@code destination}, so that the next append starts a new one. The stream is closed
     * first, whether or not the move succeeds.
     * <p>
     * Requires {@code ioLock}.
     *
     * @return whether the log was moved
     */
    boolean closeMovingTo(File destination) {
        assert Thread.holdsLock(ioLock);
        closeOutput();
        return file.renameTo(destination);
    }

    /** Closes the stream, leaving the log where it is. Requires {@code ioLock}. */
    void closeOutput() {
        assert Thread.holdsLock(ioLock);
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

    /** Requires {@code ioLock}. */
    private FileOutputStream outputForAppending() throws IOException {
        if (output != null) {
            return output;
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("could not create " + directory);
        }
        boolean isNew = !file.exists() || file.length() == 0;
        // Append mode is what makes each write land at the end of the file as one step, so that a
        // process cannot splice its bytes into the middle of what another wrote.
        FileOutputStream stream = opener.openForAppending(file);
        if (isNew) {
            try {
                stream.write(EventLogFormat.fileHeader());
            } catch (IOException | RuntimeException e) {
                // Not yet in output, so closeOutput would not reach it.
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // The write already failed; that is the error worth reporting.
                }
                throw e;
            }
        }
        output = stream;
        return output;
    }
}
