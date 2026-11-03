package com.launchdarkly.testhelpers.httptest.impl;

import com.google.common.collect.ImmutableMap;
import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestContext;
import com.launchdarkly.testhelpers.httptest.RequestInfo;
import com.launchdarkly.testhelpers.httptest.ServerTLSConfiguration;

import com.launchdarkly.testhelpers.httptest.nanohttpd.protocols.http.IHTTPSession;
import com.launchdarkly.testhelpers.httptest.nanohttpd.protocols.http.NanoHTTPD;
import com.launchdarkly.testhelpers.httptest.nanohttpd.protocols.http.response.IStatus;
import com.launchdarkly.testhelpers.httptest.nanohttpd.protocols.http.response.Response;
import com.launchdarkly.testhelpers.httptest.nanohttpd.protocols.http.response.Status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.KeyManagerFactory;

/**
 * A copy of the class of this name in com.launchdarkly:test-helpers 2.1.0, which it replaces on the
 * unit-test classpath, so it has to keep this package and name. It differs in two ways:
 * <ul>
 *   <li>It reads a request body until Content-Length. That release reads it with a single {@code read},
 *   which returns only the part that has arrived, so a body sent in more than one TCP segment was
 *   recorded truncated.</li>
 *   <li>It listens on 127.0.0.1 rather than on every address. Listening on every address with
 *   SO_REUSEADDR, as NanoHTTPD does, the kernel can hand out a port another process already listens on
 *   at 127.0.0.1 (Android Studio and the emulator hold several), and "localhost" then reaches that
 *   process instead: the test sees a 404, a 401 or no answer at all.</li>
 * </ul>
 * {@code NanoHttpdServerDelegateTest} fails if this copy stops being the one loaded. Delete both once
 * test-helpers does the same.
 */
class NanoHttpdServerDelegate implements HttpServer.Delegate {
  private static final String LOOPBACK = "127.0.0.1";

  private final ServerImpl server;
  
  public NanoHttpdServerDelegate(int port, Handler handler, ServerTLSConfiguration tlsConfig) {
    server = new ServerImpl(port, handler, tlsConfig); // NanoHTTPD will pick a port for us if this is zero
  }
  
  @Override
  public void close() throws IOException {
    server.closeAllConnections();
    server.stop();
  }

  @Override
  public int start() throws IOException {
    server.start();
    return server.getListeningPort();
  }

  private static final class ServerImpl extends NanoHTTPD {
    private final Handler handler;
    
