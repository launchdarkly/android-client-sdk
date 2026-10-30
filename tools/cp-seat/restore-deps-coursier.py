#!/usr/bin/env python3
"""restore-deps-coursier.py — re-fetch the seat's coursier-resolved
third-party classpath into the default coursier cache (the v1 layout the
classpath-deps.txt paths are baked against) after a cache wipe.

  INPUT: tools/cp-seat/classpath-deps.txt (the 51 resolved jar paths)
  ACT:   for each jar path, recover (repo, coords) from the cache layout
         v1/https/<host>/<repo-path>/<path>, and drive
         java -jar /home/termag/termag/tools/coursier.jar fetch --repo
         "<maven repo mirror of the cache layout>" <group:artifact:version>
         so the same v1 path re-materializes.
  FAIL-CLOSED: any jar still absent after its fetch prices rc 2 named.

One finite process; it touches the network only via coursier's own
fetches (the apt recipe's own law: d0524).
"""
import os
import subprocess
import sys

Deps = "/home/termag/termag/projects/android-client-sdk/tools/cp-seat/classpath-deps.txt"
CS_JAR = "/home/termag/termag/tools/coursier.jar"
CACHE = os.path.expanduser("~/.cache/coursier/v1")


def coords_of(jar_path):
    """Recover (group, artifact, version, repo, host, rest) from the v1
    cache layout path:
      v1/https/<host>/<sitepath>/maven2/<group dirs>/<artifact>/<version>/<file>
    dl.google's site path carries maven2 at dl/android/maven2.
    """
    rel = (os.path.relpath(jar_path, CACHE)
           if jar_path.startswith(CACHE) else jar_path)
    rel = rel[len("https/"):] if rel.startswith("https/") else rel
    host, _, rest = rel.partition("/")
    if rest.startswith("maven2/"):
        rest = rest[len("maven2/"):]
    elif rest.startswith("dl/android/maven2/"):
        rest = rest[len("dl/android/maven2/"):]
    else:
        return None, None, None, None, host, rest
    repo = ("https://dl.google.com/dl/android/maven2"
            if host == "dl.google.com" else "https://repo1.maven.org/maven2")
    parts = rest.split("/")
    if len(parts) < 4:
        return None, None, None, None, host, rest
    group = ".".join(parts[:-3])
    artifact = parts[-3]
    version = parts[-2]
    return group, artifact, version, repo, host, rest


def main():
    with open("/home/termag/termag/projects/android-client-sdk/"
              "tools/cp-seat/classpath-deps.txt", encoding="utf-8") as f:
        text = f.read().strip()
    jars = [p for p in text.split(":") if p]
    print("classpath entries:", len(jars))
    bad = {}
    seen = set()
    for j in jars:
        if j in seen:
            continue
        seen.add(j)
        exists = os.path.isfile(j)
        if exists:
            print("OK-kept:", os.path.basename(j))
            continue
        group, artifact, version, repo, host, rest = coords_of(j)
        if not (group and artifact and version and repo):
            print("PROBLEM: unresolved coords for", j)
            bad[j] = "coords"
            continue
        print("fetch: %s:%s:%s from %s (%s)" % (group, artifact, version,
                                                repo, host))
        proc = subprocess.run(
            ["/home/termag/termag/tools/jdk-17.0.20.1+1/bin/java", "-jar",
             CS_JAR, "fetch", "-p", "-r", repo,
             "--intransitive",
             "%s:%s:%s" % (group, artifact, version)],
            capture_output=True, text=True)
        if proc.returncode != 0:
            print("  fetch refused rc=%d: %s" % (
                proc.returncode, (proc.stderr or proc.stdout).strip()[-200:]))
            bad[j] = "fetch"
            continue
        if not os.path.isfile(j):
            print("  PROBLEM: fetch reported ok but %s still absent" % j)
            bad[j] = "post-missing"
    if bad:
        print("PROBLEM: %d jar(s) still unmaterialized" % len(bad))
        for k, v in sorted(bad.items())[:10]:
            print("  %s (%s)" % (k, bad[k]))
        return 2
    print("all classpath jars materialized:", len(seen))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
