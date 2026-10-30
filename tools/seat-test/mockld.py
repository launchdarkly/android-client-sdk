"""Minimal LaunchDarkly mobile wire mock for the SDK init smoke test (loopback only)."""
import http.server
import json

FLAGS = {
    "a-flag": {
        "key": "a-flag", "version": 3, "on": True, "value": "on",
        "variations": ["off", "on"], "default": 0,
    }
}


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        p = self.path.split("?")[0]
        try:
            if p.startswith("/msdk/evalx/contexts"):
                # LD mobile /msdk/evalx responses are a flat map: flagKey -> Flag object
                body = json.dumps(FLAGS).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            else:
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
        except Exception:
            pass

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    http.server.ThreadingHTTPServer(("127.0.0.1", 18099), Handler).serve_forever()
