package com.launchdarkly.sdk.android;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The on-disk shape of an event log.
 * <p>
 * A log opens with a magic and a format version, and holds a sequence of frames:
 * <pre>
 *     +- 4 bytes -+- 2 bytes -+     +- 2 bytes -+-  4 bytes  -+- n bytes -+
 *     |   LDEV    |  version  | ... |   type    |  length (n) |  payload  |
 *     +-----------+-----------+     +-----------+-------------+-----------+
 * </pre>
 * The length prefix is what makes a log recoverable: a reader can tell a frame the writer finished
 * from one it did not, without having to parse the event to find where it ends. The version is what
 * makes an upgrade safe - a log this version does not understand is discarded rather than misread -
 * and the frame type leaves room for a later version to write something new into a log this one
 * still reads.
 * <p>
 * The format is byte-for-byte the one the iOS SDK writes, so the two can be reasoned about, and
 * debugged, as one thing.
 */
final class EventLogFormat {
    /** Bump this whenever the framing or the meaning of a frame changes. */
    static final int VERSION = 1;
    static final byte[] MAGIC = "LDEV".getBytes(StandardCharsets.US_ASCII);
    static final int FILE_HEADER_SIZE = 6;
    static final int FRAME_HEADER_SIZE = 6;
    /** The only frame type written today; a reader skips a frame whose type it does not know. */
    static final int EVENT_FRAME = 1;
    /** A ceiling on a single frame, so a corrupt length cannot make recovery allocate wildly. */
    static final int MAX_FRAME_SIZE = 8 * 1024 * 1024;

    private EventLogFormat() {
    }

    static byte[] fileHeader() {
        byte[] header = new byte[FILE_HEADER_SIZE];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[4] = (byte) (VERSION >> 8);
        header[5] = (byte) VERSION;
        return header;
    }

    static void writeFrame(ByteArrayOutputStream target, byte[] serializedEvent) {
        target.write((byte) (EVENT_FRAME >> 8));
        target.write((byte) EVENT_FRAME);
        int length = serializedEvent.length;
        target.write((byte) (length >> 24));
        target.write((byte) (length >> 16));
        target.write((byte) (length >> 8));
        target.write((byte) length);
        target.write(serializedEvent, 0, length);
    }

    /** Frames on their own, as a log: only for the in-memory fallback, which has no file. */
    static byte[] withFileHeader(byte[] frames) {
        byte[] log = new byte[FILE_HEADER_SIZE + frames.length];
        System.arraycopy(fileHeader(), 0, log, 0, FILE_HEADER_SIZE);
        System.arraycopy(frames, 0, log, FILE_HEADER_SIZE, frames.length);
        return log;
    }

    /**
     * @return how many event frames the log holds, or -1 if it is not a log this version reads
     */
    static int eventCount(byte[] log) {
        Frames frames = new Frames(log);
        if (!frames.isReadable()) {
            return -1;
        }
        int events = 0;
        while (frames.next()) {
            if (frames.type() == EVENT_FRAME) {
                events++;
            }
        }
        return events;
    }

    /**
     * Assembles the JSON array LaunchDarkly expects out of the frames, without parsing the events:
     * they were serialized on the way in and are shipped exactly as they were recorded.
     *
     * @return the request body, or null if the log is unreadable or holds no events
     */
    static byte[] assembleBody(byte[] log) {
        Frames frames = new Frames(log);
        if (!frames.isReadable()) {
            return null;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(log.length + 2);
        body.write('[');
        boolean isFirst = true;
        while (frames.next()) {
            if (frames.type() != EVENT_FRAME) {
                continue;
            }
            if (!isFirst) {
                body.write(',');
            }
            body.write(log, frames.start(), frames.length());
            isFirst = false;
        }
        if (isFirst) {
            return null;
        }
        body.write(']');
        return body.toByteArray();
    }

    /**
     * @return each event in the log, serialized as it will be sent; empty if the log is unreadable
     */
    static List<byte[]> eventPayloads(byte[] log) {
        List<byte[]> payloads = new ArrayList<>();
        Frames frames = new Frames(log);
        if (!frames.isReadable()) {
            return payloads;
        }
        while (frames.next()) {
            if (frames.type() == EVENT_FRAME) {
                payloads.add(Arrays.copyOfRange(log, frames.start(), frames.start() + frames.length()));
            }
        }
        return payloads;
    }

    /**
     * Walks the frames of a log, stopping at the first one that is not wholly there.
     * <p>
     * A process that died partway through a write leaves a torn frame at the end. Everything before it
     * is intact, so recovery keeps that and drops only the tail.
     * <p>
     * A cursor rather than a callback, so that a caller counting or copying frames does it in a plain
     * loop. {@link #next()} is false from the first frame that is not wholly there, and always for a log
     * that is not {@link #isReadable() readable}.
     */
    static final class Frames {
        private final byte[] log;
        private final boolean readable;
        private int cursor = FILE_HEADER_SIZE;
        private int type;
        private int start;
        private int length;

        Frames(byte[] log) {
            this.log = log;
            this.readable = isReadable(log);
        }

        /** Whether this is a log this version reads at all. */
        boolean isReadable() {
            return readable;
        }

        /** Moves to the next whole frame, if there is one. */
        boolean next() {
            if (!readable || cursor + FRAME_HEADER_SIZE > log.length) {
                return false;
            }
            int frameType = readUInt16(log, cursor);
            int frameLength = readInt32(log, cursor + 2);
            int frameStart = cursor + FRAME_HEADER_SIZE;
            if (frameLength <= 0 || frameLength > MAX_FRAME_SIZE || frameStart + frameLength > log.length) {
                cursor = log.length;
                return false;
            }
            type = frameType;
            start = frameStart;
            length = frameLength;
            cursor = frameStart + frameLength;
            return true;
        }

        int type() {
            return type;
        }

        /** Where the current frame's payload starts in the log. */
        int start() {
            return start;
        }

        int length() {
            return length;
        }

        private static boolean isReadable(byte[] log) {
            if (log == null || log.length < FILE_HEADER_SIZE) {
                return false;
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (log[i] != MAGIC[i]) {
                    return false;
                }
            }
            return readUInt16(log, MAGIC.length) == VERSION;
        }
    }

    private static int readUInt16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int readInt32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }
}
