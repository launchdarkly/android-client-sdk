"""Interactive-hook driver: answers EVERY callback the seat emits, then asserts
the full stage sequence (beforeEvaluation + afterEvaluation) and the command
response. Mirrors the grader's own callback pacing (stdin keeps reading while a
command holds the worker)."""
import json, subprocess, threading, time

proc = subprocess.Popen(
    ["/home/termag/termag/projects/android-client-sdk/tools/cp-seat/seat-serve.sh"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE,
    stderr=open("/tmp/hooks-stderr.txt", "w"), text=True, bufsize=1)
lines = []
lock = threading.Lock()
answered = set()

def route_and_answer():
    for line in lines:
        try:
            e = json.loads(line)
        except Exception:
            continue
        if e.get("op") == "callback" and isinstance(e.get("id"), int):
            with lock:
                if e["id"] in answered:
                    continue
                answered.add(e["id"])
            proc.stdin.write(json.dumps({"cp": "0", "id": e["id"], "op": "callback",
                                         "ok": True, "status": 200, "body": None}) + "\n")
            proc.stdin.flush()

threading.Thread(target=route_and_answer, daemon=True).start()

responses = {}
rid = {"i": 0}
def send(op, body=None):
    rid["i"] += 1
    proc.stdin.write(json.dumps({"cp": "0", "id": rid["i"], "op": op, "body": body}) + "\n")
    proc.stdin.flush()
    return rid["i"]

def wait_for(rv, timeout=30.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for line in list(lines):
            try:
                e = json.loads(line)
            except Exception:
                continue
            if e.get("id") == rv and e.get("op") != "callback" and ("ok" in e or "status" in e):
                return e
        time.sleep(0.05)
    return None

send("status"); wait_for(1)
cfg = {"tag": "h", "configuration": {
    "credential": "mob-key", "startWaitTimeMs": 8000,
    "serviceEndpoints": {"streaming": "http://127.0.0.1:18099", "polling": "http://127.0.0.1:18099",
                         "events": "http://127.0.0.1:18099"},
    "hooks": {"hooks": [{"name": "hh", "callbackUri": "cp-callback://hook/t1",
                          "data": {"beforeEvaluation": {"seeded": "data"}}}]},
    "clientSide": {"initialContext": {"kind": "user", "key": "hu", "anonymous": False}},
    "polling": {"baseUri": "http://127.0.0.1:18099", "pollIntervalMs": 100},
    "events": None}}
r2 = send("createClient", cfg); e2 = wait_for(r2)
print("CREATE:", (e2 or {}).get("ok"))
r3 = send("command", {"client": e2["body"]["client"],
    "request": {"command": "evaluate",
                "evaluate": {"flagKey": "a-flag", "valueType": "string", "defaultValue": "off", "detail": True}}})
e3 = wait_for(r3)
print("CMD:", json.dumps((e3 or {}).get("body"))[:170])

stages = []
deadline = time.time() + 3
while time.time() < deadline:
    try:
        with lock:
            stages = [json.loads(l)["body"]["payload"].get("stage")
                      for l in lines if '"op":"callback"' in l and json.loads(l).get("body", {}).get("payload")]
    except Exception:
        pass
    time.sleep(0.05)
print("STAGES:", stages)

send("stop"); time.sleep(2)
proc.kill()
ok = e2 and e2.get("ok") and e3 and e3.get("ok") and set(stages) >= {"beforeEvaluation", "afterEvaluation"}
print("HOOK-WHEEL", "OK" if ok else "INCOMPLETE")
