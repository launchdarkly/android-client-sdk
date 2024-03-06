package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogLevel;

import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;
import timber.log.Timber.DebugTree;
import timber.log.Timber.Tree;

/**
 * Allows LaunchDarkly log output to be forwarded to Timber.
 * <p>
 * Currently this is the default logging implementation; in the future, we may change the default to
 * {@link LDAndroidLogging}.
 * <p>
 * When this logging implementation is active, the SDK will automatically call
 * {@code Timber.plant(new Timber.DebugTree)} at initialization time if and only if
 * {@code BuildConfig.DEBUG} is true. This behavior is for consistency with the default behavior of
 * earlier SDK versions and may be removed in the future. It can be changed with
 * {@link Adapter#autoPlantDebugTree(boolean)}.
 *
 * @since 3.2.0
 */
public abstract class LDTimberLogging {

    /**
     * @return an {@link LDLogAdapter} for Timber logging
     */
    public static LDLogAdapter adapter() {
        return new Adapter(true);
    }

    /**
     * A Timber implementation of the {@link LDLogAdapter} interface.
     */
    public static final class Adapter implements LDLogAdapter, LDLogAdapter.IsConfiguredExternally {
        // Note: implementing IsConfiguredExternally tells the logging framework that it should not
        // try to do level filtering, because Timber has its own configuration mechanisms.
        private final boolean autoPlantDebugTree;

        Adapter(boolean autoPlantDebugTree) {
            this.autoPlantDebugTree = autoPlantDebugTree;
        }

        /**
         * Returns a modified logging adapter with the automatic debug tree behavior changed.
         * <p>
         * By default, this property is true, meaning that the SDK will automatically call
         * {@code Timber.plant(new Timber.DebugTree)} at initialization time if and only if
         * {@code BuildConfig.DEBUG} is true. If you set it to false as shown below, then the
         * SDK will never create a {@code DebugTree} and the application is responsible for
         * doing so if desired.
         * <pre><code>
         *     LDConfig config = new LDConfig.Builder()
         *         .mobileKey("mobile-key")
         *         .logAdapter(LDTimberLogging.adapter().autoPlantDebugTree(false))
         *         .build();
         * </code></pre>
         * <p>
         * In a future version, this automatic behavior may be removed, since it is arguably more
         * correct for library code to leave all Tree-planting to the application. The behavior is
         * retained in the current release for backward compatibility.
         * </p>
         * @param autoPlantDebugTree true to retain the default automatic {@code DebugTree}
         *                           behavior, or false to disable it
         * @return a modified {@link Adapter} with the specified behavior
         */
        public Adapter autoPlantDebugTree(boolean autoPlantDebugTree) {
            return new Adapter(autoPlantDebugTree);
        }

        @Override
        public Channel newChannel(String name) {
            return new ChannelImpl(name, autoPlantDebugTree);
        }
    }

    private static final class ChannelImpl extends LDAndroidLogging.ChannelImplBase {
        private static final AtomicBoolean inited = new AtomicBoolean(false);

        ChannelImpl(String tag, boolean autoPlantDebugTree) {
            super(tag);
            if (autoPlantDebugTree && !inited.getAndSet(true)) {
                if (BuildConfig.DEBUG) {
                    Timber.plant(new DebugTree());
                }
            }
        }

        @Override
        public boolean isEnabled(LDLogLevel level) {
            return true;
        }

        @Override
        protected void logInternal(LDLogLevel level, String text) {
            Tree tree = Timber.tag(tag);
            switch (level) {
                case DEBUG:
                    tree.d(text);
                    break;
                case INFO:
                    tree.i(text);
                    break;
                case WARN:
                    tree.w(text);
                    break;
                case ERROR:
                    tree.e(text);
                    break;
            }
        }
    }
}
