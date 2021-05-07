package com.launchdarkly.sdk.android;

import com.google.gson.Gson;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DeleteFlagResponseTest {

    private static final Gson gson = GsonCache.getGson();

    private static final String jsonWithoutVersion = "{\"key\": \"flag\"}";
    private static final String jsonWithNullVersion = "{\"key\": \"flag2\", \"version\": null}";
    private static final String jsonWithVersion = "{\"key\": \"flag\", \"version\": 50}";
    private static final String jsonWithExtraElement = "{\"key\": \"flag\", \"version\": 100, \"extra\": [1,2,3]}";

    @Test
    public void constructor() {
        // Cannot check flag version, as the field is not exposed.
        assertNull((new DeleteFlagResponse(null, null)).flagToUpdate());
        assertEquals("test", (new DeleteFlagResponse("test", null)).flagToUpdate());
    }

    @Test
    public void deleteFlagResponseKeyIsDeserialized() {
        DeleteFlagResponse result;
        result = gson.fromJson(jsonWithoutVersion, DeleteFlagResponse.class);
        assertEquals("flag", result.flagToUpdate());
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
        DeleteFlagResponse deleteNoVersion = gson.fromJson(jsonWithoutVersion, DeleteFlagResponse.class);
        DeleteFlagResponse deleteLowVersion = gson.fromJson(jsonWithVersion, DeleteFlagResponse.class);
        DeleteFlagResponse deleteHighVersion = gson.fromJson(jsonWithExtraElement, DeleteFlagResponse.class);
        Flag flagNoVersion = new FlagBuilder("flag").build();
        Flag flagLowVersion = new FlagBuilder("flag").version(50).build();
        Flag flagHighVersion = new FlagBuilder("flag").version(100).build();

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
