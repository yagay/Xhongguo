#!/usr/bin/env python3
"""Read only APK DEX symbol definitions. Never upload APK or extract credentials."""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

SPEC = Path(__file__).resolve().parents[1] / "tools" / "apk_contract_74232.json"

def parse_dex(data, wanted):
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a standard DEX")
    u32 = lambda p: struct.unpack_from("<I", data, p)[0]
    u16 = lambda p: struct.unpack_from("<H", data, p)[0]
    def leb(pos):
        value = shift = 0
        for _ in range(5):
            byte = data[pos]
            pos += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, pos
            shift += 7
        raise ValueError("Invalid ULEB128")
    sn, so, tn, to, pn, po, fn, fo, mn, mo, cn, co = struct.unpack_from("<12I", data, 56)
    strings = []
    for index in range(sn):
        _, pos = leb(u32(so + index * 4))
        strings.append(data[pos:data.index(0, pos)].decode("utf-8", "replace"))
    types = [strings[u32(to + index * 4)] for index in range(tn)]
    def proto(index):
        pos = po + index * 12
        params = u32(pos + 8)
        args = "".join(types[u16(params + 4 + 2*j)] for j in range(u32(params))) if params else ""
        return "(" + args + ")" + types[u32(pos + 4)]
    result = {}
    for i in range(cn):
        pos = co + 32*i
        name = types[u32(pos)]
        if name not in wanted:
            continue
        methods, fields = set(), set()
        offset = u32(pos + 24)
        if offset:
            counts = []
            for _ in range(4):
                size, offset = leb(offset)
                counts.append(size)
            for size in counts[:2]:
                index = 0
                for _ in range(size):
                    delta, offset = leb(offset)
                    index += delta
                    flags, offset = leb(offset)
                    p = fo + index * 8
                    fields.add(strings[u32(p+4)] + ":" + types[u16(p+2)])
            for size in counts[2:]:
                index = 0
                for _ in range(size):
                    delta, offset = leb(offset)
                    index += delta
                    flags, offset = leb(offset)
                    _, offset = leb(offset)
                    p = mo + index * 8
                    methods.add(strings[u32(p+4)] + proto(u16(p+2)))
        result[name] = (methods, fields)
    return cn, result

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--strict-reference", action="store_true")
    args = parser.parse_args()
    spec = json.loads(SPEC.read_text())
    wanted = set(spec["methods"]) | set(spec["fields"]) | set(spec["classes"])
    found = {}
    definitions = 0
    with args.apk.open("rb") as stream:
        sha = hashlib.file_digest(stream, "sha256").hexdigest()
    with zipfile.ZipFile(args.apk) as archive:
        dexes = [n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        for dex in dexes:
            count, present = parse_dex(archive.read(dex), wanted)
            definitions += count
            found.update(present)
    missing = [f"class {name}" for name in sorted(wanted - found.keys())]
    for key, index in (("methods", 0), ("fields", 1)):
        for name, signatures in spec[key].items():
            actual = found.get(name, (set(), set()))[index]
            missing.extend(f"{name} {s}" for s in signatures if s not in actual)
    report = {"sha256": sha, "dex_count": len(dexes), "class_definitions": definitions,
              "matched_classes": len(found), "expected_classes": len(wanted), "missing": missing}
    print(json.dumps(report, indent=2))
    if args.strict_reference and sha != spec["reference"]["sha256"]:
        raise SystemExit("APK SHA-256 differs from the reference")
    raise SystemExit(1 if missing else 0)

if __name__ == "__main__":
    main()
