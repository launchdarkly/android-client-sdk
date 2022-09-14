package com.launchdarkly.sdk.android;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.launchdarkly.sdk.internal.GsonHelpers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Used internally to track which contexts have flag data in the persistent store. This is only a
 * data container and does not do any persistence.
 * <p>
 * This exists because we can't assume that the persistent store mechanism has an "enumerate all
 * the keys that exist under such-and-such prefix" capability, so we need a table of contents at a
 * fixed location. The only information being tracked here is, for each flag data set that exists
 * in storage, 1. a context identifier (hashed fully-qualified key, as defined by FlagDataManager)
 * and 2. the millisecond timestamp when it was last accessed, to support the LRU eviction behavior
 * of FlagDataManager.
 * <p>
 * Instances are immutable; the update methods return a new instance.
 * <p>
 * The format of the JSON data is intentionally very minimal for efficiency: it is an array of
 * arrays, where the first value in each array is a context identifier and the second value is the
 * millisecond timestamp.
 */
final class ContextIndex {
    final List<IndexEntry> data;

    static class IndexEntry {
        final String contextId;
        final long timestamp;

        IndexEntry(String contextId, long timestamp) {
            this.contextId = contextId;
            this.timestamp = timestamp;
        }
    }

    ContextIndex() {
        this(new ArrayList<>());
    }

    ContextIndex(List<IndexEntry> data) {
        this.data = data == null ? new ArrayList<>() : data;
    }

    /**
     * Updates the timestamp for a context ID, or adds it if that context is not already in the list.
     * The new or updated entry is always added at the end; the list will be re-sorted if necessary
     * by prune().
     *
     * @param hashedContextId the context ID
     * @param timestamp the updated timestamp
     * @return a new ContextIndex instance with updated data
     */
    public ContextIndex updateTimestamp(String hashedContextId, long timestamp) {
        List<IndexEntry> newData = new ArrayList<>();
        for (IndexEntry e: data) {
            if (!e.contextId.equals(hashedContextId)) {
                newData.add(e);
            }
        }
        newData.add(new IndexEntry(hashedContextId, timestamp));
        return new ContextIndex(newData);
    }

    /**
     * Removes context IDs if necessary to stay within the configured limit, starting with the
     * oldest ones.
     *
     * @param maxContextsToRetain the maximum number of contexts that should be in the cache;
     *                            a negative number means no limit
     * @param removedIdsOut receives context IDs of any entries that were removed
     * @return a new ContextIndex instance with updated data.
     */
    public ContextIndex prune(int maxContextsToRetain, List<String> removedIdsOut) {
        if (data.size() <= maxContextsToRetain || maxContextsToRetain < 0) {
            return this;
        }
        // The data will normally already be in ascending timestamp order, in which case this sort
        // won't do anything, but this is just in case unsorted data somehow got persisted.
        IndexEntry[] items = data.toArray(new IndexEntry[data.size()]);
        Arrays.sort(items, new Comparator<IndexEntry>() {
            @Override
            public int compare(IndexEntry o1, IndexEntry o2) {
                return Long.compare(o1.timestamp, o2.timestamp);
            }
        });
        List<IndexEntry> newData = new ArrayList<>(Arrays.asList(items));
        int numDrop = newData.size() - maxContextsToRetain;
        for (int i = 0; i < numDrop; i++) {
            removedIdsOut.add(newData.get(0).contextId);
            newData.remove(0);
        }
        return new ContextIndex(newData);
    }

    public static ContextIndex fromJson(String json) {
        List<IndexEntry> out = new ArrayList<>();
        JsonReader r = new JsonReader(new StringReader(json));
        try {
            r.beginArray();
            while (r.hasNext()) {
                r.beginArray();
                if (r.hasNext()) {
                    String contextId = r.nextString();
                    if (r.hasNext()) {
                        long timestamp = r.nextLong();
                        out.add(new IndexEntry(contextId, timestamp));
                    }
                }
                while (r.hasNext()) {}
                r.endArray();
            }
            r.endArray();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        return new ContextIndex(out);
    }

    public String toJson() {
        StringWriter sw = new StringWriter();
        try {
            JsonWriter w = new JsonWriter(sw);
            w.beginArray();
            for (IndexEntry e: data) {
                w.beginArray();
                w.value(e.contextId);
                w.value(e.timestamp);
                w.endArray();
            }
            w.endArray();
            w.flush();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        return sw.toString();
    }
}
