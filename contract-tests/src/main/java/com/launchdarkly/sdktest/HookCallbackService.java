package com.launchdarkly.sdktest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.net.URI;

public class HookCallbackService {
    private final URI serviceUri;

    public HookCallbackService(URI serviceUri) {
        this.serviceUri = serviceUri;
    }

    public void post(Object params) {
        // CENTINEL seat extension (CP-SPEC 7.2): a cp-callback:// callback URI rides
        // the CP/stdio callback wheel — the seat MUST NOT dial it; it emits a
        // `callback` envelope and awaits the runner's reply on stdin before the
        // SDK-side call completes. Any other scheme keeps the harness's own
        // HTTP receiver arm (used when the seat runs under CP/http on a device).
        if (serviceUri != null && "cp-callback".equals(serviceUri.getScheme())) {
            SdkClientEntity.seatPostCallbackHTTPFace(serviceUri, params);
            return;
        }
        RequestBody body = RequestBody.create(
            TestService.gson.toJson(params == null ? "{}" : params),
            MediaType.parse("application/json")
        );
        Request request = new Request.Builder().url(serviceUri.toString())
            .method("POST", body)
            .build();
        try (Response response = TestService.client.newCall(request).execute()) {
          assertOk(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertOk(Response response) {
        if (!response.isSuccessful()) {
            String body = "";
            if (response.body() != null) {
                try {
                    body = ": " + response.body().string();
                } catch (Exception e) {}
            }
            throw new RuntimeException("HTTP error " + response.code() + " from callback to " + serviceUri + body);
        }
    }
}
