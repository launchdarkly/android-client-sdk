package com.launchdarkly.sdktest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

/**
 * An HTTP router inspired by com.launchdarkly.testhelpers.httptest.SimpleRouter, that
 * we can use with NanoHTTPD.
 * https://github.com/launchdarkly/java-test-helpers/blob/main/src/main/java/com/launchdarkly/testhelpers/httptest/SimpleRouter.java
 */
public class Router {
    public interface Handler {
        NanoHTTPD.Response apply(List<String> pathParams, String body) throws Exception;
    }

    private final List<Route> routes = new ArrayList<>();

    private static class Route {
        final String method;
        final Pattern pattern;
        final Handler handler;

        Route(String method, Pattern pattern, Handler handler) {
            this.method = method;
            this.pattern = pattern;
            this.handler = handler;
        }
    }

    public void add(String method, String path, Handler handler) {
        addRegex(method, Pattern.compile(Pattern.quote(path)), handler);
    }

    public void addRegex(String method, Pattern regex, Handler handler) {
        routes.add(new Route(method, regex, handler));
    }

    public NanoHTTPD.Response route(String method, String path, String body) throws Exception {
        boolean matchedPath = false;
        for (Route r: routes) {
            Matcher m = r.pattern.matcher(path);
            if (m.matches()) {
                matchedPath = true;
                if (r.method != null && !r.method.equalsIgnoreCase(method)) {
                    continue;
                }
                ArrayList<String> params = new ArrayList<>();
                if (m.groupCount() > 0) {
                    for (int i = 1; i <= m.groupCount(); i++) {
                        params.add(m.group(i));
                    }
                }
                return r.handler.apply(params, body);
            }
        }
        if (matchedPath) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, NanoHTTPD.MIME_PLAINTEXT, "Method Not Allowed\n");
        } else {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found\n");
        }
    }
}
