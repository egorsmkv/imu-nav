#!/usr/bin/env python3
"""Build disposable public/synthetic search indexes and run the Kotlin/JNI parity benchmark.

No third-party Python packages. Never opens a source SQLite pack for writing.
"""
import argparse
import json
import os
from pathlib import Path
import sqlite3
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def synthetic(path, size):
    with sqlite3.connect(path) as db:
        db.executescript("""
        CREATE TABLE place(id INTEGER PRIMARY KEY,name TEXT,kind TEXT,lat REAL,lon REAL,population INTEGER);
        CREATE TABLE street(id INTEGER PRIMARY KEY,name TEXT,place_id INTEGER,lat REAL,lon REAL);
        CREATE TABLE addr(street_id INTEGER,number TEXT,lat REAL,lon REAL);
        CREATE INDEX addr_street ON addr(street_id,number);
        CREATE VIRTUAL TABLE place_fts USING fts4(names,prefix='2,3,4,6,8');
        CREATE VIRTUAL TABLE street_fts USING fts4(name,prefix='2,3,4,6,8');
        """)
        for index in range(1, size + 1):
            lat = 40 + (index % 10000) / 1000
            db.execute("INSERT INTO place VALUES (?,?,?,?,?,?)", (index, f"New Town {index}", "town", lat, 30, 1000))
            db.execute("INSERT INTO place_fts(rowid,names) VALUES (?,?)", (index, f"new town нове місто {index}"))
            db.execute("INSERT INTO street VALUES (?,?,?,?,?)", (index, f"Main {index}", index, lat, 30))
            db.execute("INSERT INTO street_fts(rowid,name) VALUES (?,?)", (index, f"main головна {index}"))
            for number in ["22 к1", "220"] + (["22"] if index % 10 == 0 else []):
                db.execute("INSERT INTO addr VALUES (?,?,?,?)", (index, number, lat, 30))
        for offset, name, lat, lon in [(1, "Far Away Town", -35, 150), (2, "East Island", 0, 179.99), (3, "West Island", 0, -179.99)]:
            index = size + offset
            db.execute("INSERT INTO place VALUES (?,?,?,?,?,?)", (index, name, "town", lat, lon, 1000))
            db.execute("INSERT INTO place_fts(rowid,names) VALUES (?,?)", (index, name.lower()))
            db.execute("INSERT INTO street VALUES (?,?,?,?,?)", (index, "Main D'Artagnan", index, lat, lon))
            db.execute("INSERT INTO street_fts(rowid,name) VALUES (?,?)", (index, "main dartagnan головна"))
            db.execute("INSERT INTO addr VALUES (?,?,?,?)", (index, "22", lat, lon))
        for table in ["place_fts", "street_fts"]:
            db.execute(f"INSERT INTO {table}({table}) VALUES ('optimize')")
        db.commit()
        db.execute("VACUUM")


def export(source, output):
    queries = [
        ("place", "SELECT p.*, f.* FROM place p JOIN place_fts f ON f.rowid=p.id"),
        ("street", "SELECT s.*,f.name AS search_text,p.name AS place_name FROM street s JOIN street_fts f ON f.rowid=s.id LEFT JOIN place p ON p.id=s.place_id"),
        ("address", "SELECT rowid AS id,street_id AS place_id,number,lat,lon FROM addr"),
    ]
    with sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True) as db, output.open("w") as writer:
        db.row_factory = sqlite3.Row
        for kind, query in queries:
            for row in db.execute(query):
                row = dict(row)
                result = {"kind": kind, "id": row["id"], "lat": row["lat"], "lon": row["lon"]}
                if kind == "place":
                    result.update(name=row["name"], text=row["names"], place_kind=row["kind"], population=row["population"] or 0)
                elif kind == "street":
                    result.update(name=row["name"], text=row["search_text"], place_id=row["place_id"], place_name=row["place_name"])
                else:
                    result.update(place_id=row["place_id"], number=row["number"])
                writer.write(json.dumps(result, ensure_ascii=False) + "\n")


def copy_with_prefixes(source, destination):
    with sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True) as original, sqlite3.connect(destination) as db:
        original.backup(db)
        for table, column in [("place_fts", "names"), ("street_fts", "name")]:
            db.execute(f"CREATE TEMP TABLE saved_fts AS SELECT rowid AS id,{column} AS words FROM {table}")
            db.execute(f"DROP TABLE {table}")
            db.execute(f"CREATE VIRTUAL TABLE {table} USING fts4({column},prefix='2,3,4,6,8')")
            db.execute(f"INSERT INTO {table}(rowid,{column}) SELECT id,words FROM saved_fts")
            db.execute("DROP TABLE saved_fts")
            db.execute(f"INSERT INTO {table}({table}) VALUES ('optimize')")
        db.commit()
        db.execute("VACUUM")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="new output directory; never overwritten")
    parser.add_argument("--target", type=Path, required=True, help="Cargo target directory (allow several GB)")
    parser.add_argument("--pack", type=Path, help="public regional search.db (read-only)")
    parser.add_argument("--sizes", type=int, nargs="+", default=[10000, 100000])
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, CARGO_TARGET_DIR=str(args.target.resolve()))
    subprocess.run(["cargo", "build", "--release", "--manifest-path", str(ROOT / "native/Cargo.toml"), "-p", "imu-nav-search", "--features", "builder"], env=env, check=True)
    for size in args.sizes:
        if not 1 <= size <= 1000000:
            parser.error("synthetic size must be between 1 and 1000000")
        synthetic(args.out / f"synthetic-{size}.db", size)
    if args.pack:
        # SQLite backup gives a consistent independent copy, including an active source WAL if present.
        with sqlite3.connect(args.pack.resolve().as_uri() + "?mode=ro", uri=True) as source, sqlite3.connect(args.out / "regional.db") as target:
            source.backup(target)
        copy_with_prefixes(args.pack, args.out / "regional-prefix.db")
    for database in sorted(args.out.glob("*.db")):
        exported = database.with_suffix(".jsonl")
        export(database, exported)
        subprocess.run([str(args.target.resolve() / "release/build-search"), str(exported), str(database.with_suffix(".tantivy"))], check=True)
    if not args.prepare_only:
        env.update(TANTIVY_LIBRARY_DIR=str(args.target.resolve() / "release"), TANTIVY_DATA_DIR=str(args.out.resolve()))
        subprocess.run([str(ROOT / "gradlew"), ":routing:test", "--tests", "*TantivyPrototypeTest*", "--rerun-tasks"], cwd=ROOT, env=env, check=True)


if __name__ == "__main__":
    main()
