package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class BatchLocksTest {
    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void expiryLeavesNoFileWhereABatchHasGone() {
        File gone = new File(tempFolder.getRoot(), "ready-gone-1");

        assertFalse(BatchLocks.deleteUnlessBeingSent(gone));
        assertFalse("an empty batch was left where the one delivered had been", gone.exists());
    }

    @Test
    public void expiryDeletesABatchNobodyIsSending() throws IOException {
        File batch = tempFolder.newFile("ready-old-1");
        try (FileOutputStream out = new FileOutputStream(batch)) {
            out.write(new byte[] { 1, 2, 3 });
        }

        assertTrue(BatchLocks.deleteUnlessBeingSent(batch));
        assertFalse(batch.exists());
    }

    @Test
    public void batchesOfOneDirectoryShareAMonitorAndAnotherDirectoryHasItsOwn() throws IOException {
        File directory = tempFolder.newFolder("one");
        File other = tempFolder.newFolder("other");

        assertSame(BatchLocks.monitorFor(new File(directory, "ready-a-1")),
                BatchLocks.monitorFor(new File(directory.getPath(), "ready-b-1")));
        assertNotSame(BatchLocks.monitorFor(new File(directory, "ready-a-1")),
                BatchLocks.monitorFor(new File(other, "ready-a-1")));
    }

    @Test
    public void expiryInOneDirectoryDoesNotWaitOnAnotherDirectoryButWaitsOnItsOwn() throws Exception {
        File held = batchIn(tempFolder.newFolder("held"), "ready-held-1");
        File besideIt = batchIn(held.getParentFile(), "ready-beside-1");
        File elsewhere = batchIn(tempFolder.newFolder("elsewhere"), "ready-elsewhere-1");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> sameDirectory;
            synchronized (BatchLocks.monitorFor(held)) {
                assertTrue("expiry in another directory waited on this one's monitor",
                        executor.submit(() -> BatchLocks.deleteUnlessBeingSent(elsewhere)).get(5, TimeUnit.SECONDS));
                sameDirectory = executor.submit(() -> BatchLocks.deleteUnlessBeingSent(besideIt));
                Thread.sleep(200);
                assertFalse("expiry in the same directory did not wait on its monitor", sameDirectory.isDone());
            }
            assertTrue(sameDirectory.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void aBatchSentInOneDirectoryLeavesOneOfTheSameNameInAnotherToExpiry() throws IOException {
        File sent = batchIn(tempFolder.newFolder("sending"), "ready-same-1");
        File namesake = batchIn(tempFolder.newFolder("expiring"), "ready-same-1");

        BatchLocks.startedSending(sent);
        try {
            assertTrue(BatchLocks.isSentInThisProcess(sent));
            assertFalse(BatchLocks.isSentInThisProcess(namesake));
            assertFalse(BatchLocks.deleteUnlessBeingSent(sent));
            assertTrue(BatchLocks.deleteUnlessBeingSent(namesake));
        } finally {
            BatchLocks.stoppedSending(sent);
        }
        assertFalse(BatchLocks.isSentInThisProcess(sent));
        assertTrue(BatchLocks.deleteUnlessBeingSent(sent));
    }

    private static File batchIn(File directory, String name) throws IOException {
        File batch = new File(directory, name);
        try (FileOutputStream out = new FileOutputStream(batch)) {
            out.write(new byte[] { 1, 2, 3 });
        }
        return batch;
    }
}
