#!/usr/bin/env python3
"""cp-seat — the CP/http transport framing of the Android client SDK's
CP/stdio seat. It is a second transport of the same graded seat: the census
CP/stdio seat (`Seat`/`SeatServe`, served by `seat-serve.sh`) and this HTTP
framing rout every op onto the SAME seat process, so the two framings cannot
drift while grading the same SDK heads.

This file is a VERIFY-ONLY transport: it carries no SDK op bodies (the
purity law — no flag keys, no evaluation semantics; the op bodies stay in
the JVM seat's `SeatServe` + `SdkClientEntity`). One-to-one, it projects the
harness HTTP API onto the CP/stdio envelope protocol (CP-SPEC 3/4/6):

  GET    /             -> op "status"; the reply body IS the HTTP body
  POST   /             -> op "createClient"; body verbatim (CreateInstanceParams)
                          -> 2xx (201) + `Location: /clients/<id>`
  POST   /clients/<id> -> op "command"; body verbatim as {client, request}
  DELETE /clients/<id> -> op "closeClient"; empty 2xx on success
  DELETE /             -> op "stop"; respond, then exit (the seat exits with it)

Launch (CP-SPEC 6.1 + rule 5a): `CP_FRAMING=http`, `CP_VERSION=0`,
`CP_HTTP_HOST=127.0.0.1` (or `::1` — anything else is refused before any
bind), `CP_HTTP_PORT=0` (OS-assigned ephemeral). The bridge binds that
loopback host on the OS-assigned port, then prints one line
`R5-SERVICE-LISTEN <host>:<port>` (the public harness driver waits for it)
and one line the CP `ready` envelope
(`{"cp":"0","id":0,"op":"ready","body":{"framing":"http","url":...}}` —
REQUIRED under CP/http). After `ready`, stdout carries nothing more; stderr
is the free-form log (the seat child's stderr is inherited, never stdout).

Callbacks (CP-SPEC 6.3/7.2): under CP/http every baseUri/callbackUri the
runner sends is a real loopback URL, so a service->runner `callback`
envelope is transcoded verbatim here: the payload POSTs to `uri + path`
with the stdlib client face and the runner's HTTP response status is the
callback's answer envelope (`ok`, `status`). A `cp-callback://` URI cannot
be answered here (an HTTP framing has no envelope channel to answer it and
§6.1 forbids stdout after `ready`): it logs the named refusal and answers
500 — an honest refusal, never a silent pass.

No verifier-specific logic, no op bodies: one envelope projection per row.
No rm anywhere (Addendum v16).
"""

import json
import os
import socket
import subprocess
import sys
import threading
import time
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

CP = "0"

# the census CP/stdio seat launcher (the JVM seat this bridge reframes)
HERE = os.path.dirname(os.path.abspath(__file__))
SDK_ROOT = os.path.dirname(os.path.dirname(HERE))
SEAT_LAUNCH = os.path.join(HERE, "seat-serve.sh")

# one op's stdio envelope round-trip budget: the seat's own CP op timeouts
# price inside their calls; the harness drives serially under its own guard
OP_WAIT_S = float(os.environ.get("CP_BRIDGE_OP_WAIT_S") or 300)


def _log(message):
    sys.stderr.write("[cp-seat-http] %s\n" % message)
    sys.stderr.flush()


def is_loopback(host):
    """The seat's own rule-5a law: a listener binds loopback only, ever."""
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        return False
    for _family, _type, _proto, _canonname, sockaddr in infos:
        ip = sockaddr[0]
        if ip == "::1" or ip.startswith("127."):
            return True
    return False