    ServerImpl(int port, Handler handler, ServerTLSConfiguration tlsConfig) {
      super(LOOPBACK, port);
      this.handler = handler;
      
      if (tlsConfig != null) {
        try {
          char[] fakePassword = "secret".toCharArray();
          KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
          keyStore.load(null);
          keyStore.setCertificateEntry("localhost", tlsConfig.getCertificate());
          keyStore.setEntry("localhost",
              new KeyStore.PrivateKeyEntry(tlsConfig.getPrivateKey(), new Certificate[] { tlsConfig.getCertificate() }),
              new KeyStore.PasswordProtection(fakePassword));
          KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
          keyManagerFactory.init(keyStore, fakePassword);
          makeSecure(NanoHTTPD.makeSSLSocketFactory(keyStore, keyManagerFactory), null);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
    
    @Override
    public Response serve(IHTTPSession session) {
      // We need to call the handler on a separate thread so that we can support chunked streaming.
      // NanoHTTPD doesn't have an imperative "start writing the response" method; instead, we need
      // to return the response to it, and *then* if there is additional streaming content, the
      // handler will continue writing it.
      CompletableFuture<Response> responseReceiver = new CompletableFuture<>();
      RequestContextImpl ctx = new RequestContextImpl(makeRequestInfo(session), responseReceiver);
      
      new Thread(() -> {
        try {
          handler.apply(ctx);
          ctx.commit();
        } catch (Exception e) {
          responseReceiver.completeExceptionally(e);
        }
      }).start();
      
      try {
        Response response = responseReceiver.get();
        return response;
      } catch (ExecutionException e) {
        throw new RuntimeException(e.getCause());
      } catch (InterruptedException e) {
        throw new RuntimeException(e.getCause());
      }
    }
   
    private RequestInfo makeRequestInfo(IHTTPSession session) {
      String path = session.getUri(); // NanoHTTPD calls this the URI but it's really the path
      String query = session.getQueryParameterString();
      String queryWithPrefix = query == null || query.isEmpty() ? "" : ("?" + query); 
      URI requestUri = URI.create(getBaseUri() + path + queryWithPrefix);
      ImmutableMap.Builder<String, String> headers = ImmutableMap.builder();
      String body = "";
      int contentLength = 0;
      
      for (Map.Entry<String, String> h: session.getHeaders().entrySet()) {
        headers.put(h.getKey().toLowerCase(), h.getValue());
        if (h.getKey().equalsIgnoreCase("content-length")) {
          contentLength = Integer.parseInt(h.getValue());           
        }
      }
      if (contentLength > 0) {
        try {
          InputStream bodyStream = session.getInputStream();
          byte[] data = new byte[contentLength];
          int n = 0;
          while (n < contentLength) {
            int read = bodyStream.read(data, n, contentLength - n);
            if (read < 0) {
              break;
            }
            n += read;
          }
          body = new String(data, 0, n, Charset.forName("UTF-8"));
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      
      return new RequestInfo(session.getMethod().toString(), requestUri, path,
          queryWithPrefix.isEmpty() ? null : queryWithPrefix,
          headers.build(), body);
    }
    
    private String getBaseUri() {
      return "http://localhost:" + this.getListeningPort();
    }
  }
  
  private static final class RequestContextImpl implements RequestContext {
    private final RequestInfo requestInfo;
    private final CompletableFuture<Response> responseReceiver;
    
    int status = 200;
    String contentType = null;
    Map<String, List<String>> headers = new HashMap<>();
    
    boolean chunked = false;
    volatile Response response = null;
    PipedOutputStream chunkedPipe = null;

    RequestContextImpl(RequestInfo requestInfo, CompletableFuture<Response> responseReceiver) {
      this.requestInfo = requestInfo;
      this.responseReceiver = responseReceiver;
    }
    
    void commit() {
      if (chunked) {
        try {
          chunkedPipe.close();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      } else {
        if (response == null) {
          // a status was set but nothing was written; call write() to force us to create a response
          write(null);
        }
        responseReceiver.complete(response);
      }      
    }
    
    @Override
    public RequestInfo getRequest() {
      return requestInfo;
    }

    @Override
    public void setStatus(int status) {
      this.status = status;
    }

    @Override
    public void setHeader(String name, String value) {
      headers.remove(name);
      addHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
      String lowerName = name.toLowerCase();
      List<String> values = headers.get(lowerName);
      if (values == null) {
        values = new ArrayList<>();
        headers.put(lowerName, values);
      }
      values.add(value);
    }

    @Override
    public void setChunked() {
      if (!chunked) {
        if (response != null) {
          throw new RuntimeException("setChunked was called after writing a non-chunked response");
        }
        chunked = true;
        chunkedPipe = new PipedOutputStream();
        InputStream pipeReader;
        try {
          pipeReader = new PipedInputStream(chunkedPipe);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        response = Response.newChunkedResponse(statusWithCode(status),
              contentType, pipeReader);
        setHeaders(response);
        response.setUseGzip(false);
        
        // We need to tell the ServerImpl code to return this response immediately to the server,
        // while the handler (which will write the actual stream data) continues executing. That's
        // what it provided this CompletableFuture for.
        responseReceiver.complete(response);
      }
    }

    @Override
    public void write(byte[] data) {
      if (chunked) {
        try {
          if (data != null) {
            chunkedPipe.write(data);
          }
          chunkedPipe.flush();
          Thread.sleep(200);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
        return;
      }

      if (response != null) {
        throw new RuntimeException("write was called twice for a non-chunked response");
      }
      if (data == null) {
        data = new byte[0];
      }
      if (data.length != 0 && contentType == null) {
        contentType = "text/plain";
      }
      response = Response.newFixedLengthResponse(statusWithCode(status),
          contentType, new ByteArrayInputStream(data), data.length);
      setHeaders(response);
    }

    @Override
    public String getPathParam(int i) {
      return null;
    }

    private void setHeaders(Response r) {
      for (Map.Entry<String, List<String>> h: headers.entrySet()) {
        String name = h.getKey();
        String value = String.join(",", h.getValue());
        if (name.equals("content-type")) {
          r.setMimeType(value);
        } else {
          r.addHeader(name, value);
          // The name addHeader in NanoHTTPD is misleading: it replaces any previous value, so we need
          // to pre-concatenate with String.join() if we want multiple values to work.
          // https://github.com/NanoHttpd/nanohttpd/issues/629
        }
      }
    }
    
    private IStatus statusWithCode(int statusCode) {
      IStatus builtin = Status.lookup(statusCode);
      if (builtin != null) {
        return builtin;
      }
      return new IStatus() {
        @Override
        public int getRequestStatus() {
          return statusCode;
        }
        
        @Override
        public String getDescription() {
          return statusCode + " UNKNOWN";
        }
      };
    }
  }
}
