package com.launchdarkly.sdk.android;

import android.app.Application;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.HttpConfiguration;

import okhttp3.OkHttpClient;

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
 * Any attempt by SDK components to access an object that would normally be provided by the SDK,
 * but that has not been set, will cause an immediate unchecked exception. This would only happen if
 * components were being used outside of the SDK client in test code that did not correctly set
 * these properties.
 */
final class ClientContextImpl extends ClientContext {
    private final DiagnosticStore diagnosticStore;
    private final OkHttpClient sharedEventClient;
    private final SummaryEventStore summaryEventStore;

    ClientContextImpl(
            ClientContext base,
            DiagnosticStore diagnosticStore,
            OkHttpClient sharedEventClient,
            SummaryEventStore summaryEventStore
    ) {
        super(base);
        this.diagnosticStore = diagnosticStore;
        this.sharedEventClient = sharedEventClient;
        this.summaryEventStore = summaryEventStore;
    }

    static ClientContextImpl fromConfig(
            Application application,
            LDConfig config,
            String mobileKey,
            String environmentName,
            DiagnosticStore diagnosticStore,
            OkHttpClient sharedEventClient,
            SummaryEventStore summaryEventStore,
            LDLogger logger
    ) {
        ClientContext minimalContext = new ClientContext(null, mobileKey, logger, config,
                environmentName, config.isEvaluationReasons(), null, config.isOffline(),
                config.serviceEndpoints);
        HttpConfiguration httpConfig = config.http.build(minimalContext);
        ClientContext baseClientContext = new ClientContext(
                application,
                mobileKey,
                logger,
                config,
                environmentName,
                config.isEvaluationReasons(),
                httpConfig,
                config.isOffline(),
                config.serviceEndpoints
        );
        return new ClientContextImpl(baseClientContext, diagnosticStore, sharedEventClient, summaryEventStore);
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

    public OkHttpClient getSharedEventClient() {
        throwExceptionIfNull(sharedEventClient);
        return sharedEventClient;
    }

    public SummaryEventStore getSummaryEventStore() {
        throwExceptionIfNull(summaryEventStore);
        return summaryEventStore;
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