class SeatBridge:
    """One CP/stdio child (the census JVM seat) + its envelope wire."""

    def __init__(self):
        self._lock = threading.Lock()
        self._next_id = 0
        self._pending = {}   # id -> Event awaiting the reply
        self._replies = {}   # id -> the seat's reply envelope
        self.child = subprocess.Popen(
            [SEAT_LAUNCH],
            cwd=SDK_ROOT,
            # the child's stderr stays this process's stderr (the grader's
            # service-stderr receipt); NEVER stdout (CP-SPEC 5.1)
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=None)
        _log("seat child pid %d via %s" % (self.child.pid, SEAT_LAUNCH))
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()

    def call(self, op, body=None, wait_s=OP_WAIT_S):
        """One runner->service op, verbatim body; returns the reply envelope
        (the seat's own seven-key response) or None on wire death."""
        with self._lock:
            self._next_id += 1
            rid = self._next_id
            evt = threading.Event()
            self._pending[rid] = evt
        self._send({"cp": CP, "id": rid, "op": op, "body": body})
        got = evt.wait(wait_s)
        with self._lock:
            self._pending.pop(rid, None)
            reply = self._replies.pop(rid, None)
        if not got or reply is None:
            _log("op %s id %d priced no reply inside %ss (seat exit %s)"
                 % (op, rid, wait_s, self.child.poll()))
            return None
        return reply

    def _send(self, payload):
        try:
            self.child.stdin.write((json.dumps(payload, separators=(",", ":"),
                                               ensure_ascii=True)
                                    + "\n").encode("utf-8"))
            self.child.stdin.flush()
        except (OSError, ValueError) as exc:
            _log("seat stdin write refused (%r); the child priced exit %s"
                 % (exc, self.child.poll()))

    def answer_callback(self, envelope_id, status):
        """The runner's callback reply: echoes the id and carries ok/status
        (the seat's wheel reads exactly those)."""
        ok = 200 <= status <= 299
        self._send({"cp": CP, "id": envelope_id, "op": "callback",
                    "ok": ok, "status": int(status), "body": None,
                    "error": None if ok else ("callback priced %d" % status)})

    def close(self):
        try:
            self.child.stdin.close()
        except (OSError, ValueError):
            pass

    # ------------------------------------------------------------ reader
    def _read_loop(self):
        try:
            for raw in iter(self.child.stdout.readline, b""):
                line = raw.decode("utf-8", "replace").strip()
                if not line:
                    continue
                try:
                    envelope = json.loads(line)
                except ValueError:
                    _log("unparseable seat line (dropped): %s" % line[:96])
                    continue
                op = envelope.get("op")
                if op == "ready":
                    # the JVM seat's own stdio ready envelope: swallowed here
                    # (the bridge names its own CP/http ready; the harness
                    # never sees the child's stdio wire)
                    continue
                if op == "callback":
                    self._transcode_callback(envelope)
                    continue
                rid = envelope.get("id")
                if rid is None:
                    _log("id-less seat line (dropped): %s" % line[:96])
                    continue
                with self._lock:
                    self._replies[rid] = envelope
                    evt = self._pending.get(rid)
                if evt is not None:
                    evt.set()
        finally:
            _log("seat child stdout closed (exit %s)" % self.child.poll())
            with self._lock:
                for evt in list(self._pending.values()):
                    evt.set()

    def _transcode_callback(self, envelope):
        """CP-SPEC 6.3 + 7.2: the payload POSTs to the runner's own
        callbackUri URL (+ path); the runner's HTTP status is the answer."""
        body = envelope.get("body") or {}
        envelope_id = envelope.get("id")
        uri = body.get("uri") or ""
        path = body.get("path") or ""
        if uri.startswith("cp-callback://"):
            _log("cp-callback URI priced under CP/http (the named refusal: "
                 "an HTTP framing has no envelope channel to answer it)")
            if envelope_id is not None:
                self.answer_callback(envelope_id, 500)
            return
        payload = body.get("payload")
        data = json.dumps(payload, separators=(",", ":"),
                          ensure_ascii=True).encode("utf-8") \
            if payload is not None else b""
        parsed = urlparse(uri)
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        status = 500
        try:
            conn = HTTPConnection(parsed.hostname, port, timeout=10)
            try:
                conn.request("POST", (parsed.path or "/") + path, body=data,
                             headers={"Content-Type": "application/json",
                                      "Content-Length": str(len(data))})
                resp = conn.getresponse()
                status = resp.status
                resp.read()
            finally:
                conn.close()
        except Exception as exc:
            _log("callback dial %s%s failed: %r" % (uri, path, exc))
        if envelope_id is not None:
            self.answer_callback(envelope_id, status)


# ---------------------------------------------------------------- HTTP face

