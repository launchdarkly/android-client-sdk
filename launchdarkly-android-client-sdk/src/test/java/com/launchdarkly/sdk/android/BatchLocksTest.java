package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

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
}
