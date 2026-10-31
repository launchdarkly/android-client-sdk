package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashSet;
import java.util.Set;

/**
 * The file of events on disk this process appends to, not to be confused with diagnostic logging.
 * <p>
 * One per store rather than one per directory, so that only one writer ever appends to a given log. Each is
 * named, by {@link EventDirectory} as every file there is, for a UUID picked when it is opened, which is what
 * keeps two writers apart: nothing about it comes from
 * the application, and no two processes, or two stores in one, ever pick the same one.
 * <p>
 * The owner holds an exclusive lock on its log for as long as it is open, and the kernel releases it when the
 * descriptor closes, including when the process dies. A log no one holds the lock on therefore belongs to no
 * one alive, whatever process wrote it, and recovery takes it. A log is locked before it takes the name
 * recovery looks for, so that a log being opened is never taken for one whose owner is gone.
 * <p>
 * A POSIX lock belongs to the process rather than to the descriptor it was taken through, and closing any
 * descriptor this process has on the file releases it. So nothing in this process opens another store's log:
 * the logs open here are remembered, and recognized as live without a descriptor being opened on them.
 * <p>
 * Belongs to an {@link EventStore}, which makes every call holding its {@code ioLock}. The lock is handed
 * in so that each call can check it is held, rather than this taking a lock of its own that would have
 * to be ordered against the store's.
 */
final class DiskEventLog {
    /** How the log is opened, so a test can make a write fail partway through a session, as a full disk does. */
    interface Opener {
        FileOutputStream openForAppending(File file) throws IOException;
    }

    /** The absolute paths of the logs open in this process, which nothing here may open a descriptor on. */
    private static final Set<String> openInThisProcess = new HashSet<>();

    private final File directory;
    private final Object ioLock;
    private final Opener opener;
    private final LDLogger logger;
    // Guarded by ioLock.
    private File file;
    private FileOutputStream output;
    private FileLock lock;

    DiskEventLog(File directory, Object ioLock, Opener opener, LDLogger logger) {
        this.directory = directory;
        this.file = EventDirectory.newLogFile(directory);
        this.ioLock = ioLock;
        this.opener = opener;
        this.logger = logger;
    }

    /**
     * Whether some live process holds a log, and recovery must leave it alone.
     * <p>
     * A log open in this process is answered from memory. Any other is answered by trying for a shared lock
     * on it, which fails while its owner holds the exclusive one. A log that cannot be looked at counts as
     * held: leaving a dead process's log for a later run costs nothing, while taking a live one's would lose
     * what it appends next.
     */
    static boolean isHeldByLiveProcess(File log) {
        synchronized (openInThisProcess) {
            if (openInThisProcess.contains(log.getAbsolutePath())) {
                return true;
            }
        }
        try (RandomAccessFile probe = new RandomAccessFile(log, "r");
             FileChannel channel = probe.getChannel();
             FileLock held = channel.tryLock(0L, Long.MAX_VALUE, true)) {
            return held == null;
        } catch (FileNotFoundException e) {
            // Gone, so no one is appending to it.
            return false;
        } catch (IOException | OverlappingFileLockException e) {
            return true;
        }
    }

    /** Where the log is, whether it exists yet or not. */
    File file() {
        return file;
    }

    /** Requires {@code ioLock}. */
    boolean exists() {
        assert Thread.holdsLock(ioLock);
        return file.exists();
    }

    /**
     * Whether this store has the log open and locked, so that no other process can have recovered it, nor
     * any other store in this one. Requires {@code ioLock}.
     */
    boolean isHeld() {
        assert Thread.holdsLock(ioLock);
        return output != null && lock != null;
    }

    /**
     * Appends to the log, opening it first where it is not open.
     * <p>
     * Requires {@code ioLock}.
     *
     * @throws IOException if the log could not be opened or written to; none of {@code bytes} is then left
     *   in the log, and the log is closed, so the next append starts a new one
     */
    void append(byte[] bytes) throws IOException {
        assert Thread.holdsLock(ioLock);
        FileOutputStream stream = outputForAppending();
        long start = -1;
        try {
            start = stream.getChannel().size();
            stream.write(bytes);
        } catch (IOException | RuntimeException e) {
            // Writing that fails partway can leave whole frames behind. The caller keeps every one of
            // the bytes in memory to deliver from there, so frames left here as well would be sent
            // twice: beside the memory copy when the log is closed this session, or by recovery on the
            // next launch.
            if (start >= 0 && !truncate(stream, start) && start <= EventLogFormat.FILE_HEADER_SIZE) {
                // A log with no events before this write is never closed into a batch, and a later run
                // recovering it would send what landed under no count that leaves it out. Memory holds
                // all of it.
                if (!file.delete() && file.exists()) {
                    logger.warn("Could not remove an event log holding only a failed write");
                }
            }
            closeOutput();
            throw e;
        }
    }

