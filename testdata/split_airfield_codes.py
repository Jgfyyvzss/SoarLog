"""
One-off: split SoarLog airfield names of the form "XXXX - Airport Name" into code XXXX and name "Airport Name".

Usage:  python -I split_airfield_codes.py <backup.zip> [<output.zip>]

Reads backup.json from the SoarLog backup zip, changes only live airfields (not deleted) that have no code yet and
whose name is exactly four letters, a hyphen and the rest. It sets name, name_key (trimmed, lower case) and code
(capitals) and sets updated_at to now. Ids are unchanged, so flights and defaults stay attached.
Every change is listed. If any two live airfields would share a name or a code, nothing is written.
The output defaults to <backup>-split.zip beside the input; an existing file is never overwritten.
Image entries are copied unchanged.
"""
import json
import re
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

JSON_ENTRY = "backup.json"
PATTERN = re.compile(r"^([A-Za-z]{4})\s*-\s*(\S.*)$")


def name_key(name: str) -> str:
    return name.strip().lower()


def main() -> int:
    if len(sys.argv) not in (2, 3):
        print(__doc__)
        return 2
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2]) if len(sys.argv) == 3 else src.with_name(src.stem + "-split.zip")
    if dst.exists():
        print(f"Output already exists, not overwriting: {dst}")
        return 1

    with zipfile.ZipFile(src) as zin:
        data = json.loads(zin.read(JSON_ENTRY).decode("utf-8"))
    airfields = data.get("airfields")
    if not isinstance(airfields, list):
        print("No airfields list in backup.json; is this a SoarLog backup?")
        return 1

    t = datetime.now(timezone.utc)
    now = t.strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}Z"
    changes, untouched, odd = [], [], []
    for a in airfields:
        if a.get("deleted_at") is not None:
            continue
        old = a["name"]
        m = PATTERN.match(old.strip())
        if a.get("code"):
            untouched.append(f"{old}  (already has code {a['code']})")
        elif m:
            code, name = m.group(1).upper(), m.group(2).strip()
            a["code"], a["name"], a["name_key"], a["updated_at"] = code, name, name_key(name), now
            changes.append(f"{old!r:45} -> code {code}, name {name!r}")
        else:
            untouched.append(old)
            if "-" in old:
                odd.append(old)

    live = [a for a in airfields if a.get("deleted_at") is None]
    clashes = []
    for label, key in (("name", lambda a: a["name_key"]), ("code", lambda a: (a.get("code") or "").strip().upper())):
        seen = {}
        for a in live:
            k = key(a)
            if not k:
                continue
            if k in seen:
                clashes.append(f"Two airfields would share the {label} {k!r}: {seen[k]!r} and {a['name']!r}")
            else:
                seen[k] = a["name"]

    print(f"Changed ({len(changes)}):")
    for c in changes:
        print("  " + c)
    print(f"Left as they are ({len(untouched)}):")
    for u in untouched:
        print("  " + u)
    if odd:
        print("Warning, these contain a hyphen but did not match 'XXXX - Name':")
        for o in odd:
            print("  " + o)
    if clashes:
        print("Nothing written, because:")
        for c in clashes:
            print("  " + c)
        return 1
    if not changes:
        print("Nothing to change; nothing written.")
        return 0

    out_json = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "x") as zout:
        for info in zin.infolist():
            if info.filename == JSON_ENTRY:
                zout.writestr(zipfile.ZipInfo(JSON_ENTRY, info.date_time), out_json, compress_type=zipfile.ZIP_DEFLATED)
            else:
                zout.writestr(info, zin.read(info.filename), compress_type=info.compress_type)
    print(f"Written: {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
