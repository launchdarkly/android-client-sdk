package com.launchdarkly.sdk.android;

import android.util.Log;
import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.SimpleFormat;

/**
 * Allows LaunchDarkly log output to be sent directly to the native Android Log API.
 * <p>
 * By default, the SDK sends logging to Timber. If you want to bypass Timber and use Android
 * logging directly instead, use this class with {@link LDConfig.Builder#logAdapter(LDLogAdapter)}:
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder()
 *         .logAdapter(LDAndroidLogging.adapter())
 *         .build();
 * </code></pre>
 * <p>
 * By default, debug-level logging is disabled and all other levels are enabled. To change this,
 * use {@link LDConfig.Builder#logLevel(LDLogLevel)}.
 * @since 3.2.0
 */
public abstract class LDAndroidLogging {

    /**
     * @return an {@link LDLogAdapter} for Android logging
     */
    public static LDLogAdapter adapter() {
        return new AdapterImpl(false);
    }

    static LDLogAdapter adapter(boolean overrideAndroidLogFilter) {
        // This method is exposed only for testing. Normally, it's important to respect the behavior
        // of Android's Log.isLoggable(), which allows debug output only if it's enabled by a system
        // property for that log tag. But in instrumented tests, we always want to see output for
        // all levels.
        return new AdapterImpl(overrideAndroidLogFilter);
    }

    static final class AdapterImpl implements LDLogAdapter {
        private final boolean overrideAndroidLogFilter;

        AdapterImpl(boolean overrideAndroidLogFilter) {
            this.overrideAndroidLogFilter = overrideAndroidLogFilter;
        }

        @Override
        public Channel newChannel(String name) {
            return new ChannelImpl(name);
        }

        private final class ChannelImpl extends ChannelImplBase {
            public ChannelImpl(String tag) {
                super(tag);
            }

            @Override
            public boolean isEnabled(LDLogLevel level) {
                return overrideAndroidLogFilter || Log.isLoggable(tag, toAndroidLogLevel(level));
            }

            private int toAndroidLogLevel(LDLogLevel level) {
                switch (level) {
                    case DEBUG:
                        return Log.DEBUG;
                    case INFO:
                        return Log.INFO;
                    case WARN:
                        return Log.WARN;
                    case ERROR:
                        return Log.ERROR;
                    default:
                        return Log.VERBOSE;
                }
            }

            @Override
            protected void logInternal(LDLogLevel level, String text) {
                switch (level) {
                    case DEBUG:
                        Log.d(tag, text);
                        break;
                    case INFO:
                        Log.i(tag, text);
                        break;
                    case WARN:
                        Log.w(tag, text);
                        break;
                    case ERROR:
                        Log.e(tag, text);
                        break;
                }
            }
        }
    }

    abstract static class ChannelImplBase implements LDLogAdapter.Channel {
        protected final String tag;

        public ChannelImplBase(String tag) {
            this.tag = tag;
        }

        protected abstract void logInternal(LDLogLevel level, String text);

        // To avoid unnecessary string computations for debug output, we don't want to
        // pre-format messages for disabled levels. We'll avoid that by checking if the
        // level is enabled first.

        @Override
        public void log(LDLogLevel level, Object message) {
            if (isEnabled(level)) {
                logInternal(level, message == null ? null : message.toString());
            }
        }

        @Override
        public void log(LDLogLevel level, String format, Object param) {
            if (isEnabled(level)) {
                logInternal(level, SimpleFormat.format(format, param));
            }
        }

        @Override
        public void log(LDLogLevel level, String format, Object param1, Object param2) {
            if (isEnabled(level)) {
                logInternal(level, SimpleFormat.format(format, param1, param2));
            }
        }

        @Override
        public void log(LDLogLevel level, String format, Object... params) {
            if (isEnabled(level)) {
                logInternal(level, SimpleFormat.format(format, params));
            }
        }
    }
}
