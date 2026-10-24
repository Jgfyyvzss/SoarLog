#!/usr/bin/env python3
"""Convert a SeeYou .cup waypoint file into an airfields CSV for import into the logbook app.

Usage:
    python3 cup_to_airfields.py INPUT.cup [-o airfields.csv] [options]

Output columns (the app's airfields.csv format):
    name, latitude, longitude, elevation_m, match_radius_m, clubs

Latitude and longitude are signed decimal degrees (south and west negative).
Elevation is whole metres. match_radius_m and clubs are left empty unless you
ask for them with --radius and --club.

Only the waypoint section of the CUP file is read. Everything after a line
starting with "-----Related Tasks-----" is ignored, as are columns the app
does not use (style, runway, frequency, description and so on).

The app skips airfields whose name already exists, so this script keeps only
the first of any rows that end up with the same output name and says so.
"""
import argparse
import csv
import io
import re
import sys

COORD = re.compile(r"^(\d{2,3})(\d{2}\.\d+)([NSEW])$")
ELEV = re.compile(r"^\s*(-?\d+(?:\.\d+)?)\s*(m|ft)?\s*$", re.IGNORECASE)
FT_TO_M = 0.3048


def read_text(path):
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "cp1252"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return raw.decode("latin-1")


def parse_coord(text, axis):
    """DDMM.mmmN / DDDMM.mmmE to signed decimal degrees."""
    m = COORD.match(text.strip())
    if not m:
        raise ValueError("unreadable %s %r" % (axis, text))
    deg, minutes, hemi = int(m.group(1)), float(m.group(2)), m.group(3)
    if minutes >= 60:
        raise ValueError("minutes out of range in %r" % text)
    value = deg + minutes / 60.0
    if hemi in "SW":
        value = -value
    limit = 90 if axis == "latitude" else 180
    if hemi in ("N", "S") and axis != "latitude" or hemi in ("E", "W") and axis != "longitude":
        raise ValueError("%s has the wrong hemisphere letter: %r" % (axis, text))
    if abs(value) > limit:
        raise ValueError("%s out of range: %r" % (axis, text))
    return value


def parse_elevation(text):
    m = ELEV.match(text)
    if not m:
        raise ValueError("unreadable elevation %r" % text)
    value = float(m.group(1))
    if (m.group(2) or "m").lower() == "ft":
        value *= FT_TO_M
    return int(round(value))


def tidy_case(name):
    """Title-case names that are all upper or all lower case; leave mixed case alone."""
    letters = [c for c in name if c.isalpha()]
    if letters and (all(c.isupper() for c in letters) or all(c.islower() for c in letters)):
        return " ".join(w[:1].upper() + w[1:].lower() if w else w for w in name.split(" "))
    return name


def build_name(waypoint, code, fmt):
    if fmt == "name" or not code:
        return waypoint
    if fmt == "code":
        return code
    if code.casefold() == waypoint.casefold():
        return code  # "SANDIA - SANDIA" is just noise
    return "%s - %s" % (code, waypoint)


def convert(text, fmt, tidy, radius, club, warn):
    rows, seen = [], {}
    reader = csv.reader(io.StringIO(text))
    for line_no, rec in enumerate(reader, 1):
        if not rec or not any(c.strip() for c in rec):
            continue
        first = rec[0].strip()
        if first.startswith("-----"):  # task section: stop
            break
        if len(rec) < 6:
            warn("line %d: only %d fields, skipped" % (line_no, len(rec)))
            continue
        if line_no == 1 and rec[3].strip().lower() in ("lat", "latitude"):
            continue  # header row
        waypoint, code = rec[0].strip(), rec[1].strip()
        try:
            lat = parse_coord(rec[3], "latitude")
            lon = parse_coord(rec[4], "longitude")
            elev = parse_elevation(rec[5])
        except ValueError as e:
            warn("line %d (%s): %s, skipped" % (line_no, waypoint or code, e))
            continue
        if not waypoint and not code:
            warn("line %d: no name or code, skipped" % line_no)
            continue
        if tidy:
            waypoint = tidy_case(waypoint)
        name = build_name(waypoint, code, fmt)
        key = name.casefold()
        if key in seen:
            warn("line %d: %r repeats line %d, skipped" % (line_no, name, seen[key]))
            continue
        seen[key] = line_no
        rows.append([name, "%.6f" % lat, "%.6f" % lon, str(elev),
                     "" if radius is None else str(radius), club])
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("input", help="SeeYou .cup file")
    ap.add_argument("-o", "--output", default="airfields.csv", help="output CSV (default airfields.csv)")
    ap.add_argument("--name-format", choices=("code-name", "name", "code"), default="code-name",
                    help="how to build the airfield name: 'CODE - Name' (default), the waypoint name only, or the code only")
    ap.add_argument("--tidy-case", action="store_true",
                    help="title-case names that are entirely upper or lower case")
    ap.add_argument("--radius", type=int, metavar="M",
                    help="fill match_radius_m with this value for every airfield")
    ap.add_argument("--club", default="", metavar="NAMES",
                    help="fill clubs with this value (semicolon-separated club names) for every airfield")
    args = ap.parse_args()
    if args.radius is not None and args.radius <= 0:
        ap.error("--radius must be above 0")

    warnings = []
    rows = convert(read_text(args.input), args.name_format, args.tidy_case,
                   args.radius, args.club, warnings.append)
    with open(args.output, "w", newline="", encoding="utf-8-sig") as fh:
        w = csv.writer(fh, lineterminator="\r\n")
        w.writerow(["name", "latitude", "longitude", "elevation_m", "match_radius_m", "clubs"])
        w.writerows(rows)
    for msg in warnings:
        print("warning:", msg, file=sys.stderr)
    print("wrote %d airfields to %s (%d skipped)" % (len(rows), args.output, len(warnings)),
          file=sys.stderr)


if __name__ == "__main__":
    main()
