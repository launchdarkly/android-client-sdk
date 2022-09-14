package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.AssertHelpers.assertJsonEqual;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ContextIndexTest {
    @Test
    public void emptyConstructor() {
        ContextIndex ci = new ContextIndex();
        assertNotNull(ci.data);
        assertEquals(0, ci.data.size());
    }

    @Test
    public void serialize() {
        ContextIndex ci = new ContextIndex()
                .updateTimestamp("user1", 1000)
                .updateTimestamp("user2", 2000);
        String json = ci.toJson();
        String expected = "[[\"user1\",1000],[\"user2\",2000]]";
        assertJsonEqual(expected, json);
    }

    @Test
    public void deserialize() {
        String json = "[[\"user1\",1000],[\"user2\",2000]]";
        ContextIndex ci = ContextIndex.fromJson(json);

        assertEquals(2, ci.data.size());
        assertEquals("user1", ci.data.get(0).contextId);
        assertEquals(1000, ci.data.get(0).timestamp);
        assertEquals("user2", ci.data.get(1).contextId);
        assertEquals(2000, ci.data.get(1).timestamp);
    }

    @Test
    public void deserializeMalformedJson() {
        deserializeMalformedJson("}");
        deserializeMalformedJson("[");
        deserializeMalformedJson("[[true,1000]]");
        deserializeMalformedJson("[[\"user1\",false]]");
        deserializeMalformedJson("[3]");
    }

    private void deserializeMalformedJson(String s) {
        try {
            ContextIndex.fromJson("}");
            fail("expected exception");
        } catch (IllegalArgumentException e) {}
    }

    @Test
    public void updateTimestampForExistingContext() {
        ContextIndex ci = new ContextIndex()
                .updateTimestamp("user1", 1000)
                .updateTimestamp("user2", 2000);

        ci = ci.updateTimestamp("user1", 2001);

        assertEquals(2, ci.data.size());
        assertEquals("user2", ci.data.get(0).contextId);
        assertEquals(2000, ci.data.get(0).timestamp);
        assertEquals("user1", ci.data.get(1).contextId);
        assertEquals(2001, ci.data.get(1).timestamp);
    }

    @Test
    public void pruneRemovesLeastRecentContexts() {
        ContextIndex ci = new ContextIndex()
                .updateTimestamp("user1", 1000)
                .updateTimestamp("user2", 2000)
                .updateTimestamp("user3", 1111) // deliberately out of order
                .updateTimestamp("user4", 3000)
                .updateTimestamp("user5", 4000);

        List<String> removed = new ArrayList<>();
        ci = ci.prune(3, removed);
        assertThat(removed, hasItems("user1", "user3"));

        assertEquals(3, ci.data.size());
        assertEquals("user2", ci.data.get(0).contextId);
        assertEquals(2000, ci.data.get(0).timestamp);
        assertEquals("user4", ci.data.get(1).contextId);
        assertEquals(3000, ci.data.get(1).timestamp);
        assertEquals("user5", ci.data.get(2).contextId);
        assertEquals(4000, ci.data.get(2).timestamp);
    }

    @Test
    public void pruneWhenLimitIsNotExceeded() {
        ContextIndex ci = new ContextIndex()
                .updateTimestamp("user1", 1000)
                .updateTimestamp("user2", 2000);

        List<String> removed = new ArrayList<>();
        ci = ci.prune(3, removed);
        assertEquals(0, removed.size());

        assertEquals(2, ci.data.size());
        assertEquals("user1", ci.data.get(0).contextId);
        assertEquals(1000, ci.data.get(0).timestamp);
        assertEquals("user2", ci.data.get(1).contextId);
        assertEquals(2000, ci.data.get(1).timestamp);
    }
}
