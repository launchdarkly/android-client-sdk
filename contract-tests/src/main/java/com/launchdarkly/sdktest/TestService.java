package com.launchdarkly.sdktest;

import android.app.Application;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
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
            "evaluation-hooks",
            "track-hooks",
            "client-per-context-summaries"
    };
    private static final String MIME_JSON = "application/json";
    static final Gson gson = new GsonBuilder()
            .registerTypeAdapterFactory(LDGson.typeAdapters())
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
