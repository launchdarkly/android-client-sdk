package com.launchdarkly.sdk.android;

import com.google.gson.Gson;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DeleteFlagResponseTest {

    private static final Gson gson = GsonCache.getGson();

    private static final String jsonWithNullVersion = "{\"key\": \"flag2\", \"version\": null}";
    private static final String jsonWithVersion = "{\"key\": \"flag\", \"version\": 50}";
    private static final String jsonWithExtraElement = "{\"key\": \"flag\", \"version\": 100, \"extra\": [1,2,3]}";

    @Test
    public void constructor() {
        // Cannot check flag version, as the field is not exposed.
        assertNull((new DeleteFlagResponse(null, 1)).flagToUpdate());
        assertEquals("test", (new DeleteFlagResponse("test", 1)).flagToUpdate());
    }

    @Test
    public void deleteFlagResponseKeyIsDeserialized() {
        DeleteFlagResponse result;
        result = gson.fromJson(jsonWithNullVersion, DeleteFlagResponse.class);
        assertEquals("flag2", result.flagToUpdate());
        result = gson.fromJson(jsonWithVersion, DeleteFlagResponse.class);
        assertEquals("flag", result.flagToUpdate());
        result = gson.fromJson(jsonWithExtraElement, DeleteFlagResponse.class);
        assertEquals("flag", result.flagToUpdate());
    }

    @Test
    public void testUpdateFlag() {
        // Create delete flag responses from json to verify version is deserialized
        DeleteFlagResponse deleteLowVersion = gson.fromJson(jsonWithVersion, DeleteFlagResponse.class);
        DeleteFlagResponse deleteHighVersion = gson.fromJson(jsonWithExtraElement, DeleteFlagResponse.class);
        Flag flagLowVersion = new FlagBuilder("flag").version(50).build();
        Flag flagHighVersion = new FlagBuilder("flag").version(100).build();

        assertEquals(flagLowVersion, deleteLowVersion.updateFlag(flagLowVersion));
        assertEquals(flagHighVersion, deleteLowVersion.updateFlag(flagHighVersion));

        assertDeletedItemPlaceholder(deleteLowVersion.updateFlag(null),
                deleteLowVersion.flagToUpdate(), deleteLowVersion.getVersion());
        assertDeletedItemPlaceholder(deleteHighVersion.updateFlag(null),
                deleteHighVersion.flagToUpdate(), deleteHighVersion.getVersion());
        assertDeletedItemPlaceholder(deleteHighVersion.updateFlag(flagLowVersion),
                deleteHighVersion.flagToUpdate(), deleteHighVersion.getVersion());
    }

    private static void assertDeletedItemPlaceholder(Flag f, String key, int version) {
        assertEquals(key, f.getKey());
        assertEquals(version, f.getVersion());
        assertTrue(f.isDeleted());
    }
}
