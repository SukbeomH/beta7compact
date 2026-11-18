#!/usr/bin/env python3
"""Reject publication of APKs that cannot update the existing beta7compact app."""
import argparse
import re
import subprocess

EXPECTED_CERT = "eaf9514a8a00e3d029e98e772cf0185bfafd270645cb21bc057f198a2d9e23f8"
parser = argparse.ArgumentParser()
parser.add_argument("apk")
parser.add_argument("--apksigner", required=True, help="Android SDK build-tools apksigner path")
args = parser.parse_args()
result = subprocess.run([args.apksigner, "verify", "--print-certs", args.apk],
                        check=True, capture_output=True, text=True)
certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", result.stdout)
if certs != [EXPECTED_CERT]:
    raise SystemExit("STOP: APK signing certificate does not match installed-app identity. Do not publish.")
print("PASS: APK signature verified; certificate matches existing installations: " + EXPECTED_CERT)
