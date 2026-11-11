#!/usr/bin/env python3
"""Removes coverage-instrumented iron-mcp-core classes from sbt 2's caches.

sbt 2 keeps compiled classes in content-addressed caches: ~/.cache/sbt/v2 (shared across checkouts) and
target/bootcache (this checkout's; target/out's class files are symlinks into it). A coverage run's instrumented
compile is cached there under its own key (scripts/coverage.sh adds a per-run nonce), so ordinary builds never get
it, but nothing would ever reuse it either. scripts/coverage.sh runs this afterwards to remove those entries, and it
is the repair should an instrumented compile ever be cached under an ordinary key.

Removes each blob holding an instrumented class (a class file, or a whole output directory zipped), every blob that
lists one (a compile's output manifest), and every action entry pointing at such a manifest. The next ordinary build
is then a cache miss and compiles plain classes.
"""
import io
import os
import sys
import zipfile

NEEDLE = b"iron-mcp-core/scoverage-data"


def purge(store: str) -> int:
    cas, ac = os.path.join(store, "cas"), os.path.join(store, "ac")
    if not os.path.isdir(cas):
        return 0
    def read(path: str) -> bytes:
        with open(path, "rb") as f:
            return f.read()
    def instrumented(path: str) -> bool:
        data = read(path)
        if NEEDLE in data:
            return True
        if data[:2] == b"PK":  # a whole output directory, stored as <dir>.sbtdir.zip
            try:
                archive = zipfile.ZipFile(io.BytesIO(data))
                return any(NEEDLE in archive.read(n) for n in archive.namelist() if n.endswith(".class"))
            except zipfile.BadZipFile:
                return False
        return False
    blobs = [os.path.join(cas, f) for f in os.listdir(cas) if instrumented(os.path.join(cas, f))]
    hashes = [os.path.basename(b).split("-")[1].encode() for b in blobs]
    manifests = [os.path.join(cas, f) for f in os.listdir(cas)
                 if os.path.join(cas, f) not in blobs and any(h in read(os.path.join(cas, f)) for h in hashes)]
    manifest_hashes = [os.path.basename(m).split("-")[1].encode() for m in manifests]
    actions = [os.path.join(ac, f) for f in os.listdir(ac) if any(h in read(os.path.join(ac, f)) for h in manifest_hashes)] \
        if os.path.isdir(ac) else []
    for path in blobs + manifests + actions:
        os.remove(path)
    if blobs:
        print(f"{store}: removed {len(blobs)} instrumented blobs, {len(manifests)} manifests, {len(actions)} action entries")
    return len(blobs)


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    stores = [os.path.expanduser("~/.cache/sbt/v2"), os.path.join(root, "target", "bootcache")]
    found = sum(purge(s) for s in stores)
    print("caches hold no instrumented classes" if found == 0 else "purged")
    sys.exit(0)
