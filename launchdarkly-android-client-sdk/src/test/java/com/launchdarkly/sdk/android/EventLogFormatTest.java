package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.LDValue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The frame checksum: a frame the storage changed after it was written ends the walk, as a torn tail does.
 */
public class EventLogFormatTest {
    private static byte[] event(String key) {
        return ("{\"kind\":\"custom\",\"key\":\"" + key + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] log(String... keys) {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (String key : keys) {
            EventLogFormat.writeFrame(frames, event(key));
        }
        return EventLogFormat.withFileHeader(frames.toByteArray());
    }

    /** Where each frame of a log written by this version starts. */
    private static List<Integer> frameOffsets(byte[] log) {
        List<Integer> offsets = new ArrayList<>();
        int cursor = EventLogFormat.FILE_HEADER_SIZE;
        while (cursor + EventLogFormat.FRAME_HEADER_SIZE <= log.length) {
            offsets.add(cursor);
            cursor += EventLogFormat.FRAME_HEADER_SIZE + EventLogFormat.readInt32(log, cursor + 2);
        }
        return offsets;
    }

    private static List<String> keys(byte[] log) {
        List<String> keys = new ArrayList<>();
        for (byte[] payload : EventLogFormat.eventPayloads(log)) {
            keys.add(LDValue.parse(new String(payload, StandardCharsets.UTF_8)).get("key").stringValue());
        }
        return keys;
    }

    @Test
    public void writesTheFrameTheIosSdkWrites() {
        // The checksum is zlib's CRC-32, so these bytes are what the iOS SDK writes for the same event too.
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        EventLogFormat.writeFrame(frame, "{\"kind\":\"custom\",\"key\":\"k\"}".getBytes(StandardCharsets.UTF_8));

        byte[] header = Arrays.copyOf(frame.toByteArray(), EventLogFormat.FRAME_HEADER_SIZE);
        assertArrayEquals(new byte[] {0x00, 0x01, 0x00, 0x00, 0x00, 0x1B, (byte) 0xCB, 0x2B, 0x5E, 0x6F}, header);
    }

    @Test
    public void readsBackEveryFrameItWrote() {
        byte[] log = log("event-0", "event-1", "event-2");

        assertEquals(Arrays.asList("event-0", "event-1", "event-2"), keys(log));
        assertFalse(EventLogFormat.isDamaged(log));
    }

    @Test
    public void keepsEveryEventBeforeAPayloadWithAFlippedBit() {
        byte[] log = log("event-0", "event-1", "event-2");
        int second = frameOffsets(log).get(1);
        log[second + EventLogFormat.FRAME_HEADER_SIZE + 3] ^= 0x04;

        assertEquals(Arrays.asList("event-0"), keys(log));
        assertEquals(1, EventLogFormat.eventCount(log));
        assertTrue(EventLogFormat.isDamaged(log));
    }

    @Test
    public void keepsEveryEventBeforeAZeroFilledPayload() {
        byte[] log = log("event-0", "event-1", "event-2");
        int second = frameOffsets(log).get(1);
        int size = EventLogFormat.readInt32(log, second + 2);
        Arrays.fill(log, second + EventLogFormat.FRAME_HEADER_SIZE,
                second + EventLogFormat.FRAME_HEADER_SIZE + size, (byte) 0);

        assertEquals(Arrays.asList("event-0"), keys(log));
    }

    @Test
    public void keepsEveryEventBeforeASizeThatStillFitsInTheFile() {
        // A size that is merely wrong rather than impossible passes every bound a torn tail is caught by,
        // and would have the reader start the next frame partway through this one's payload.
        byte[] log = log("event-0", "event-1", "event-2");
        int second = frameOffsets(log).get(1);
        log[second + 5] -= 1;

        assertEquals(Arrays.asList("event-0"), keys(log));
    }

    @Test
    public void assemblesNoMoreEventsThanItIsAskedFor() {
        byte[] log = log("event-0", "event-1", "event-2");

        assertEquals(LDValue.parse("[" + new String(event("event-0"), StandardCharsets.UTF_8) + ","
                        + new String(event("event-1"), StandardCharsets.UTF_8) + "]"),
                LDValue.parse(new String(EventLogFormat.assembleBody(log, 2), StandardCharsets.UTF_8)));
        assertEquals(null, EventLogFormat.assembleBody(log, 0));
    }

    @Test
    public void hasNoBodyWhenTheFirstFrameIsDamaged() {
        byte[] log = log("damaged");
        log[EventLogFormat.FILE_HEADER_SIZE + EventLogFormat.FRAME_HEADER_SIZE] ^= 0x01;

        assertEquals(null, EventLogFormat.assembleBody(log));
        assertEquals(0, EventLogFormat.eventCount(log));
        assertTrue(EventLogFormat.isDamaged(log));
    }

    @Test
    public void refusesAVersionItDoesNotKnow() {
        byte[] log = log("event-0");
        log[5] = 2;

        assertEquals(-1, EventLogFormat.eventCount(log));
        assertFalse(EventLogFormat.isDamaged(log));
    }
}
