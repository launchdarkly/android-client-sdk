"""Paced shell-exercise of the android cp-seat (status, createClient, evaluate, close)."""
import json, subprocess, sys, time, os

CP = "/home/termag/termag/projects/android-client-sdk/tools/cp-seat/seat-serve.sh"

def envelope(rid, op, body=None):
    return json.dumps({"cp": "0", "id": rid, "op": op, "body": body})

def main():
    proc = subprocess.Popen([CP], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=open("/tmp/seat-driver-stderr.txt", "w"), text=True, bufsize=1)
    # read raw response lines in a thread
    lines = []
    import threading
    def reader():
        for line in proc.stdout:
            lines.append(line.rstrip())
    t = threading.Thread(target=reader, daemon=True)
    t.start()

    def send(op, body=None, wait=8.0, name=""):
        proc.stdin.write(envelope(next(SENDER), op, body) + "\n")
        proc.stdin.flush()
        deadline = time.time() + wait
        got = None
        while time.time() < deadline:
            for l in lines:
                try:
                    e = json.loads(l)
                except Exception:
                    continue
                matches = (e.get("id") == SENDER.last_sent) if False else False
            # simple: find envelope responses not yet consumed
            if pending():
                got = lines[-1]
                break
            time.sleep(0.05)
        return got

    # easier: tag responses by request id, consume on demand
    consumed = []
    def next_resp(rid, wait=8.0):
        deadline = time.time() + wait
        while time.time() < deadline:
            for i, l in enumerate(lines):
                try:
                    e = json.loads(l)
                except Exception:
                    continue
                if e.get("id") == rid and i not in consumed:
                    consumed.append(i)
                    return e
            time.sleep(0.05)
        return None

    SENDER = {"i": 0}
    def send(op, body=None):
        SENDER["i"] += 1
        proc.stdin.write(envelope(SENDER["i"], op, body) + "\n")
        proc.stdin.flush()
        return SENDER["i"]

    r1 = send("status")
    e1 = next_resp(r1)
    print("STATUS:", e1.get("ok"), e1.get("body", {}).get("clientVersion"), len(e1.get("body", {}).get("capabilities", [])), "caps")

    cfg = {
        "tag": "smoke",
        "configuration": {
            "credential": "mob-key",
            "startWaitTimeMs": 8000,
            "serviceEndpoints": {"streaming": "http://127.0.0.1:18099", "polling": "http://127.0.0.1:18099", "events": "http://127.0.0.1:18099"},
            "clientSide": {"initialContext": {"kind": "user", "key": "u1", "anonymous": False}},
            "polling": {"baseUri": "http://127.0.0.1:18099", "pollIntervalMs": 100},
            "events": None,
        },
    }
    r2 = send("createClient", cfg)
    e2 = next_resp(r2, 15)
    print("CREATE:", json.dumps(e2)[:120])

    r3 = send("command", {"client": e2["body"]["client"],
                          "request": {"command": "evaluate",
                                      "evaluate": {"flagKey": "a-flag", "valueType": "string",
                                                   "defaultValue": "off", "detail": True}}})
    e3 = next_resp(r3, 10)
    print("EVALUATE:", json.dumps(e3.get("body"))[:220])
    ok = e1 and e1.get("ok") and e2 and e2.get("ok") and e3 and e3.get("ok") and not e3["body"].get("error")
    r4 = send("stop")
    next_resp(r4, 5)
    proc.wait(timeout=15)
    print("EVALUATE-CLAIM-OK" if ok else "EVALUATE-CLAIM-FAIL")

if __name__ == "__main__":
    main()
