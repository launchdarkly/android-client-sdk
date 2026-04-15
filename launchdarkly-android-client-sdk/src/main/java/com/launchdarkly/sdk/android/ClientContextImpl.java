package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;

import androidx.annotation.Nullable;

/**
 * This package-private subclass of {@link ClientContext} contains additional non-public SDK objects
 * that may be used by our internal components.
 * <p>
 * The reason for using this mechanism, instead of just passing those objects directly as constructor
 * parameters, is that some SDK components are pluggable-- that is, they are implementations of a
 * public interface that a customer could implement themselves, and they are instantiated via a
 * standard factory method, which always takes a {@link ClientContext} parameter. Customer code can
 * only see the public properties of {@link ClientContext}, but our own code can see the
 * package-private properties, which they can do by calling {@code ClientContextImpl.get(ClientContext)}
 * to make sure that what they have is really a {@code ClientContextImpl} (as opposed to some other
 * implementation of {@link ClientContext}, which might have been created for instance in application
 * test code).
 * <p>
 * Any attempt by SDK components to access an object that would normally be provided by the SDK
 * (such as PlatformState), but that has not been set, will cause an immediate unchecked exception.
 * This would only happen if components were being used outside of the SDK client in test code that
 * did not correctly set these properties.
 */
final class ClientContextImpl extends ClientContext {
    private final DiagnosticStore diagnosticStore;
    private final FeatureFetcher fetcher;
    private final PlatformState platformState;
    private final TaskExecutor taskExecutor;
    private final PersistentDataStoreWrapper.PerEnvironmentData perEnvironmentData;
    @Nullable
    private final SelectorSource selectorSource;

    /** Used by FDv1 code paths that do not need a {@link SelectorSource}. */
    ClientContextImpl(
            ClientContext base,
            DiagnosticStore diagnosticStore,
            FeatureFetcher fetcher,
            PlatformState platformState,
            TaskExecutor taskExecutor,
            PersistentDataStoreWrapper.PerEnvironmentData perEnvironmentData
    ) {
        this(base, diagnosticStore, fetcher, platformState, taskExecutor, perEnvironmentData, null);
    }

    /**
     * Used by FDv2 code paths. The {@code selectorSource} provides selector state to
     * initializers and synchronizers via the {@link ContextDataManager.ContextDataManagerView}.
     */
    ClientContextImpl(
            ClientContext base,
            DiagnosticStore diagnosticStore,
            FeatureFetcher fetcher,
            PlatformState platformState,
            TaskExecutor taskExecutor,
            PersistentDataStoreWrapper.PerEnvironmentData perEnvironmentData,
            @Nullable SelectorSource selectorSource
    ) {
        super(base);
        this.diagnosticStore = diagnosticStore;
        this.fetcher = fetcher;
        this.platformState = platformState;
        this.taskExecutor = taskExecutor;
        this.perEnvironmentData = perEnvironmentData;
        this.selectorSource = selectorSource;
    }

    static ClientContextImpl fromConfig(
            LDConfig config,
            String mobileKey,
            String environmentName,
            PersistentDataStoreWrapper.PerEnvironmentData perEnvironmentData, FeatureFetcher fetcher,
            LDContext initialContext,
            LDLogger logger,
            PlatformState platformState,
            IEnvironmentReporter environmentReporter,
            TaskExecutor taskExecutor
    ) {
        boolean initiallyInBackground = platformState != null && !platformState.isForeground();
        ClientContext minimalContext = new ClientContext(mobileKey, environmentReporter, logger, config,
                null, environmentName, config.isEvaluationReasons(), initialContext, null,
                initiallyInBackground, null, config.serviceEndpoints, config.isOffline());
        HttpConfiguration httpConfig = config.http.build(minimalContext);
        ClientContext baseClientContext = new ClientContext(
                mobileKey,
                environmentReporter,
                logger,
                config,
                null,
                environmentName,
                config.isEvaluationReasons(),
                initialContext,
                httpConfig,
                initiallyInBackground,
                null,
                config.serviceEndpoints,
                config.isOffline()
        );
        DiagnosticStore diagnosticStore = null;
        if (!config.getDiagnosticOptOut()) {
            diagnosticStore = new DiagnosticStore(EventUtil.makeDiagnosticParams(baseClientContext));
        }
        return new ClientContextImpl(baseClientContext, diagnosticStore, fetcher, platformState, taskExecutor, perEnvironmentData);
    }

