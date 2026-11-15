#!/usr/bin/env python3
"""Apply SQL emitted by the actual Room migration to SQLite and compare exported schemas."""
import json
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
schemas = root / "app/schemas/dev.sukbeomh.beta7compact.library.LibraryDb"
v1, v2 = (json.loads((schemas / f"{version}.json").read_text())["database"] for version in (1, 2))
sql = (root / "app/build/reports/m1-migration.sql").read_text()


def database(schema):
    db = sqlite3.connect(":memory:")
    for entity in schema["entities"]:
        db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    return db


old, fresh = database(v1), database(v2)
columns = [row[1] for row in old.execute("PRAGMA table_info(photos)")]
fixtures = []
for i, imported in enumerate((None, 123)):
    values = dict(key=f"synthetic-key-{i}", name=f"PUBLIC_TEST_{i}.JPG", size=4,
                  captured=1577901845000, handle=i + 1, storage=2, parent=8,
                  pair="synthetic-pair", uri=f"content://synthetic/{i + 1}", imported=imported, recipe=None)
    fixture = tuple(values[column] for column in columns)
    old.execute(f"INSERT INTO photos VALUES ({','.join('?' for _ in columns)})", fixture)
    fixtures.append(fixture)
old.executescript(sql)
assert list(old.execute("PRAGMA table_info(photos)")) == list(fresh.execute("PRAGMA table_info(photos)"))
assert list(old.execute("SELECT * FROM photos ORDER BY key")) == [row + (None,) for row in fixtures]
assert old.execute("PRAGMA integrity_check").fetchone() == ("ok",)
print("Room v1->v2 emitted SQL: SQLite schema matches fresh v2; staged/completed keys, URIs and metadata preserved (2 rows).")
