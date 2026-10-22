package com.launchdarkly.sdk.android;

import androidx.annotation.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
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
    static final byte[] MAGIC = {'L', 'D', 'E', 'V'};
    static final int FILE_HEADER_SIZE = 6;
    static final int FRAME_HEADER_SIZE = 10;
    /** The only frame type written today; a reader skips a frame whose type it does not know. */
    static final int EVENT_FRAME = 1;
    /**
     * The most a batch may hold, file header included, so that no request it becomes is larger than the
     * events service accepts.
     */
    static final int MAX_BATCH_SIZE = 2 * 1024 * 1024;
    /**
     * A ceiling on a single frame's payload: the most that still fits in a batch on its own. It is also
     * what keeps a corrupt size from making recovery allocate wildly.
     */
    static final int MAX_FRAME_SIZE = MAX_BATCH_SIZE - FILE_HEADER_SIZE - FRAME_HEADER_SIZE;

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
        while (frames.hasNext()) {
            frames.next();
        }
        return !frames.reachedEnd();
    }

    /** Frames on their own, as a log: only for the in-memory fallback, which has no file. */
    static byte[] withFileHeader(byte[] frames) {
        return withFileHeader(frames, 0, frames.length);
    }

    /** The frames from {@code start} to {@code end}, as a log of their own. */
    static byte[] withFileHeader(byte[] frames, int start, int end) {
        byte[] log = new byte[FILE_HEADER_SIZE + end - start];
        System.arraycopy(fileHeader(), 0, log, 0, FILE_HEADER_SIZE);
        System.arraycopy(frames, start, log, FILE_HEADER_SIZE, end - start);
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
        while (frames.hasNext()) {
            if (frames.next().type == EVENT_FRAME) {
                events++;
            }
        }
        return events;
    }

    /**
     * A JSON array of events at the start of an array that may run on past it, so that it can be sent
     * from where it was assembled rather than copied out first.
     */
    static final class Payload {
        final byte[] bytes;
        final int length;
        /**
         * How many events made it in: fewer than the batch holds where a frame after the first is torn or
         * fails its checksum, since assembly stops there.
         */
        final int eventCount;

        Payload(byte[] bytes, int length, int eventCount) {
            this.bytes = bytes;
            this.length = length;
            this.eventCount = eventCount;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(bytes, length);
        }
    }

    /**
     * Turns the log into the JSON array LaunchDarkly expects, in the log's own array, without parsing
     * the events: they were serialized on the way in and are shipped exactly as they were recorded.
     * <p>
     * The array always has room: the file header becomes the '[', and each frame header the ',' or ']'
     * after its payload. Each payload moves towards the start only once its frame has been checked, and
     * never past where that frame began, so no frame still to be checked is written over.
     *
     * @return the payload of the first {@code maxEvents} events, leaving out any after them; or null if the
     *   log is unreadable or holds no events, in which case the log is left as it was
     */
    static Payload assemblePayloadInPlace(byte[] log, int maxEvents) {
        Frames frames = new Frames(log);
        if (!frames.isReadable()) {
            return null;
        }
        int events = 0;
        int cursor = 1;
        while (events < maxEvents && frames.hasNext()) {
            Frame frame = frames.next();
            if (frame.type != EVENT_FRAME) {
                continue;
            }
            System.arraycopy(log, frame.start, log, cursor, frame.length);
            cursor += frame.length;
            log[cursor++] = ',';
            events++;
        }
        if (events == 0) {
            return null;
        }
        log[0] = '[';
        log[cursor - 1] = ']';
        return new Payload(log, cursor, events);
    }

    /**
     * {@link #assemblePayloadInPlace} into an array of its own, leaving the log as it was.
     *
     * @return the payload, or null if the log is unreadable or holds no events
     */
    @VisibleForTesting
    static byte[] assembleBody(byte[] log) {
        return assembleBody(log, Integer.MAX_VALUE);
    }

    @VisibleForTesting
    static byte[] assembleBody(byte[] log, int maxEvents) {
        Payload payload = assemblePayloadInPlace(log.clone(), maxEvents);
        return payload == null ? null : payload.toByteArray();
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
        while (frames.hasNext()) {
            Frame frame = frames.next();
            if (frame.type == EVENT_FRAME) {
                payloads.add(Arrays.copyOfRange(log, frame.start, frame.start + frame.length));
            }
        }
        return payloads;
    }

    /** One whole frame of a log: its type, and where its payload is in the log. */
    static final class Frame {
        final int type;
        final int start;
        final int length;

        Frame(int type, int start, int length) {
            this.type = type;
            this.start = start;
            this.length = length;
        }
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
     * There is no next frame from the first one that is not wholly there or fails its checksum, and none
     * at all in a log that is not {@link #isReadable() readable}. {@link #hasNext()} is what checks the
     * next frame, so a caller may write anywhere before where the last frame it was handed ends.
     */
    static final class Frames implements Iterator<Frame> {
        private final byte[] log;
        private final boolean readable;
        private final CRC32 checksum = new CRC32();
        private int cursor = FILE_HEADER_SIZE;
        private boolean stopped;
        private Frame checked;

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
         * checksum. Only meaningful once {@link #hasNext()} has returned false.
         */
        boolean reachedEnd() {
            return !stopped && cursor == log.length;
        }

        @Override
        public boolean hasNext() {
            if (checked != null) {
                return true;
            }
            if (!readable || stopped || cursor + FRAME_HEADER_SIZE > log.length) {
                return false;
            }
            int type = readUInt16(log, cursor);
            int length = readInt32(log, cursor + 2);
            int start = cursor + FRAME_HEADER_SIZE;
            if (length <= 0 || length > MAX_FRAME_SIZE || start + length > log.length) {
                stopped = true;
                return false;
            }
            checksum.reset();
            checksum.update(log, cursor, 6);
            checksum.update(log, start, length);
            if ((int) checksum.getValue() != readInt32(log, cursor + 6)) {
                stopped = true;
                return false;
            }
            checked = new Frame(type, start, length);
            cursor = start + length;
            return true;
        }

        @Override
        public Frame next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Frame frame = checked;
            checked = null;
            return frame;
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

    /** The size, header included, of the frame at {@code start} in frames this process wrote. */
    static int frameSize(byte[] frames, int start) {
        return FRAME_HEADER_SIZE + readInt32(frames, start + 2);
    }

    /**
     * Where a run of whole frames from {@code start}, in frames this process wrote, ends if it is to take
     * no more than {@code room} bytes. It takes the first frame whatever its size.
     */
    static int endOfFramesFitting(byte[] frames, int start, long room) {
        int end = start + frameSize(frames, start);
        while (end < frames.length && end + frameSize(frames, end) - start <= room) {
            end += frameSize(frames, end);
        }
        return end;
    }

    /** How many frames there are between two frame boundaries in frames this process wrote. */
    static int frameCount(byte[] frames, int start, int end) {
        int count = 0;
        for (int cursor = start; cursor < end; cursor += frameSize(frames, cursor)) {
            count++;
        }
        return count;
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