    public static ClientContextImpl get(ClientContext context) {
        if (context instanceof ClientContextImpl) {
            return (ClientContextImpl)context;
        }
        return new ClientContextImpl(context, null, null, null, null, null);
    }

    /** Creates a context for FDv1 data sources that do not need a {@link SelectorSource}. */
    public static ClientContextImpl forDataSource(
            ClientContext baseClientContext,
            DataSourceUpdateSink dataSourceUpdateSink,
            LDContext newEvaluationContext,
            boolean newInBackground,
            Boolean previouslyInBackground
    ) {
        return forDataSource(baseClientContext, dataSourceUpdateSink, newEvaluationContext,
                newInBackground, previouslyInBackground, null);
    }

    /**
     * Creates a context for data sources, optionally including a {@link SelectorSource}.
     * FDv2 data sources require the selector source so that {@link FDv2DataSourceBuilder} can
     * provide selector state to initializers and synchronizers.
     */
    public static ClientContextImpl forDataSource(
            ClientContext baseClientContext,
            DataSourceUpdateSink dataSourceUpdateSink,
            LDContext newEvaluationContext,
            boolean newInBackground,
            Boolean previouslyInBackground,
            @Nullable SelectorSource selectorSource
    ) {
        ClientContextImpl baseContextImpl = ClientContextImpl.get(baseClientContext);
        return new ClientContextImpl(
                new ClientContext(
                        baseClientContext.getMobileKey(),
                        baseClientContext.getEnvironmentReporter(),
                        baseClientContext.getBaseLogger(),
                        baseClientContext.getConfig(),
                        dataSourceUpdateSink,
                        baseClientContext.getEnvironmentName(),
                        baseClientContext.isEvaluationReasons(),
                        newEvaluationContext,
                        baseClientContext.getHttp(),
                        newInBackground,
                        previouslyInBackground,
                        baseClientContext.getServiceEndpoints(),
                        false // setOffline is always false if we are creating a data source
                ),
                baseContextImpl.getDiagnosticStore(),
                baseContextImpl.getFetcher(),
                baseContextImpl.getPlatformState(),
                baseContextImpl.getTaskExecutor(),
                baseContextImpl.getPerEnvironmentData(),
                selectorSource
        );
    }

    /**
     * Sets the evaluation context and returns a new instance of {@link ClientContextImpl}
     * @param context to now use as the evaluation context
     * @return a new instance
     */
    public ClientContextImpl setEvaluationContext(LDContext context) {
        return new ClientContextImpl(
            super.setEvaluationContext(context),
            this.diagnosticStore,
            this.fetcher,
            this.platformState,
            this.taskExecutor,
            this.perEnvironmentData,
            this.selectorSource
        );
    }

    public DiagnosticStore getDiagnosticStore() {
        return diagnosticStore;
    }

    public FeatureFetcher getFetcher() {
        return fetcher;
    }

    public PlatformState getPlatformState() {
        return throwExceptionIfNull(platformState);
    }

    public TaskExecutor getTaskExecutor() {
        return throwExceptionIfNull(taskExecutor);
    }

    public PersistentDataStoreWrapper.PerEnvironmentData getPerEnvironmentData() {
        return throwExceptionIfNull(perEnvironmentData);
    }

    @Nullable
    public SelectorSource getSelectorSource() {
        return selectorSource;
    }

    private static <T> T throwExceptionIfNull(T o) {
        if (o == null) {
            throw new IllegalStateException(
                    "Attempted to use an SDK component without the necessary dependencies from LDClient; "
                    + " this should never happen unless an application has tried to construct the"
                    + " component directly outside of normal SDK usage"
            );
        }
        return o;
    }
}
