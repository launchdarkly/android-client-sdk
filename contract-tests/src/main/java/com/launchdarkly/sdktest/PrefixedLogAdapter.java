package com.launchdarkly.sdktest;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogLevel;

/**
 * This wraps the underlying logging adapter to use the logger name as a prefix for the message
 * text, rather than passing it through as a tag, because Android logging tags have a length
 * limit so we can't put the whole test name there.
 */
public class PrefixedLogAdapter implements LDLogAdapter {
    private final LDLogAdapter wrappedAdapter;
    private final String singleLoggerName;

    public PrefixedLogAdapter(LDLogAdapter wrappedAdapter, String singleLoggerName) {
        this.wrappedAdapter = wrappedAdapter;
        this.singleLoggerName = singleLoggerName;
    }

    @Override
    public Channel newChannel(String name) {
        return new ChannelImpl(wrappedAdapter.newChannel(singleLoggerName),
                "[" + name + "] ");
    }

    private static final class ChannelImpl implements Channel {
        private final Channel wrappedChannel;
        private final String prefix;

        public ChannelImpl(Channel wrappedChannel, String prefix) {
            this.wrappedChannel = wrappedChannel;
            this.prefix = prefix;
        }

        @Override
        public boolean isEnabled(LDLogLevel level) {
            return wrappedChannel.isEnabled(level);
        }

        @Override
        public void log(LDLogLevel level, Object message) {
            wrappedChannel.log(level, prefix + message.toString());
        }

        @Override
        public void log(LDLogLevel level, String format, Object param) {
            wrappedChannel.log(level, prefix + format, param);
        }

        @Override
        public void log(LDLogLevel level, String format, Object param1, Object param2) {
            wrappedChannel.log(level, prefix + format, param1, param2);
        }

        @Override
        public void log(LDLogLevel level, String format, Object... params) {
            wrappedChannel.log(level, prefix + format, params);
        }
    }
}
