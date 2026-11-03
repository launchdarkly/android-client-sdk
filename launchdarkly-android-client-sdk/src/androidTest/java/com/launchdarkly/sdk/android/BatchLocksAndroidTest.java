package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/**
 * The way expiry opens a batch on Android before API 26, run on whatever device is attached: the JVM tests
 * cannot reach it.
 */
@RunWith(AndroidJUnit4.class)
public class BatchLocksAndroidTest {
    private final File directory =
            new File(ApplicationProvider.getApplicationContext().getCacheDir(), "batch-locks-test");

    @Before
    public void createDirectory() {
        assertTrue(directory.isDirectory() || directory.mkdirs());
    }

    @After
    public void deleteDirectory() {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        directory.delete();
    }

    @Test
    public void neverCreatesABatchThatHasGone() throws IOException {
        File gone = new File(directory, "ready-gone-1");
        try {
            BatchLocks.openThroughParcelFileDescriptor(gone).close();
            fail("opened a batch that is not there");
        } catch (FileNotFoundException expected) {
            // What expiry takes for a batch someone else has dealt with.
        }
        assertFalse(gone.exists());
    }

    @Test
    public void locksABatchExclusivelyAndLeavesItAsItWas() throws IOException {
        File batch = new File(directory, "ready-there-1");
        byte[] contents = { 1, 2, 3 };
        try (FileOutputStream out = new FileOutputStream(batch)) {
            out.write(contents);
        }

        try (FileChannel channel = BatchLocks.openThroughParcelFileDescriptor(batch);
             FileLock exclusive = channel.tryLock()) {
            assertNotNull(exclusive);
            assertFalse(exclusive.isShared());
        }

        assertEquals(contents.length, batch.length());
    }
}
