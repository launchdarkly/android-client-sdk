package com.launchdarkly.sdk.android;

import android.annotation.SuppressLint;
import android.os.ParcelFileDescriptor;

import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * The locks that tell every live process which batches have a request out, so that expiry never deletes a
 * batch someone is still sending: were that request to fail, there would be no file left to retry from.
 * <p>
 * A sender holds a shared lock on the batch for as long as its request is out. Expiry deletes a batch only
 * while holding an exclusive lock on it, which it cannot have while anyone holds the shared one. The kernel
 * drops a lock when its process dies, so a batch whose sender is gone is expired like any other.
 * <p>
 * A POSIX lock belongs to the process rather than to the descriptor it was taken through, and closing any
 * descriptor this process has on the file releases it. So the batches locked here are remembered, and expiry
 * in this process asks that rather than opening a descriptor on one. The lock is taken after the batch has
 * been read for sending, since the read closes a descriptor of its own; a batch expired by another process in
 * the moment between is still sent from what was read, but has no file for a retry.
 */
final class BatchLocks {
    /** A batch this process is sending, the descriptor its lock was taken through, and how many send it. */
    private static final class Held {
        final RandomAccessFile file;
        FileLock lock;
        int senders;

        Held(RandomAccessFile file) {
            this.file = file;
        }
    }

    /**
     * The batches of one directory this process has a request out for, by name. Its monitor guards the probes
     * and deletions below as well, so that a store in this process cannot start sending a batch another is
     * expiring. One per directory rather than one for all, since a lock taken or dropped on one file leaves
     * every other file's alone, so stores of different environments have nothing to wait on each other for.
     */
    private static final class Directory {
        final Map<String, Held> heldInThisProcess = new HashMap<>();
    }

    /**
     * Every directory this process has sent from or expired in, by absolute path. Process-wide, because two
     * stores in one process can share a directory and must share its monitor. Never shrinks: a directory
     * dropped while a call still held it would let the next call take a second monitor for the same files.
     * There is one entry per environment directory the process has touched, which stays a handful.
     */
    private static final Map<String, Directory> directories = new HashMap<>();

    private BatchLocks() {
    }

    /** The monitor every call on a batch takes: its directory's, whichever store names the batch. */
    @VisibleForTesting
    static Object monitorFor(File batch) {
        return directoryOf(batch);
    }

    private static Directory directoryOf(File batch) {
        String path = batch.getAbsoluteFile().getParent();
        synchronized (directories) {
            Directory directory = directories.get(path);
            if (directory == null) {
                directory = new Directory();
                directories.put(path, directory);
            }
            return directory;
        }
    }

    /**
     * Locks a batch for as long as a request for it is out. Each call is ended by one
     * {@link #stoppedSending}; a batch two deliveries send at once stays locked until both have stopped.
     */
    static void startedSending(File batch) {
        Directory directory = directoryOf(batch);
        synchronized (directory) {
            String name = batch.getName();
            Held held = directory.heldInThisProcess.get(name);
            try {
                if (held == null) {
                    held = new Held(new RandomAccessFile(batch, "r"));
                    directory.heldInThisProcess.put(name, held);
                }
                held.senders++;
                if (held.lock != null) {
                    // Taken again rather than kept: this sender read the batch through a descriptor of its own,
                    // and closing that released the lock the first one took.
                    held.lock.release();
                    held.lock = null;
                }
                held.lock = held.file.getChannel().tryLock(0L, Long.MAX_VALUE, true);
            } catch (FileNotFoundException e) {
                // Gone already, so there is nothing left for expiry to take.
            } catch (IOException | OverlappingFileLockException e) {
                // Still remembered, which keeps expiry in this process away from it; only other processes
                // cannot tell.
                if (held != null) {
                    held.lock = null;
                }
            }
        }
    }

    /** Ends one {@link #startedSending}, releasing the lock once the last sender has stopped. */
    static void stoppedSending(File batch) {
        Directory directory = directoryOf(batch);
        synchronized (directory) {
            String name = batch.getName();
            Held held = directory.heldInThisProcess.get(name);
            if (held == null || --held.senders > 0) {
                return;
            }
            directory.heldInThisProcess.remove(name);
            try {
                // Releases the lock with it.
                held.file.close();
            } catch (IOException ignored) {
                // Released by the kernel all the same once the descriptor is gone.
            }
        }
    }

    /** Whether a store in this process is sending a batch, answered without opening the file. */
    static boolean isSentInThisProcess(File batch) {
        Directory directory = directoryOf(batch);
        synchronized (directory) {
            return directory.heldInThisProcess.containsKey(batch.getName());
        }
    }

    /**
     * Deletes a batch for expiry, unless some live process is sending it.
     *
     * @return whether this call deleted it; false for one being sent, one that could not be looked at, and one
     *   already gone, whose events are someone else's to count
     */
    static boolean deleteUnlessBeingSent(File batch) {
        Directory directory = directoryOf(batch);
        synchronized (directory) {
            if (directory.heldInThisProcess.containsKey(batch.getName())) {
                return false;
            }
            try (FileChannel channel = openWithoutCreating(batch);
                 FileLock exclusive = channel.tryLock()) {
                // Deleted while still locked, so no sender can take its lock between the look and the delete.
                return exclusive != null && batch.delete();
            } catch (IOException | OverlappingFileLockException e) {
                return false;
            }
        }
    }

    private static volatile boolean hasNioFiles = true;

    /**
     * Opens a batch for writing, which an exclusive lock needs, failing if it is missing. Never creates it: a
     * batch another process delivered or expired since the listing would be back for a moment, empty, and a
     * delivery reading it then would report its events lost.
     */
    static FileChannel openWithoutCreating(File batch) throws IOException {
        if (hasNioFiles) {
            try {
                return NioFiles.openWithoutCreating(batch);
            } catch (LinkageError e) {
                // Android before API 26, which has no java.nio.file.
                hasNioFiles = false;
            }
        }
        return openThroughParcelFileDescriptor(batch);
    }

    /** {@link #openWithoutCreating} for any Android version. Not on the JVM, where this class is a stub. */
    static FileChannel openThroughParcelFileDescriptor(File batch) throws FileNotFoundException {
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(batch, ParcelFileDescriptor.MODE_READ_WRITE);
        // Closing the channel closes the stream, which closes the descriptor.
        return new ParcelFileDescriptor.AutoCloseOutputStream(descriptor).getChannel();
    }

    /** Apart, so that Android without java.nio.file fails when this is called rather than when BatchLocks loads. */
    @SuppressLint("NewApi")
    private static final class NioFiles {
        static FileChannel openWithoutCreating(File batch) throws IOException {
            return FileChannel.open(batch.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE);
        }
    }
}
