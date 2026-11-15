#!/usr/bin/env python3
"""Offline verification of the committed LUT delivery; no source downloads."""
from collections import Counter
import json
from pathlib import Path

import numpy as np

from build import BASE, ROOT, decode, digest


def verify(output):
    manifest = json.loads((output / "manifest.json").read_text())
    report = json.loads((output / "validation.json").read_text())
    presets = json.loads((BASE / "presets.json").read_text())
    entries = manifest["luts"]
    errors = []
    expected = Counter(color_negative=8, slide=6, cinema_print=3, bw=5, creative=5)
    if Counter(e["category"] for e in entries) != expected:
        errors.append("category counts")
    ids = [e["id"] for e in entries]
    if ids != [p["id"] for p in presets] or len(ids) != len(set(ids)):
        errors.append("preset roster / duplicate IDs")
    if [r["id"] for r in report["luts"]] != ids:
        errors.append("validation roster")
    reports = {r["id"]: r for r in report["luts"]}
    if not report["passed"] or not all(r["passed"] for r in reports.values()):
        errors.append("quality gate")
    if report["requirements_sha256"] != digest(BASE / "requirements.txt"):
        errors.append("requirements changed after validation")
    for name, checksum in report["inputs_sha256"].items():
        if checksum != digest(BASE / name):
            errors.append(name + ": build input changed after validation")
    for entry in entries:
        name = entry["id"]
        try:
            path = output / entry["path"]
            table = decode(path.read_bytes())
            if digest(path) != entry["sha256"] or digest(path) != reports[name]["asset_sha256"]:
                errors.append(name + ": asset checksum")
            if path.stat().st_size != entry["bytes"]:
                errors.append(name + ": length")
            if entry["input_color_space"] != "sRGB" or entry["output_color_space"] != "sRGB":
                errors.append(name + ": color space")
            if entry["license_spdx"] not in ("MIT", "CC0-1.0"):
                errors.append(name + ": license")
            preview = output / entry["preview"]["path"]
            if digest(preview) != entry["preview"]["sha256"]:
                errors.append(name + ": preview checksum")
            # Upstream 8-bit B&W Halds contain <=1 source LSB RGB differences.
            # Preserve the source instead of silently desaturating its data.
            if entry["category"] == "bw" and np.max(np.ptp(table, axis=-1)) > 1 / 255 + 1 / 65535:
                errors.append(name + ": B&W channel spread exceeds source precision")
        except (OSError, ValueError, KeyError) as exc:
            errors.append(name + ": " + str(exc))
    notice = (output / "NOTICE").read_text()
    for path in (BASE / "licenses").glob("*.txt"):
        if path.read_text() not in notice:
            errors.append("NOTICE missing " + path.name)
    return errors


if __name__ == "__main__":
    errors = verify(ROOT / "assets/luts")
    if errors:
        raise SystemExit("\n".join(errors))
    print("PASS: 27 LUTs, counts, format, checksums, previews, licenses, validation linkage")
