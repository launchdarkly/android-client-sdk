package com.launchdarkly.sdktest;

import android.app.Application;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.json.JsonSerialization;
import com.launchdarkly.sdktest.Representations.CommandParams;
import com.launchdarkly.sdktest.Representations.CreateInstanceParams;
import com.launchdarkly.sdktest.Representations.Status;
import com.launchdarkly.sdk.android.BuildConfig;
import com.launchdarkly.sdk.android.LDAndroidLogging;
import com.launchdarkly.sdk.json.LDGson;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;

public class TestService extends NanoHTTPD {
    private static final int PORT = 8001;
    private static final String[] CAPABILITIES = new String[]{
            "client-side",
            "mobile",
            "service-endpoints",
            "singleton",
            "strongly-typed",
            "tags",
            "auto-env-attributes",
            "inline-context-all",
            "anonymous-redaction",
            "client-prereq-events",
            "client-prereq-cycle-detection",
            "polling-gzip",
            "evaluation-hooks",
            "track-hooks",
            "client-per-context-summaries",
            "client-event-source-http-errors",
            "retry-conformance-fdv1-streaming",
            "retry-conformance-fdv1-polling"
    };
    private static final String MIME_JSON = "application/json";
    /**
     * CENTINEL census-context wire seam (the events_ctx classes' own close
     * law): a single-kind context wire may omit the explicit kind member (the
     * census bank's own initial_context bytes; the harness's implicit default
     * kind law, CONTEXT 1.2). The shared core's own context deserializer
     * prices its top-level custom attributes only on the kinded shape, so the
     * seat injects the default kind member on the kind-less, custom-free
     * single-kind wire before the core's own parse. Shapes that already carry
     * kind (incl. kind:"multi") and the legacy-user wire (the custom-envelope
     * form the harness's old-user rows ride) parse through the core's own
     * deserializer byte-true.
     */
    private static final com.google.gson.JsonDeserializer<LDContext> CENSUS_CONTEXT_WIRE =
            (json, typeOfT, context) -> {
                com.google.gson.JsonElement el =
                        com.google.gson.JsonParser.parseString(json.toString());
                if (el.isJsonObject() && !el.getAsJsonObject().has("kind")) {
                    el.getAsJsonObject().addProperty("kind", "user");
                }
                try {
                    return JsonSerialization.deserialize(el.toString(), LDContext.class);
                } catch (com.launchdarkly.sdk.json.SerializationException e) {
                    // the core's own parse refusal prices named (the census
                    // context wire seam never swallows the wire)
                    throw new com.google.gson.JsonSyntaxException("census context wire: " + e.getMessage(), e);
                }
            };

    static final Gson gson = new GsonBuilder()
            .registerTypeAdapterFactory(LDGson.typeAdapters())
            // the explicit context wire seam: later registration wins over the
            // factory's own LDContext adapter (kinded shapes ride byte-true)
            .registerTypeAdapter(LDContext.class, CENSUS_CONTEXT_WIRE)
            .create();

    static final OkHttpClient client = new OkHttpClient();

    private final Router router = new Router();
    private final Application application;
    private final LDLogAdapter logAdapter;
    private final LDLogger logger;

    private final Map<String, SdkClientEntity> clients = new ConcurrentHashMap<String, SdkClientEntity>();
    private final AtomicInteger clientCounter = new AtomicInteger(0);

    public static class BadRequestException extends Exception {
        public BadRequestException(String message) {
            super(message);
        }
    }

    TestService(Application application) {
        super(PORT);
        this.application = application;
        this.logAdapter = LDAndroidLogging.adapter();
        this.logger = LDLogger.withAdapter(logAdapter, "service");
        router.add("GET", "/", (params, body) -> getStatus());
        router.add("POST", "/", (params, body) -> postCreateClient(body));
        router.addRegex("POST", Pattern.compile("/clients/(.*)"), (params, body) -> postClientCommand(params, body));
        router.addRegex("DELETE", Pattern.compile("/clients/(.*)"), (params, body) -> deleteClient(params));
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();

        String body = null;
        if (Method.POST.equals(method) && session.getHeaders().containsKey("content-length")) {
            int contentLength = Integer.parseInt(session.getHeaders().get("content-length"));
            byte[] buffer = new byte[contentLength];
            try {
                session.getInputStream().read(buffer, 0, contentLength);
            } catch (IOException e) {
                e.printStackTrace();
            }
            body = new String(buffer);
        }

        logger.info("Handling request: {} {}", method.name(), session.getUri());
        try {
            return router.route(method.name(), session.getUri(), body);
        } catch (JsonSyntaxException jse) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "Invalid JSON Format\n");
        } catch (Exception e) {
            logger.error("Exception when handling request: {} {} - {}", method.name(), session.getUri(), e);
            logger.error(LogValues.exceptionTrace(e));
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, e.toString());
        }
    }

    private Response getStatus() {
        Status ret = new Representations.Status();

        ret.name = "android-client-sdk";
        ret.clientVersion = BuildConfig.VERSION_NAME;
        ret.capabilities = CAPABILITIES;

        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, gson.toJson(ret));
    }

    private Response postCreateClient(String jsonPayload) {
        CreateInstanceParams params = gson.fromJson(jsonPayload, CreateInstanceParams.class);

        String clientId = String.valueOf(clientCounter.incrementAndGet());
        SdkClientEntity client = new SdkClientEntity(application, params, logAdapter);

        clients.put(clientId, client);

        Response response = newFixedLengthResponse(null);
        response.addHeader("Location", "/clients/" + clientId);
        return response;
    }

    private Response postClientCommand(List<String> pathParams, String jsonPayload) {
        String clientId = pathParams.get(0);
        CommandParams params = gson.fromJson(jsonPayload, CommandParams.class);
        SdkClientEntity client = clients.get(clientId);
        if (client == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Client not found\n");
        }
        try {
            Object resp = client.doCommand(params);
            return newFixedLengthResponse(
                    Response.Status.ACCEPTED,
                    MIME_JSON,
                    resp != null ? gson.toJson(resp) : null);
        } catch (java.io.IOException e) {
            return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT, String.valueOf(e.getMessage() == null ? e : e.getMessage()) + "\n");
        } catch (BadRequestException e) {
            return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    NanoHTTPD.MIME_PLAINTEXT,
                    e.getMessage());
        }
    }

    private Response deleteClient(List<String> pathParams) {
        String clientId = pathParams.get(0);
        SdkClientEntity client = clients.get(clientId);
        if (client == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Client not found\n");
        }
        client.close();
        return newFixedLengthResponse(null);
    }
}
