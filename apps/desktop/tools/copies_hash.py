#!/usr/bin/env python3
"""Shows or updates the Android-original hashes in apps/desktop/COPIES.md (the file CopiesTest reads).

    python3 apps/desktop/tools/copies_hash.py                         state of every row (ok / CHANGED / MISSING)
    python3 apps/desktop/tools/copies_hash.py --update <desktop file> record the Android original's hash for that row

Exit status: 1 when a row is not ok (status) or an original is missing (--update).
Only update a row after you re-checked the desktop copy against the Android change. Hash rule (same as CopiesTest): SHA-256 of the
Android file(s) in the listed order, concatenated, with "\\r" removed, first 16 hex digits.
"""
import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]   # apps/: the paths in COPIES.md are relative to it
DOC = ROOT / "desktop" / "COPIES.md"


def digest(paths):
    data = b"".join((ROOT / p).read_bytes().replace(b"\r", b"") for p in paths)
    return hashlib.sha256(data).hexdigest()[:16]


def main(argv):
    update = argv[1:] and argv[1] == "--update"
    targets = set(argv[2:]) if update else set()
    if update and not targets:
        sys.exit("--update needs the desktop file path(s) as in the first column of COPIES.md")
    lines = DOC.read_text(encoding="utf-8").split("\n")
    seen = set()
    not_ok = 0
    for i, line in enumerate(lines):
        if not line.startswith("| desktop/"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 5:
            sys.exit(f"apps/desktop/COPIES.md line {i + 1}: a row needs 5 cells: {line}")
        path, originals, cls, recorded = cells[0], [x.strip() for x in cells[1].split(",") if x.strip()], cells[2], cells[3]
        if cls != "edits" or (update and path not in targets):
            continue
        gone = next((x for x in originals if not (ROOT / x).is_file()), None)
        if gone is not None:
            if update:
                sys.exit(f"original missing: {gone} \u2014 fix the path in COPIES.md first")
            print(f"MISSING  {path}  ({gone} is gone: it moved or was renamed; fix the row's Android path)")
            not_ok += 1
            continue
        now = digest(originals)
        if update:
            lines[i] = line.replace(f"| {recorded} |", f"| {now} |", 1)
            seen.add(path)
            print(f"updated  {path}  {recorded} -> {now}")
        else:
            ok = now == recorded
            not_ok += not ok
            print(f"{'ok      ' if ok else 'CHANGED '}{path}  recorded {recorded}  now {now}")
    if update:
        missing = targets - seen
        if missing:
            sys.exit("no such 'edits' row: " + ", ".join(sorted(missing)))
        DOC.write_text("\n".join(lines), encoding="utf-8")
    elif not_ok:
        sys.exit(f"{not_ok} row(s) not ok")


main(sys.argv)
