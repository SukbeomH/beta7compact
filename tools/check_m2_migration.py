#!/usr/bin/env python3
"""Validate actual Room 3->4 SQL, all legacy paths, data preservation and result links."""
import json
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
folder = root / "app/schemas/dev.sukbeomh.beta7compact.library.LibraryDb"
schemas = {n: json.loads((folder / f"{n}.json").read_text())["database"] for n in range(1, 5)}
m1 = (root / "app/build/reports/m1-migration.sql").read_text().split(";")
m2 = (root / "app/build/reports/m2-migration.sql").read_text()


def fresh(version):
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    for entity in schemas[version]["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity["indices"]:
            db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    return db


def structure(db):
    result = {}
    for table in ("photos", "recipes", "recipe_results", "recipe_jobs"):
        columns = list(db.execute(f"PRAGMA table_info({table})"))
        indexes = [(r[1], r[2], list(db.execute(f"PRAGMA index_info({r[1]})")))
                   for r in db.execute(f"PRAGMA index_list({table})")]
        result[table] = columns, sorted(indexes), list(db.execute(f"PRAGMA foreign_key_list({table})"))
    return result


expected = structure(fresh(4))
for version in (1, 2, 3):
    db = fresh(version)
    columns = [r[1] for r in db.execute("PRAGMA table_info(photos)")]
    for i in range(2):
        values = dict(key=f"public-{i}", name="PUBLIC.JPG", size=4, captured=123,
                      handle=i, storage=2, parent=3, pair="public", uri=f"content://synthetic/{i}",
                      imported=None if i == 0 else 999, recipe="legacy-snapshot",
                      cameraTime="20200102T030405.1Z", contentHash="synthetic-hash")
        db.execute(f"INSERT INTO photos VALUES ({','.join('?' for _ in columns)})",
                   tuple(values[c] for c in columns))
    old = list(db.execute("SELECT * FROM photos ORDER BY key"))
    if version == 1:
        db.execute(m1[0])
    if version <= 2:
        db.execute(m1[1])
    db.executescript(m2)
    assert structure(db) == expected
    assert list(db.execute("SELECT * FROM photos ORDER BY key")) == [
        row + (None,) * (len(schemas[3]["entities"][0]["fields"]) - len(columns)) for row in old]
    db.execute("INSERT INTO recipes VALUES ('public-r', 'Public', '{}', 1)")
    db.execute("INSERT INTO recipe_results VALUES ('public-result', 'public-1', 'content://synthetic/result', '{}', 1234)")
    db.execute("INSERT INTO recipe_jobs VALUES ('public-job', 'public-batch', 'public-1', '{}', 1234, 'pending', NULL, 0, NULL)")
    try:
        db.execute("INSERT INTO recipe_results VALUES ('bad', 'missing', 'synthetic', '{}', 1)")
    except sqlite3.IntegrityError:
        pass
    else:
        raise AssertionError("Missing source accepted")
    assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
    assert list(db.execute("PRAGMA foreign_key_check")) == []
print("Room 1/2/3->4: fresh schema/index/FK match; original rows unchanged; recipes/results/jobs persist; integrity/FK checks pass.")
