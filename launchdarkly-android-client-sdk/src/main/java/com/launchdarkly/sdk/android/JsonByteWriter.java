package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Appends compact JSON, already UTF-8 encoded, to a byte array. Callers are responsible for well-formed
 * nesting: every {@link #name} is followed by exactly one value, and every container that is begun is
 * ended.
 * <p>
 * The Android counterpart of the Apple SDK's {@code JSONWriter}. What it replaces is Gson's
 * {@code JsonWriter} behind a {@code BufferedWriter}, an {@code OutputStreamWriter} and a
 * {@code ByteArrayOutputStream}: four layers, a UTF-16 buffer and a charset encoder between an event and
 * its bytes, and two copies after it. Here a string is escaped and encoded in one pass straight into the
 * array that becomes the frame.
 * <p>
 * <b>The output is Gson's, byte for byte.</b> That is what {@code OutboundEventBufferSerializationTest}
 * holds it to, and it is why this has two escaping modes. {@code EventOutputFormatter} writes its own
 * fields through a plain {@code JsonWriter}, but hands every {@link LDValue} and {@link EvaluationReason}
 * to {@code Gson.toJson}, which turns on HTML escaping for the length of the call -- including the field
 * name that was pending when it was called. {@link #nameAndValue} and {@link #nameAndReason} reproduce
 * exactly that span.
 * <p>
 * Not thread-safe. One writer serves one run of events on one thread.
 */
final class JsonByteWriter {
    private static final byte[] TRUE = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE = {'f', 'a', 'l', 's', 'e'};
    private static final byte[] NULL = {'n', 'u', 'l', 'l'};
    private static final byte[] LONG_MIN_VALUE =
            Long.toString(Long.MIN_VALUE).getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEX_DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    private byte[] bytes;
    private int size;

    /**
     * Whether the next value written needs a comma in front of it.
     * <p>
     * Writing a name clears it, so the value that follows the colon is not preceded by one; writing a
     * value or closing a container sets it, so whatever comes next is.
     */
    private boolean needsSeparator;

    /** Whether {@code <}, {@code >}, {@code &}, {@code =} and {@code '} are escaped, as Gson's HTML-safe mode does. */
    private boolean htmlSafe;

    JsonByteWriter(int initialCapacity) {
        bytes = new byte[Math.max(initialCapacity, 16)];
    }

    void reset() {
        size = 0;
        needsSeparator = false;
        htmlSafe = false;
    }

    int size() {
        return size;
    }

    /** A copy of everything written since the last {@link #reset}, independent of this writer's buffer. */
    byte[] toByteArray() {
        return Arrays.copyOf(bytes, size);
    }

    /** A copy of the bytes written since {@code offset}, independent of this writer's buffer. */
    byte[] bytesFrom(int offset) {
        return Arrays.copyOfRange(bytes, offset, size);
    }

    /** Appends JSON that was produced earlier, in the position a value would go. */
    void writeRaw(byte[] raw) {
        separate();
        ensure(raw.length);
        System.arraycopy(raw, 0, bytes, size, raw.length);
        size += raw.length;
        needsSeparator = true;
    }

    // Structure

    JsonByteWriter beginObject() {
        separate();
        append('{');
        needsSeparator = false;
        return this;
    }

    JsonByteWriter endObject() {
        append('}');
        needsSeparator = true;
        return this;
    }

    JsonByteWriter beginArray() {
        separate();
        append('[');
        needsSeparator = false;
        return this;
    }

    JsonByteWriter endArray() {
        append(']');
        needsSeparator = true;
        return this;
    }

    JsonByteWriter name(String name) {
        separate();
        writeQuoted(name);
        append(':');
        needsSeparator = false;
        return this;
    }

    // Values

    JsonByteWriter value(String value) {
        if (value == null) {
            return nullValue();
        }
        separate();
        writeQuoted(value);
        needsSeparator = true;
        return this;
    }

    JsonByteWriter value(boolean value) {
        separate();
        append(value ? TRUE : FALSE);
        needsSeparator = true;
        return this;
    }

    JsonByteWriter value(long value) {
        separate();
        writeLong(value);
        needsSeparator = true;
        return this;
    }

    /**
     * As {@code JsonWriter.value(Number)} writes a {@code Double} on a strict writer, which is what the
     * formatter's own fields are: {@link Double#toString}, and a non-finite value refused.
     */
    JsonByteWriter value(Double value) {
        if (value == null) {
            return nullValue();
        }
        if (value.isNaN() || value.isInfinite()) {
            throw new IllegalArgumentException("Numeric values must be finite, but was " + value);
        }
        separate();
        appendAscii(Double.toString(value));
        needsSeparator = true;
        return this;
    }

    JsonByteWriter nullValue() {
        separate();
        append(NULL);
        needsSeparator = true;
        return this;
    }

    /**
     * Writes a name and an {@link LDValue} as {@code jw.name(name); gson.toJson(value, LDValue.class, jw)}
     * does: nothing at all for a null value, and both halves HTML-escaped.
     */
    JsonByteWriter nameAndValue(String name, LDValue value) {
        if (value == null || value.isNull()) {
            return this;
        }
        boolean wasHtmlSafe = htmlSafe;
        htmlSafe = true;
        try {
            name(name);
            writeLDValue(value);
        } finally {
            htmlSafe = wasHtmlSafe;
        }
        return this;
    }

    /** Writes a name and a reason as {@code jw.name(name); gson.toJson(reason, EvaluationReason.class, jw)} does. */
    JsonByteWriter nameAndReason(String name, EvaluationReason reason) {
        if (reason == null) {
            return this;
        }
        boolean wasHtmlSafe = htmlSafe;
        htmlSafe = true;
        try {
            name(name);
            beginObject();
            name("kind").value(reason.getKind().name());
            switch (reason.getKind()) {
                case RULE_MATCH:
                    name("ruleIndex").value(reason.getRuleIndex());
                    if (reason.getRuleId() != null) {
                        name("ruleId").value(reason.getRuleId());
                    }
                    if (reason.isInExperiment()) {
                        name("inExperiment").value(true);
                    }
                    break;
                case FALLTHROUGH:
                    if (reason.isInExperiment()) {
                        name("inExperiment").value(true);
                    }
                    break;
                case PREREQUISITE_FAILED:
                    // Gson.toJson turns serializeNulls off, so a null key drops the name along with it.
                    if (reason.getPrerequisiteKey() != null) {
                        name("prerequisiteKey").value(reason.getPrerequisiteKey());
                    }
                    break;
                case ERROR:
                    name("errorKind").value(reason.getErrorKind().name());
                    break;
                default:
                    break;
            }
            if (reason.getBigSegmentsStatus() != null) {
                name("bigSegmentsStatus").value(reason.getBigSegmentsStatus().name());
            }
            endObject();
        } finally {
            htmlSafe = wasHtmlSafe;
        }
        return this;
    }

    /**
     * {@code LDValue}'s own serializer, under {@code Gson.toJson}: lenient, so a non-finite number is
     * written as Java spells it, and not serializing nulls, so an object member whose value is null is
     * left out, name and all. A null in an array is still written. {@code LDValueObject.write} itself
     * emits the null; it is the writer's {@code serializeNulls}, which {@code Gson.toJson} turns off,
     * that drops it.
     */
    private void writeLDValue(LDValue value) {
        switch (value.getType()) {
            case NULL:
                nullValue();
                break;
            case BOOLEAN:
                value(value.booleanValue());
                break;
            case NUMBER:
                if (value.isInt()) {
                    value(value.intValue());
                } else {
                    separate();
                    appendAscii(Double.toString(value.doubleValue()));
                    needsSeparator = true;
                }
                break;
            case STRING:
                value(value.stringValue());
                break;
            case ARRAY:
                beginArray();
                for (LDValue element : value.values()) {
                    writeLDValue(element);
                }
                endArray();
                break;
            case OBJECT:
                beginObject();
                for (String key : value.keys()) {
                    LDValue member = value.get(key);
                    if (member.isNull()) {
                        continue;
                    }
                    name(key);
                    writeLDValue(member);
                }
                endObject();
                break;
        }
    }

    // Primitives

    private void separate() {
        if (needsSeparator) {
            append(',');
        }
    }

    private void append(char ascii) {
        ensure(1);
        bytes[size++] = (byte) ascii;
    }

    private void append(byte[] literal) {
        ensure(literal.length);
        System.arraycopy(literal, 0, bytes, size, literal.length);
        size += literal.length;
    }

    private void appendAscii(String ascii) {
        int length = ascii.length();
        ensure(length);
        for (int i = 0; i < length; i++) {
            bytes[size++] = (byte) ascii.charAt(i);
        }
    }

    /** Digits are written backwards and then flipped in place, so no scratch buffer is allocated per number. */
    private void writeLong(long value) {
        if (value == Long.MIN_VALUE) {
            append(LONG_MIN_VALUE);
            return;
        }
        ensure(20);
        if (value < 0) {
            bytes[size++] = '-';
            value = -value;
        }
        if (value == 0) {
            bytes[size++] = '0';
            return;
        }
        int start = size;
        while (value > 0) {
            bytes[size++] = (byte) ('0' + (int) (value % 10));
            value /= 10;
        }
        for (int lower = start, upper = size - 1; lower < upper; lower++, upper--) {
            byte swap = bytes[lower];
            bytes[lower] = bytes[upper];
            bytes[upper] = swap;
        }
    }

    /**
     * Escapes what Gson's {@code JsonWriter} escapes and encodes the rest as {@code OutputStreamWriter}
     * would: every control character, {@code "} and {@code \}; U+2028 and U+2029; the five HTML
     * characters in HTML-safe mode; and a surrogate without its other half written as {@code ?}, the
     * UTF-8 encoder's replacement.
     */
    private void writeQuoted(String value) {
        int length = value.length();
        // Six bytes is the most any one char can take: an escape such as \u001f.
        ensure(length * 6 + 2);
        byte[] b = bytes;
        int n = size;
        b[n++] = '"';
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                if (c >= 0x20 && c != '"' && c != '\\'
                        && !(htmlSafe && (c == '<' || c == '>' || c == '&' || c == '=' || c == '\''))) {
                    b[n++] = (byte) c;
                } else {
                    n = writeEscape(b, n, c);
                }
            } else if (c < 0x800) {
                b[n++] = (byte) (0xC0 | (c >> 6));
                b[n++] = (byte) (0x80 | (c & 0x3F));
            } else if (Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                    int codePoint = Character.toCodePoint(c, value.charAt(++i));
                    b[n++] = (byte) (0xF0 | (codePoint >> 18));
                    b[n++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                    b[n++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                    b[n++] = (byte) (0x80 | (codePoint & 0x3F));
                } else {
                    b[n++] = '?';
                }
            } else if (c == '\u2028' || c == '\u2029') {
                n = writeUnicodeEscape(b, n, c);
            } else {
                b[n++] = (byte) (0xE0 | (c >> 12));
                b[n++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                b[n++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        b[n++] = '"';
        size = n;
    }

    private static int writeEscape(byte[] b, int n, char c) {
        switch (c) {
            case '"': b[n++] = '\\'; b[n++] = '"'; return n;
            case '\\': b[n++] = '\\'; b[n++] = '\\'; return n;
            case '\t': b[n++] = '\\'; b[n++] = 't'; return n;
            case '\b': b[n++] = '\\'; b[n++] = 'b'; return n;
            case '\n': b[n++] = '\\'; b[n++] = 'n'; return n;
            case '\r': b[n++] = '\\'; b[n++] = 'r'; return n;
            case '\f': b[n++] = '\\'; b[n++] = 'f'; return n;
            default: return writeUnicodeEscape(b, n, c);
        }
    }

    private static int writeUnicodeEscape(byte[] b, int n, char c) {
        b[n++] = '\\';
        b[n++] = 'u';
        b[n++] = HEX_DIGITS[(c >> 12) & 0xF];
        b[n++] = HEX_DIGITS[(c >> 8) & 0xF];
        b[n++] = HEX_DIGITS[(c >> 4) & 0xF];
        b[n++] = HEX_DIGITS[c & 0xF];
        return n;
    }

    private void ensure(int additional) {
        int required = size + additional;
        if (required > bytes.length) {
            bytes = Arrays.copyOf(bytes, Math.max(required, bytes.length * 2));
        }
    }
}