class Handler(BaseHTTPRequestHandler):
    """One CP/http request projected onto the seat's stdio answers. The
    envelope carries the status code, the body and the error text; this
    handler only carries them across (transport-only, no op bodies)."""

    protocol_version = "HTTP/1.1"

    @property
    def seat(self):
        return self.server.seat

    def _read_body(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        return self.rfile.read(n) if n > 0 else b""

    def _respond(self, status, body=None, error=None, location=None):
        ok = 200 <= status <= 299
        if ok and body is not None:
            content_type = "application/json"
            payload = json.dumps(body, separators=(",", ":"),
                                 ensure_ascii=True).encode("utf-8")
        elif ok:
            content_type = None
            payload = b""
        else:
            content_type = "text/plain"
            payload = (error if isinstance(error, str) else "error") \
                .encode("utf-8")
        self.send_response(status)
        if content_type:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        if location:
            self.send_header("Location", location)
        self.send_header("Connection", "close")
        self.end_headers()
        if payload:
            self.wfile.write(payload)
        self.wfile.flush()

    def _reply_from(self, reply):
        if reply is None:
            self._respond(502, None, "the CP seat priced no reply")
            return
        status = int(reply.get("status", 500))
        if reply.get("ok"):
            body = reply.get("body")
            self._respond(status, body)
        else:
            self._respond(status, None, reply.get("error") or "error")

    def _path_only(self):
        return self.path.split("?", 1)[0]

    def _client_id(self):
        parts = self._path_only().split("/", 2)
        if len(parts) == 3 and parts[1] == "clients" and parts[2]:
            return parts[2]
        return None

    def do_GET(self):
        if self._path_only() == "/":
            self._reply_from(self.seat.call("status"))
            return
        self._respond(404, None, "not found")

    def do_POST(self):
        if self._path_only() != "/":
            cid = self._client_id()
            if cid is None:
                self._respond(404, None, "not found")
                return
            raw = self._read_body()
            try:
                request = json.loads(raw.decode("utf-8")) if raw else {}
            except (ValueError, UnicodeDecodeError):
                self._respond(400, None, "bad json")
                return
            self._reply_from(self.seat.call(
                "command", {"client": cid, "request": request}))
            return
        raw = self._read_body()
        try:
            payload = json.loads(raw.decode("utf-8")) if raw else None
        except (ValueError, UnicodeDecodeError):
            self._respond(400, None, "bad json")
            return
        reply = self.seat.call("createClient", payload)
        cid = (reply or {}).get("body", {}).get("client") \
            if reply and reply.get("ok") else None
        if cid:
            self._respond(201, None, None, location="/clients/%s" % cid)
        else:
            self._reply_from(reply)

    def do_DELETE(self):
        if self._path_only() == "/":
            self._reply_from(self.seat.call("stop"))
            time.sleep(0.05)
            _log("stop requested (DELETE /): exiting")
            self.seat.close()
            os._exit(0)
        cid = self._client_id()
        if cid is None:
            self._respond(404, None, "not found")
            return
        self._reply_from(self.seat.call("closeClient", {"client": cid}))


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def run():
    framing = os.environ.get("CP_FRAMING")
    version = os.environ.get("CP_VERSION")
    if version not in (None, "0"):
        sys.stderr.write("cp: CP_VERSION=%s not supported by this seat\n"
                         % version)
        return 2
    if framing not in (None, "http"):
        sys.stderr.write("cp: cp-seat's serve_http implements CP/http only "
                         "(CP_FRAMING=%r)\n" % framing)
        return 2
    host = os.environ.get("CP_HTTP_HOST") or "127.0.0.1"
    if not is_loopback(host):
        # rule 5a: a listener binds loopback only, ever
        sys.stderr.write("cp: refusing non-loopback bind %r\n" % host)
        return 2
    if host == "localhost":
        host = "127.0.0.1"
    try:
        port = int(os.environ.get("CP_HTTP_PORT") or 0)
    except ValueError:
        port = 0
    if ":" in host:
        Server.address_family = socket.AF_INET6
    if not os.path.isfile(SEAT_LAUNCH):
        sys.stderr.write("cp: the census seat launcher is absent: %s\n"
                         % SEAT_LAUNCH)
        return 2
    seat = SeatBridge()
    server = Server((host, port), Handler)
    server.seat = seat
    address = server.server_address
    ip, port = address[0], address[1]
    # the R5-SERVICE-LISTEN announce (the harness-protocol face: the public
    # harness driver waits for this line on stdout), then the CP ready
    # envelope (§6.1: REQUIRED under CP/http, id 0; stdout stops after it)
    url_host = "[%s]" % ip if ":" in ip else ip
    announce = "R5-SERVICE-LISTEN %s:%d" % (ip, port)
    ready = {"cp": CP, "id": 0, "op": "ready",
             "body": {"framing": "http",
                      "url": "http://%s:%d" % (url_host, port)}}
    sys.stdout.write(announce + "\n")
    sys.stdout.write(json.dumps(ready, separators=(",", ":"),
                                ensure_ascii=True) + "\n")
    sys.stdout.flush()
    _log("listening http://%s:%d (transport-only; ops ride the census JVM "
         "seat %s; CP_HTTP_PORT=%d)" % (url_host, port, SEAT_LAUNCH, port))
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(run())
