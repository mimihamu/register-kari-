from pathlib import Path
import base64
import gzip
import hashlib

parts = [
    Path(f"tools/v053_apply.gzpart{index:02d}.b64")
    for index in range(9)
] + [
    Path("tools/v053_apply.gzpart09a.b64"),
    Path("tools/v053_apply.gzpart09b.b64"),
    Path("tools/v053_apply.gzpart09c.b64"),
    Path("tools/v053_apply.gzpart09d.b64"),
]
encoded = "".join(part.read_text(encoding="utf-8") for part in parts).encode("ascii")
if hashlib.sha256(encoded).hexdigest() != "bfe8495b81da313bbef0d0d1ad73302a1c851e2d5242613c0c19f1435ff40ba1":
    raise RuntimeError("v0.53 compressed applicator checksum mismatch")
raw = gzip.decompress(base64.b64decode(encoded, validate=True))
if hashlib.sha256(raw).hexdigest() != "96eba78b99fe84c5fc46d4561b07c0beae4713d9d3c2c6c985e9c15da5aaae1e":
    raise RuntimeError("v0.53 applicator checksum mismatch")
for pattern in ("v053_apply.part*.b64", "v053_apply.gzpart*.b64"):
    for path in Path("tools").glob(pattern):
        path.unlink()
exec(compile(raw, "v053_apply_payload.py", "exec"))
