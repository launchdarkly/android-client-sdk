package com.launchdarkly.sdk.android;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * The on-disk shape of an event log.
 * <p>
 * A log opens with a magic and a format version, and holds a sequence of frames:
 * <pre>
 *     +- 4 bytes -+- 2 bytes -+     +- 2 bytes -+- 4 bytes -+- 4 bytes -+- n bytes -+
 *     |   LDEV    |  version  | ... |   type    |  size (n) |   CRC-32  |  payload  |
 *     +-----------+-----------+     +-----------+-----------+-----------+-----------+
 * </pre>
 * The size prefix is what makes a log recoverable: a reader can tell a frame the writer finished
 * from one it did not, without having to parse the event to find where it ends. The checksum, over
 * the type, the size and the payload, is what tells a frame the writer finished from one the storage
 * has since damaged; a reader treats either the same way. The version is what makes an upgrade safe
 * - a log this version does not understand is discarded rather than misread - and the frame type
 * leaves room for a later version to write something new into a log this one still reads.
 * <p>
 * The format is byte-for-byte the one the iOS SDK writes, so the two can be reasoned about, and
 * debugged, as one thing.
 */
final class EventLogFormat {
    /** Bump this whenever the framing or the meaning of a frame changes. */
    static final int VERSION = 1;
    static final byte[] MAGIC = "LDEV".getBytes(StandardCharsets.US_ASCII);
    static final int FILE_HEADER_SIZE = 6;
    static final int FRAME_HEADER_SIZE = 10;
    /** The only frame type written today; a reader skips a frame whose type it does not know. */
    static final int EVENT_FRAME = 1;
    /** A ceiling on a single frame, so a corrupt size cannot make recovery allocate wildly. */
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
        int length = serializedEvent.length;
        byte[] header = new byte[FRAME_HEADER_SIZE];
        header[0] = (byte) (EVENT_FRAME >> 8);
        header[1] = (byte) EVENT_FRAME;
        writeInt32(header, 2, length);
        CRC32 checksum = new CRC32();
        checksum.update(header, 0, 6);
        checksum.update(serializedEvent, 0, length);
        writeInt32(header, 6, (int) checksum.getValue());
        target.write(header, 0, FRAME_HEADER_SIZE);
        target.write(serializedEvent, 0, length);
    }

    /**
     * @return whether the log holds frames that cannot be read back: torn, or failing their
     *   checksum; false for a log this version does not read at all
     */
    static boolean isDamaged(byte[] log) {
        Frames frames = new Frames(log);
        if (!frames.isReadable()) {
            return false;
        }
        while (frames.next()) {
            // Walked to wherever it stops.
        }
        return !frames.reachedEnd();
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
        return assembleBody(log, Integer.MAX_VALUE);
    }

    /**
     * Assembles the request body out of the first {@code maxEvents} events in the log, and leaves out any
     * after them.
     *
     * @return the request body, or null if the log is unreadable or holds no events
     */
    static byte[] assembleBody(byte[] log, int maxEvents) {
        Frames frames = new Frames(log);
        if (!frames.isReadable()) {
            return null;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(log.length + 2);
        body.write('[');
        boolean isFirst = true;
        int events = 0;
        while (events < maxEvents && frames.next()) {
            if (frames.type() != EVENT_FRAME) {
                continue;
            }
            events++;
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
     * Walks the frames of a log, stopping at the first one that is not wholly there or fails its
     * checksum.
     * <p>
     * A process that died partway through a write leaves a torn frame at the end. Everything before it
     * is intact, so recovery keeps that and drops only the tail. A frame the storage damaged afterwards
     * ends the walk the same way, because its size can no longer be trusted to say where the next frame
     * starts.
     * <p>
     * A cursor rather than a callback, so that a caller counting or copying frames does it in a plain
     * loop. {@link #next()} is false from the first frame that is not wholly there or fails its
     * checksum, and always for a log that is not {@link #isReadable() readable}.
     */
    static final class Frames {
        private final byte[] log;
        private final boolean readable;
        private final CRC32 checksum = new CRC32();
        private int cursor = FILE_HEADER_SIZE;
        private boolean stopped;
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

        /**
         * Whether the walk ended at the end of the log, rather than at a frame that is torn or fails its
         * checksum. Only meaningful once {@link #next()} has returned false.
         */
        boolean reachedEnd() {
            return !stopped && cursor == log.length;
        }

        /** Moves to the next whole frame, if there is one. */
        boolean next() {
            if (!readable || stopped || cursor + FRAME_HEADER_SIZE > log.length) {
                return false;
            }
            int frameType = readUInt16(log, cursor);
            int frameLength = readInt32(log, cursor + 2);
            int frameStart = cursor + FRAME_HEADER_SIZE;
            if (frameLength <= 0 || frameLength > MAX_FRAME_SIZE || frameStart + frameLength > log.length) {
                stopped = true;
                return false;
            }
            checksum.reset();
            checksum.update(log, cursor, 6);
            checksum.update(log, frameStart, frameLength);
            if ((int) checksum.getValue() != readInt32(log, cursor + 6)) {
                stopped = true;
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

    static int readInt32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static void writeInt32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >> 24);
        bytes[offset + 1] = (byte) (value >> 16);
        bytes[offset + 2] = (byte) (value >> 8);
        bytes[offset + 3] = (byte) value;
    }
}
