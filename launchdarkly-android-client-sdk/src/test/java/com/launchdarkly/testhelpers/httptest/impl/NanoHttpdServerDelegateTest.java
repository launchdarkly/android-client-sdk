package com.launchdarkly.testhelpers.httptest.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class NanoHttpdServerDelegateTest {
    @Test
    public void aBodySentInPartsIsRecordedWhole() throws Exception {
        char[] chars = new char[20_000];
        Arrays.fill(chars, 'x');
        String body = new String(chars);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        int firstPart = 100;

        try (HttpServer server = HttpServer.start(Handlers.status(202));
             Socket socket = new Socket("localhost", server.getPort())) {
            OutputStream out = socket.getOutputStream();
            String headers = "POST /bulk HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + bytes.length + "\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(bytes, 0, firstPart);
            out.flush();
            Thread.sleep(200);
            out.write(bytes, firstPart, bytes.length - firstPart);
            out.flush();

            RequestInfo request = server.getRecorder().requireRequest(5, TimeUnit.SECONDS);
            assertEquals(body, request.getBody());

            InputStream in = socket.getInputStream();
            byte[] status = new byte["HTTP/1.1 202".length()];
            int n = 0;
            while (n < status.length) {
                int read = in.read(status, n, status.length - n);
                if (read < 0) break;
                n += read;
            }
            assertEquals("HTTP/1.1 202", new String(status, 0, n, StandardCharsets.US_ASCII));
        }
    }

    @Test
    public void aServerDoesNotShareAPortWithAnotherLoopbackListener() throws Exception {
        // Stands in for another process listening on 127.0.0.1. Asking for its port forces what the
        // kernel otherwise does now and then with port 0.
        try (ServerSocket other = new ServerSocket()) {
            other.setReuseAddress(true);
            other.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));

            assertThrows(RuntimeException.class,
                    () -> HttpServer.start(other.getLocalPort(), Handlers.status(202)).close());
        }
    }
}
