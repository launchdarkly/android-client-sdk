package com.launchdarkly.seat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;

/**
 * CP-SPEC 8.2 T1 pipe transport for the android cp-seat (the runner-side
 * {@code dial}/{@code bytes} relay; CP-SPEC 8.2's own E-6 shape: a socket factory
 * whose sockets speak the CP dial/bytes relay byte-transparently, with the mockld
 * service mapped onto reserved loopback IP literals that can never resolve over
 * DNS — "a socket that bypassed the factory could only reach loopback").
 *
 * <p>The SDK's own URL parser (OkHttp 4 {@code HttpUrl.get(URI)}) rejects the
 * {@code cp-pipe} scheme, so the seat rewrites every service endpoint
 * {@code cp-pipe://<svc>[/path]} to {@code http://127.66.0.N:80/path} and arms the
 * SDK's own module-internal HTTP slot ({@code LDUtil.testPipeSocketFactory}) with
 * this transport's factory. The relay never parses or re-frames HTTP: the SDK's
 * own HTTP client and SSE parser do that work (the point of T1). The java-server
 * seat ({@code reference-services/java-server/src/sdktest/cp/CpService.java}) is
 * the campaign's keyed precedent; the android shape rides the same law.
 *
 * <p>Module-internal, seat-side only: no SDK public API growth — the SDK's slots
 * ride the stock bytes verbatim when the seat never arms the seam.
 */
final class SeatPipeTransport {
  /** Each reserved loopback IP literal maps one mockld service (dial-order law). */
  static final Map<String, String> PIPE_HOSTS = new LinkedHashMap<>();
  static final Map<String, String> PIPE_SVC = new LinkedHashMap<>();
  static {
    PIPE_HOSTS.put("streaming", "127.66.0.1");
    PIPE_HOSTS.put("polling", "127.66.0.2");
    PIPE_HOSTS.put("events", "127.66.0.3");
    PIPE_SVC.put("127.66.0.1", "streaming");
    PIPE_SVC.put("127.66.0.2", "polling");
    PIPE_SVC.put("127.66.0.3", "events");
  }

  /** True when the configuration's own JSON carries any cp-pipe endpoint. */
  static boolean usesPipe(String configurationJson) {
    return configurationJson != null && configurationJson.contains("cp-pipe://");
  }

  /** E-6 host rewrite: cp-pipe://&lt;svc&gt;[/path] -> http://127.66.0.N:80/path. Other URIs ride verbatim. */
  static String rewrite(String uri) {
    if (uri == null || !uri.startsWith("cp-pipe://")) {
      return uri;
    }
    String rest = uri.substring("cp-pipe://".length());
    int slash = rest.indexOf('/');
    String svc = slash < 0 ? rest : rest.substring(0, slash);
    String path = slash < 0 ? "" : rest.substring(slash);
    svc = svc.replaceAll("/+$", "");
    String ip = PIPE_HOSTS.get(svc);
    if (ip == null) {
      throw new IllegalArgumentException("cp-pipe endpoint names an unknown service: " + uri);
    }
    return "http://" + ip + ":80" + path;
  }

  /**
   * Rewrites every {@code cp-pipe://...} token inside a JSON string up to the token's
   * own terminator (a quote, a backslash, or 512 chars). Every service endpoint inside
   * the configuration surface is a scheme://token leaf, so the blanket token rewrite is
   * safe over that surface and byte-stable everywhere else.
   */
  static String rewriteConfiguration(String json) {
    if (!usesPipe(json)) {
      return json;
    }
    StringBuilder out = new StringBuilder(json.length() + 64);
    int i = 0;
    while (true) {
      int at = json.indexOf("cp-pipe://", i);
      if (at < 0) {
        out.append(json, i, json.length());
        return out.toString();
      }
      out.append(json, i, at);
      int end = at + "cp-pipe://".length();
      while (end < json.length()) {
        char c = json.charAt(end);
        if (c == '"' || c == '\\' || end - at > 512) break;
        end++;
      }
      out.append(rewrite(json.substring(at, end)));
      i = end;
    }
  }

  private final SeatServe.Bus bus;
  private final AtomicInteger ids = new AtomicInteger();
  private final AtomicInteger conns = new AtomicInteger();
  private final Map<Integer, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
  private final Map<String, PipeSocket> sockets = new ConcurrentHashMap<>();

  SeatPipeTransport(SeatServe.Bus bus) {
    this.bus = bus;
  }

