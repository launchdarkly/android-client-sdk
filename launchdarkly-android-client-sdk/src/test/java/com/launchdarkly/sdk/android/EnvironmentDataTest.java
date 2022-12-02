package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.AssertHelpers.assertJsonEqual;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.DataModel.Flag;

import org.junit.Test;

public class EnvironmentDataTest {
    @Test
    public void emptyConstructorHasNonNullEmptyFlags() {
        EnvironmentData data = new EnvironmentData();
        assertNotNull(data.values());
        assertEquals(0, data.values().size());
    }

    @Test
    public void getFlag() {
        Flag flag1 = new FlagBuilder("flag1").version(100).value(true).build();
        EnvironmentData data = new DataSetBuilder().add(flag1).build();
        assertSame(flag1, data.getFlag(flag1.getKey()));
    }

    @Test
    public void getFlagNotFound() {
        Flag flag1 = new FlagBuilder("flag1").version(100).value(true).build();
        EnvironmentData data = new DataSetBuilder().add(flag1).build();
        assertNull(data.getFlag("flag2"));
    }

    @Test
    public void getAll() {
        Flag flag1 = new FlagBuilder("flag1").version(100).value(true).build();
        Flag flag2 = new FlagBuilder("flag2").version(200).value(false).build();
        EnvironmentData data = new DataSetBuilder().add(flag1).add(flag2).build();
        assertThat(data.values(), hasItems(flag1, flag2));
        assertEquals(2, data.values().size());
    }

    @Test
    public void toJson() {
        Flag flag1 = new FlagBuilder("flag1").version(100).flagVersion(222).value(true).variation(1)
                .reason(EvaluationReason.off())
                .trackEvents(true)
                .trackReason(true)
                .debugEventsUntilDate(1000L)
                .build();
        Flag flag2 = new FlagBuilder("flag2").version(200).value(false).build();
        EnvironmentData data = new DataSetBuilder().add(flag1).add(flag2).build();
        String json = data.toJson();

        String expectedJson = "{" +
                "\"flag1\":{\"key\":\"flag1\",\"version\":100,\"flagVersion\":222,\"value\":true," +
                "\"variation\":1,\"reason\":{\"kind\":\"OFF\"},\"trackEvents\":true," +
                "\"trackReason\":true,\"debugEventsUntilDate\":1000}," +
                "\"flag2\":{\"key\":\"flag2\",\"version\":200,\"value\":false}" +
                "}";
        assertJsonEqual(expectedJson, json);
    }

    @Test
    public void fromJson() throws Exception {
        String json = "{" +
                "\"flag1\":{\"key\":\"flag1\",\"version\":100,\"flagVersion\":222,\"value\":true," +
                "\"variation\":1,\"reason\":{\"kind\":\"OFF\"},\"trackEvents\":true," +
                "\"trackReason\":true,\"debugEventsUntilDate\":1000}," +
                "\"flag2\":{\"key\":\"flag2\",\"version\":200,\"value\":false}" +
                "}";
        EnvironmentData data = EnvironmentData.fromJson(json);

        assertEquals(2, data.values().size());

        Flag flag1 = data.getFlag("flag1");
        assertNotNull(flag1);
        assertEquals("flag1", flag1.getKey());
        assertEquals(100, flag1.getVersion());
        assertEquals(Integer.valueOf(222), flag1.getFlagVersion());
        assertEquals(LDValue.of(true), flag1.getValue());
        assertEquals(Integer.valueOf(1), flag1.getVariation());
        assertTrue(flag1.isTrackEvents());
        assertTrue(flag1.isTrackReason());
        assertEquals(Long.valueOf(1000), flag1.getDebugEventsUntilDate());
        assertFalse(flag1.isDeleted());

        Flag flag2 = data.getFlag("flag2");
        assertNotNull(flag2);
        assertEquals("flag2", flag2.getKey());
        assertEquals(200, flag2.getVersion());
        assertNull(flag2.getFlagVersion());
        assertEquals(LDValue.of(false), flag2.getValue());
        assertNull(flag2.getVariation());
        assertFalse(flag2.isTrackEvents());
        assertFalse(flag2.isTrackReason());
        assertNull(flag2.getDebugEventsUntilDate());
        assertFalse(flag2.isDeleted());
    }

    @Test
    public void fromJsonWithMissingKeys() throws Exception {
        // This edge case should not be encountered in real LD usage, but can happen in tests and in
        // any case it's best for us to handle it gracefully.
        String json = "{" +
                "\"flag1\":{\"version\":100,\"flagVersion\":222,\"value\":true," +
                "\"variation\":1,\"reason\":{\"kind\":\"OFF\"},\"trackEvents\":true," +
                "\"trackReason\":true,\"debugEventsUntilDate\":1000}," +
                "\"flag2\":{\"version\":200,\"value\":false}" +
                "}";
        EnvironmentData data = EnvironmentData.fromJson(json);

        assertEquals(2, data.values().size());

        Flag flag1 = data.getFlag("flag1");
        assertNotNull(flag1);
        assertEquals("flag1", flag1.getKey());
        assertEquals(100, flag1.getVersion());
        assertEquals(Integer.valueOf(222), flag1.getFlagVersion());
        assertEquals(LDValue.of(true), flag1.getValue());
        assertEquals(Integer.valueOf(1), flag1.getVariation());
        assertTrue(flag1.isTrackEvents());
        assertTrue(flag1.isTrackReason());
        assertEquals(Long.valueOf(1000), flag1.getDebugEventsUntilDate());
        assertFalse(flag1.isDeleted());

        Flag flag2 = data.getFlag("flag2");
        assertNotNull(flag2);
        assertEquals("flag2", flag2.getKey());
        assertEquals(200, flag2.getVersion());
        assertNull(flag2.getFlagVersion());
        assertEquals(LDValue.of(false), flag2.getValue());
        assertNull(flag2.getVariation());
        assertFalse(flag2.isTrackEvents());
        assertFalse(flag2.isTrackReason());
        assertNull(flag2.getDebugEventsUntilDate());
        assertFalse(flag2.isDeleted());
    }
}