    /**
     * Truncates the log to {@code length}, where a failed write left it longer. Where it cannot, the
     * batch this log is closed into still delivers only the events it was closed holding, which leaves out
     * the frames left behind.
     *
     * @return false if the log could not be truncated
     */
    private boolean truncate(FileOutputStream stream, long length) {
        try {
            stream.getChannel().truncate(length);
            return true;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not truncate a failed write to the event log: {}",
                    LogValues.exceptionSummary(e));
            return false;
        }
    }

    /**
     * Moves the log to {@code destination}, so that the next append starts a new one.
     * <p>
     * Closed after the move rather than before it, so that the log is still locked while it moves: unlocked
     * under this name, it could be taken by another process recovering logs in between.
     * <p>
     * A move that fails leaves a log that is still there open and locked, to be appended to and moved
     * again. Closed, it would be no longer this store's to write, and the next append would start another
     * log while the caller still counted this one's events as the open log's: they would be out of reach
     * this session, and the next batch would be named for more events than it holds.
     * <p>
     * Requires {@code ioLock}.
     *
     * @return whether the log was moved
     */
    boolean closeMovingTo(File destination) {
        assert Thread.holdsLock(ioLock);
        boolean moved = file.renameTo(destination);
        if (moved || !file.exists()) {
            closeOutput();
        }
        return moved;
    }

    /**
     * Closes the stream, leaving the log where it is. Requires {@code ioLock}.
     * <p>
     * Unlocked, it is from here on a log like one a process that died left behind, which recovery may take
     * at any moment. So this log is never opened again: the next append starts a new one.
     */
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
        lock = null;
        forget(file);
    }

    /**
     * Takes the lock on the open log again, after something in this process closed a descriptor of its own
     * on the file, which released it. Requires {@code ioLock}.
     */
    void relockAfterReading() {
        assert Thread.holdsLock(ioLock);
        if (output == null) {
            return;
        }
        try {
            if (lock != null) {
                lock.release();
            }
            lock = output.getChannel().tryLock();
        } catch (IOException | RuntimeException e) {
            lock = null;
        }
        if (lock == null) {
            logger.debug("Could not lock the event log again after reading it");
        }
    }

    /**
     * Opens a new log under a name of its own: created under {@link EventDirectory#OPENING_LOG_PREFIX},
     * locked, given its header, and only then moved to the name recovery looks for.
     * <p>
     * Requires {@code ioLock}.
     */
    private FileOutputStream outputForAppending() throws IOException {
        if (output != null) {
            return output;
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("could not create " + directory);
        }
        // Never the name of an earlier open in this session, whatever exists() says of it: that log is unlocked
        // and no longer this store's to write, and exists() answers false where it cannot look, so the rename
        // below would replace it and lose its events.
        file = EventDirectory.newLogFile(directory);
        File opening = EventDirectory.openingFileFor(file);
        remember(file);
        FileOutputStream stream = null;
        try {
            stream = opener.openForAppending(opening);
            FileLock taken = stream.getChannel().tryLock();
            if (taken == null) {
                throw new IOException("could not lock " + opening);
            }
            stream.write(EventLogFormat.fileHeader());
            if (!opening.renameTo(file)) {
                throw new IOException("could not move " + opening + " to " + file);
            }
            lock = taken;
            output = stream;
            return output;
        } catch (IOException | RuntimeException e) {
            // Not yet in output, so closeOutput would not reach it.
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // The open already failed; that is the error worth reporting.
                }
            }
            if (opening.exists() && !opening.delete()) {
                logger.debug("Could not delete {}", opening);
            }
            forget(file);
            throw e;
        }
    }

    private static void remember(File log) {
        synchronized (openInThisProcess) {
            openInThisProcess.add(log.getAbsolutePath());
        }
    }

    private static void forget(File log) {
        synchronized (openInThisProcess) {
            openInThisProcess.remove(log.getAbsolutePath());
        }
    }
}
