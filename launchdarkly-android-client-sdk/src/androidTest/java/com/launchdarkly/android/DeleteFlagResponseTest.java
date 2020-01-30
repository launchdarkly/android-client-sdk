package com.launchdarkly.android;

import android.support.test.runner.AndroidJUnit4;

import com.google.gson.Gson;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
public class DeleteFlagResponseTest {

    @Rule
    public TimberLoggingRule timberLoggingRule = new TimberLoggingRule();

    private static final Gson gson = GsonCache.getGson();

    @Test
    public void deleteFlagResponseKeyIsDeserialized() {
        final String jsonStr = "{\"key\": \"flag\"}";
        final DeleteFlagResponse delete = gson.fromJson(jsonStr, DeleteFlagResponse.class);
        assertEquals("flag", delete.flagToUpdate());
    }

    @Test
    public void testUpdateFlag() {
        // Create delete flag responses from json to verify version is deserialized
        final String jsonNoVersion = "{\"key\": \"flag\"}";
        final String jsonLowVersion = "{\"key\": \"flag\", \"version\": 50}";
        final String jsonHighVersion = "{\"key\": \"flag\", \"version\": 100}";
        final DeleteFlagResponse deleteNoVersion = gson.fromJson(jsonNoVersion, DeleteFlagResponse.class);
        final DeleteFlagResponse deleteLowVersion = gson.fromJson(jsonLowVersion, DeleteFlagResponse.class);
        final DeleteFlagResponse deleteHighVersion = gson.fromJson(jsonHighVersion, DeleteFlagResponse.class);
        final Flag flagNoVersion = new FlagBuilder("flag").build();
        final Flag flagLowVersion = new FlagBuilder("flag").version(50).build();
        final Flag flagHighVersion = new FlagBuilder("flag").version(100).build();

        assertNull(deleteNoVersion.updateFlag(null));
        assertNull(deleteNoVersion.updateFlag(flagNoVersion));
        assertNull(deleteNoVersion.updateFlag(flagLowVersion));
        assertNull(deleteNoVersion.updateFlag(flagHighVersion));
        assertNull(deleteLowVersion.updateFlag(null));
        assertNull(deleteLowVersion.updateFlag(flagNoVersion));
        assertEquals(flagLowVersion, deleteLowVersion.updateFlag(flagLowVersion));
        assertEquals(flagHighVersion, deleteLowVersion.updateFlag(flagHighVersion));
        assertNull(deleteHighVersion.updateFlag(null));
        assertNull(deleteHighVersion.updateFlag(flagNoVersion));
        assertNull(deleteHighVersion.updateFlag(flagLowVersion));
    }
}
