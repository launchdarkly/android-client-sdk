package com.launchdarkly.sdk.android;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;

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
    private final PlatformState platformState;
    private final TaskExecutor taskExecutor;

    ClientContextImpl(
            ClientContext base,
            DiagnosticStore diagnosticStore,
            PlatformState platformState,
            TaskExecutor taskExecutor
    ) {
        super(base);
        this.diagnosticStore = diagnosticStore;
        this.platformState = platformState;
        this.taskExecutor = taskExecutor;
    }

    static ClientContextImpl fromConfig(
            LDConfig config,
            String mobileKey,
            String environmentName,
            LDContext initialContext,
            LDLogger logger,
            PlatformState platformState,
            TaskExecutor taskExecutor
    ) {
        ClientContext baseClientContext = new ClientContext(
                mobileKey,
                logger,
                config,
                environmentName,
                config.isEvaluationReasons(),
                initialContext,
                platformState != null && !platformState.isForeground(),
                config.isOffline(),
                config.serviceEndpoints,
                config.isUseReport()
        );
        DiagnosticStore diagnosticStore = null;
        if (!config.getDiagnosticOptOut()) {
            diagnosticStore = new DiagnosticStore(EventUtil.makeDiagnosticParams(baseClientContext));
        }
        return new ClientContextImpl(baseClientContext, diagnosticStore, platformState, taskExecutor);
    }

    public static ClientContextImpl get(ClientContext context) {
        if (context instanceof ClientContextImpl) {
            return (ClientContextImpl)context;
        }
        return new ClientContextImpl(context, null, null, null);
    }

    public DiagnosticStore getDiagnosticStore() {
        return diagnosticStore;
    }

    public PlatformState getPlatformState() {
        throwExceptionIfNull(platformState);
        return platformState;
    }

    public TaskExecutor getTaskExecutor() {
        throwExceptionIfNull(taskExecutor);
        return taskExecutor;
    }

    private static void throwExceptionIfNull(Object o) {
        if (o == null) {
            throw new IllegalStateException(
                    "Attempted to use an SDK component without the necessary dependencies from LDClient; "
                    + " this should never happen unless an application has tried to construct the"
                    + " component directly outside of normal SDK usage"
            );
        }
    }
}
