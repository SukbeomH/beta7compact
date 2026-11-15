#!/usr/bin/env python3
"""M5a scope guard against the task baseline; no credentials or Android device access."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = "f9681a5"

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)

protected = [
    "app/src/main/java/dev/sukbeomh/beta7compact/ble",
    "app/src/main/java/dev/sukbeomh/beta7compact/usb",
    "app/src/main/java/dev/sukbeomh/beta7compact/core",
    "app/src/main/java/dev/sukbeomh/beta7compact/transfer",
    "app/src/main/java/dev/sukbeomh/beta7compact/library/LibraryDb.kt",
    "app/src/main/java/dev/sukbeomh/beta7compact/recipe/RecipeWorker.kt",
    "app/src/main/java/dev/sukbeomh/beta7compact/recipe/RecipeStore.kt",
    "app/src/main/java/dev/sukbeomh/beta7compact/recipe/Lut.kt",
    "app/src/main/java/dev/sukbeomh/beta7compact/ui/MainScreen.kt",
    "app/src/main/java/dev/sukbeomh/beta7compact/App.kt",
    "app/src/main/java/dev/sukbeomh/beta7compact/DiagnosticLog.kt",
    "app/schemas", "assets", "tools/check_m1_migration.py", "tools/check_m2_migration.py",
    "settings.gradle.kts", "build.gradle.kts",
]
assert not git("diff", BASE, "--", *protected), "M1/M2 protected files changed"
path = "app/build.gradle.kts"
before = git("show", f"{BASE}:{path}").decode()
after = (ROOT / path).read_text()
assert before[before.index("dependencies {"):] == after[after.index("dependencies {"):], "Dependencies changed"
assert "versionCode = 5" in after and 'versionName = "0.3.0"' in after
path = "app/src/main/java/dev/sukbeomh/beta7compact/library/Recipes.kt"
assert git("show", f"{BASE}:{path}").decode().split("@Dao")[0] == (ROOT / path).read_text().split("@Dao")[0], "Entities changed"
for path in git("ls-files").decode().splitlines():
    assert not path.lower().endswith((".apk", ".aab")), "APK/AAB tracked"
assert git("branch", "--show-current").decode().strip() == "feat/m5a-editor-share"
print("M5a scope: BLE/USB/import/originals/LUT assets/Room v4 unchanged; dependencies unchanged; version 5/0.3.0; no tracked APK/AAB.")