  /** The javax factory the SDK's own HTTP slot rides. */
  SocketFactory socketFactory() {
    return new SocketFactory() {
      @Override public Socket createSocket() { return new PipeSocket(); }
      @Override public Socket createSocket(String h, int p) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(h, p));
        return s;
      }
      @Override public Socket createSocket(String h, int p, InetAddress la, int lp) throws IOException {
        return createSocket(h, p);
      }
      @Override public Socket createSocket(InetAddress a, int p) throws IOException {
        Socket s = createSocket();
        s.connect(new InetSocketAddress(a, p));
        return s;
      }
      @Override public Socket createSocket(InetAddress a, int p, InetAddress la, int lp) throws IOException {
        return createSocket(a, p);
      }
    };
  }

  /** Called by the seat's main loop when a service->runner dial envelope took id and a runner reply arrived. */
  void onDialReply(int id, boolean ok) {
    CompletableFuture<Boolean> f = pending.remove(id);
    if (f != null) {
      f.complete(ok);
    }
  }

  /** Called by the seat's main loop when the runner relays bytes for a conn. */
  void onRunnerBytes(String conn, byte[] data, boolean eof, boolean abort) {
    PipeSocket s = sockets.get(conn);
    if (s != null) {
      s.fromRunner(data, eof, abort);
    }
  }

  void closeAll() {
    for (PipeSocket s : sockets.values()) {
      s.close();
    }
    sockets.clear();
    for (CompletableFuture<Boolean> f : pending.values()) {
      f.complete(Boolean.FALSE);
    }
    pending.clear();
  }

  private static final byte[] EOF = new byte[0];
  private static final byte[] ABORT = new byte[] {(byte) 0xd8};  // ABORT sentinel distinct from EOF

  final class PipeSocket extends Socket {
    private String conn;
    private volatile boolean connected, closed, outShut;
    private volatile int soTimeout = 0;
    private final LinkedBlockingQueue<Object> inbound = new LinkedBlockingQueue<>();
    private final In in = new In();
    private final Out out = new Out();

    @Override public void connect(SocketAddress a, int timeout) throws IOException {
      InetSocketAddress ia = (InetSocketAddress) a;
      String host = ia.getAddress() != null ? ia.getAddress().getHostAddress() : ia.getHostString();
      String svc = PIPE_SVC.get(host);
      if (svc == null) {
        throw new IOException("cp-pipe socket factory refuses non-pipe address " + host);
      }
      conn = "p" + conns.incrementAndGet();
      sockets.put(conn, this);
      int id = ids.incrementAndGet();
      CompletableFuture<Boolean> f = new CompletableFuture<>();
      pending.put(id, f);
      bus.dial(id, conn, "cp-pipe://" + svc, svc);
      boolean ok;
      try {
        ok = f.get(30_000, TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException e) {
        pending.remove(id);
        sockets.remove(conn);
        throw new IOException("cp-pipe dial: no answer in 30000 ms");
      } catch (java.util.concurrent.ExecutionException e) {
        sockets.remove(conn);
        throw new IOException("cp-pipe dial failed: " + e.getCause());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        sockets.remove(conn);
        throw new IOException("cp-pipe dial interrupted");
      }
      if (!ok) {
        pending.remove(id);
        sockets.remove(conn);
        throw new IOException("cp-pipe dial refused");
      }
      connected = true;
    }

    void fromRunner(byte[] data, boolean eof, boolean abort) {
      if (data != null && data.length > 0) {
        inbound.add(data);
      }
      if (abort) {
        inbound.add(ABORT);
      } else if (eof) {
        inbound.add(EOF);
      }
    }

    private void sendChunk(byte[] chunk) throws IOException {
      if (closed || outShut) throw new IOException("cp-pipe socket closed");
      bus.bytes(conn, chunk);
    }

    private void sendEof() {
      if (closed || outShut) return;
      outShut = true;
      try {
        bus.bytesEof(conn);
      } catch (Exception ignored) {
      }
    }

    @Override public void close() {
      if (closed) return;
      closed = true;
      sendEof();
      inbound.add(EOF);
      outShut = true;
      if (conn != null) {
        sockets.remove(conn);
      }
    }

    @Override public void shutdownOutput() {
      sendEof();
      outShut = true;
    }

    @Override public void shutdownInput() {
      inbound.add(EOF);
    }

    @Override public boolean isConnected() { return connected && !closed; }
    @Override public boolean isClosed() { return closed; }
    @Override public void setSoTimeout(int t) { soTimeout = t; }
    @Override public int getSoTimeout() { return soTimeout; }
    @Override public void setTcpNoDelay(boolean on) {}
    @Override public boolean getTcpNoDelay() { return true; }
    @Override public void setKeepAlive(boolean on) {}
    @Override public void setSoLinger(boolean on, int l) {}
    @Override public void setOOBInline(boolean on) {}
    @Override public void setTrafficClass(int tc) {}
    @Override public void setSendBufferSize(int s) {}
    @Override public void setReceiveBufferSize(int s) {}
    @Override public void setPerformancePreferences(int ct, int lt, int bw) {}
    @Override public InputStream getInputStream() throws IOException { return in; }
    @Override public OutputStream getOutputStream() throws IOException { return out; }

    private final class In extends InputStream {
      private Object cur;
      private int pos;
      private boolean eof;

      @Override public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n <= 0 ? -1 : (one[0] & 0xff);
      }

      @Override public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        while (cur == null || pos >= ((byte[]) cur).length) {
          if (eof || closed) return -1;
          Object next;
          try {
            next = soTimeout > 0 ? inbound.poll(soTimeout, TimeUnit.MILLISECONDS) : inbound.take();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("cp-pipe read interrupted");
          }
          if (next == null) throw new SocketTimeoutException("cp-pipe read timed out");
          if (next == EOF) { eof = true; return -1; }
          if (next == ABORT) throw new IOException("cp-pipe connection reset by runner");
          cur = next;
          pos = 0;
        }
        int n = Math.min(len, ((byte[]) cur).length - pos);
        System.arraycopy(cur, pos, b, off, n);
        pos += n;
        return n;
      }

      /** Streams a chunked body: keep draining until EOF, honouring soTimeout per chunk. */
      @Override public int available() { return cur == null ? 0 : ((byte[]) cur).length - pos; }
      @Override public void close() { PipeSocket.this.close(); }
    }

    private final class Out extends OutputStream {
      @Override public void write(int b) throws IOException { write(new byte[] { (byte) b }, 0, 1); }
      @Override public void write(byte[] b, int off, int len) throws IOException {
        sendChunk(java.util.Arrays.copyOfRange(b, off, off + len));
      }
      @Override public void close() { PipeSocket.this.close(); }
    }
  }
}
