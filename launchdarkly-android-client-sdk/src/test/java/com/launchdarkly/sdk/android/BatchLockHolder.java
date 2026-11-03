package com.launchdarkly.sdk.android;

import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/**
 * Run in a process of its own by tests: holds a shared lock on a batch the way a sender in another process does,
 * until its standard input closes. POSIX locks belong to a process, so only another one can show what this
 * process's expiry makes of them.
 */
public final class BatchLockHolder {
    public static void main(String[] args) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(args[0], "r");
             FileLock ignored = file.getChannel().lock(0L, Long.MAX_VALUE, true)) {
            System.out.println("locked");
            System.out.flush();
            while (System.in.read() >= 0) {
                // Held until the test closes the stream.
            }
        }
    }
}
