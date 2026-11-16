#!/usr/bin/env python3
"""D2 scope gate: compare source and dependency declarations with the task baseline."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
base = "7a950be"
def git(*args):
    return subprocess.check_output(["git", *args], cwd=root)

protected = [f"app/src/main/java/dev/sukbeomh/beta7compact/{part}" for part in
             ("core", "usb", "ble", "library", "recipe", "transfer", "App.kt", "DiagnosticLog.kt")]
protected += ["assets/luts", "app/schemas", "build.gradle.kts", "settings.gradle.kts",
              "tools/check_m1_migration.py", "tools/check_m2_migration.py"]
assert not git("diff", base, "--", *protected), "D2 protected engine/schema/assets changed"
path = "app/build.gradle.kts"
before = git("show", f"{base}:{path}").decode()
after = (root / path).read_text()
assert before[before.index("dependencies {"):] == after[after.index("dependencies {"):], "Dependencies changed"
assert "versionCode = 6" in after and "versionName = \"0.4.0\"" in after
assert git("branch", "--show-current").decode().strip() == "feat/d2-redesign"
tracked = git("ls-files").decode().splitlines()
assert not any(path.lower().endswith((".apk", ".aab")) for path in tracked), "APK/AAB tracked"
assert (root / "NOTICE").read_bytes() == (root / "assets/material-symbols-NOTICE.txt").read_bytes()
assert "Apache License" in (root / "NOTICE").read_text()
print("D2 scope PASS: engine/USB/BLE/import/Room v4/JSON/share formats/LUT bytes unchanged; dependencies unchanged; 6/0.4.0; no APK/AAB tracked.")
