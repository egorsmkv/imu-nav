#!/usr/bin/env python3
"""Drive the shipped server over loopback with repeatable Android-style sync traffic."""

import argparse
import gzip
import hashlib
import json
import os
import re
import resource
import selectors
import sqlite3
import statistics
import subprocess
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def upload_body(device: int, round_number: int, rows: int) -> bytes:
    lines = ["radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal"]
    for index in range(rows):
        # Four nearby phones share most cells. One quarter are private, while the first fifth
        # recur on later syncs with small location updates. Invalid ranges exercise rejection.
        shared = index < rows * 3 // 4
        generation = 0 if index < rows // 5 else round_number
        cell = generation * rows + index if shared else 1_000_000 + device * 100_000 + generation * rows + index
        lat = 50.3 + (cell % 300) * 0.0002 + device * 0.00002 + round_number * 0.000005
        lon = 30.4 + (cell // 300 % 300) * 0.0002 - device * 0.00002 - round_number * 0.000005
        radio = ("LTE", "LTE", "LTE", "UMTS", "GSM", "NR")[index % 6]
        mcc = 256 if index % 5 == 0 else 255
        range_m = 0 if index % 101 == 0 else 450 + index % 200
        samples = 3 + (device + index + round_number) % 25
        lines.append(f"{radio},{mcc},1,1864,{cell},,{lon:.7f},{lat:.7f},{range_m},{samples},1,0,0,")
    return gzip.compress(("\n".join(lines) + "\n").encode(), mtime=0)


def request(base: str, path: str, method: str = "GET", body: bytes | None = None,
            device: int | None = None, token: str | None = None) -> tuple[bytes, float]:
    headers = {}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None and device is None:
        headers["Content-Type"] = "application/json"
    if device is not None:
        headers["X-Device-Id"] = f"sim-phone-{device:04d}"
        headers["Content-Encoding"] = "gzip"
        headers["Content-Type"] = "text/csv"
    started = time.perf_counter()
    pending = urllib.request.Request(base + path, data=body, headers=headers, method=method)
    with urllib.request.urlopen(pending, timeout=120) as response:
        payload = response.read()
    return payload, (time.perf_counter() - started) * 1000


def start_server(binary: Path, database: Path, profile: Path | None) -> tuple[subprocess.Popen[str], str]:
    subprocess.run([str(binary), "--data", str(database), "--create-admin", "admin@example.org"],
                   input="correct horse battery staple\n", text=True, check=True, stdout=subprocess.DEVNULL)
    command = [str(binary), "--bind", "127.0.0.1", "--port", "0", "--data", str(database)]
    if profile is not None:
        command.extend(["--profile-output", str(profile)])
    environment = os.environ.copy()
    environment.pop("CELLS_PROFILE_OUTPUT", None)
    environment.update({"RUST_LOG": "info", "NO_COLOR": "1", "HOTPATH_METRICS_SERVER_OFF": "true"})
    server = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, bufsize=1, env=environment)
    assert server.stdout is not None
    deadline = time.monotonic() + 30
    with selectors.DefaultSelector() as selector:
        selector.register(server.stdout, selectors.EVENT_READ)
        while time.monotonic() < deadline:
            if not selector.select(timeout=1):
                continue
            line = server.stdout.readline()
            match = re.search(r"address=127\.0\.0\.1:(\d+)", line)
            if match:
                return server, f"http://127.0.0.1:{match.group(1)}"
            if not line and server.poll() is not None:
                raise RuntimeError("server exited before readiness")
    server.terminate()
    raise TimeoutError("server did not become ready")


def database_fingerprint(database: Path) -> tuple[str, dict[str, int]]:
    digest = hashlib.sha256()
    counts = {}
    with sqlite3.connect(database) as connection:
        for table, columns, order in [
            ("contributions", "radio,mcc,mnc,area,cid,device,lat,lon,range_m,samples",
             "radio,mcc,mnc,area,cid,device"),
            ("consensus", "radio,mcc,mnc,area,cid,lat,lon,range_m,samples,devices,seeded",
             "radio,mcc,mnc,area,cid"),
        ]:
            rows = connection.execute(f"SELECT {columns} FROM {table} ORDER BY {order}")
            count = 0
            for row in rows:
                digest.update(repr(row).encode())
                digest.update(b"\n")
                count += 1
            counts[table] = count
    return digest.hexdigest(), counts


def run_once(binary: Path, directory: Path, rounds: int, rows: int,
             profile: bool) -> dict:
    directory.mkdir()
    database = directory / "cells.sqlite3"
    bodies = [[upload_body(device, round_number, rows) for device in range(4)]
              for round_number in range(rounds)]
    cpu_before = resource.getrusage(resource.RUSAGE_CHILDREN)
    server, base = start_server(binary, database, directory / "hotpath.json" if profile else None)
    timings: dict[str, list[float]] = {name: [] for name in ("upload", "download", "management", "health")}
    accepted = rejected = downloaded = 0
    try:
        request(base, "/health")  # Exclude process startup and the first SQLite open.
        admin_token = json.loads(request(base, "/v1/auth/login", "POST", json.dumps({
            "email": "admin@example.org", "password": "correct horse battery staple"
        }).encode())[0])["access_token"]
        tokens = [json.loads(request(base, "/v1/auth/register", "POST", json.dumps({
            "email": f"phone-{device}@example.org", "password": "correct horse battery staple"
        }).encode())[0])["access_token"] for device in range(4)]
        started = time.perf_counter()
        with ThreadPoolExecutor(max_workers=4) as pool:
            for round_number in range(rounds):
                jobs = [pool.submit(request, base, "/v1/cells", "POST",
                                    bodies[round_number][device], device, tokens[device])
                        for device in range(4)]
                for job in jobs:
                    payload, elapsed = job.result()
                    result = json.loads(payload)
                    accepted += result["accepted"]
                    rejected += result["rejected"]
                    timings["upload"].append(elapsed)
                since = 0 if round_number == 0 else max(0, int(time.time()) - 60)
                payload, elapsed = request(base, f"/v1/cells.csv.gz?mcc=255,256&since={since}")
                downloaded += len(gzip.decompress(payload).splitlines()) - 1
                timings["download"].append(elapsed)
                payload, elapsed = request(base, "/v1/towers?mcc=255&limit=500", token=admin_token)
                assert len(json.loads(payload)["towers"]) <= 500
                timings["management"].append(elapsed)
                payload, elapsed = request(base, "/health")
                assert payload.startswith(b"ok ")
                timings["health"].append(elapsed)
        payload, elapsed = request(base, "/admin?mcc=255&limit=100", token=admin_token)
        assert b"<html" in payload.lower()
        timings["management"].append(elapsed)
        wall_ms = (time.perf_counter() - started) * 1000
    finally:
        server.terminate()
        try:
            server.communicate(timeout=30)
        except subprocess.TimeoutExpired:
            server.kill()
            server.communicate()
            raise
    if server.returncode != 0:
        raise RuntimeError(f"server failed: {server.returncode}")
    cpu_after = resource.getrusage(resource.RUSAGE_CHILDREN)
    cpu_ms = 1000 * (cpu_after.ru_utime + cpu_after.ru_stime
                     - cpu_before.ru_utime - cpu_before.ru_stime)
    if profile and not (directory / "hotpath.json").is_file():
        raise RuntimeError("profiling report was not flushed")
    fingerprint, counts = database_fingerprint(database)
    return {"wall_ms": wall_ms, "server_cpu_ms": cpu_ms, "latency_ms": {
        name: {"median": statistics.median(values), "max": max(values), "count": len(values)}
        for name, values in timings.items()}, "accepted": accepted, "rejected": rejected,
        "downloaded_rows": downloaded, "database": counts, "fingerprint": fingerprint}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--rounds", type=int, default=4)
    parser.add_argument("--rows", type=int, default=1000)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--profile", action="store_true")
    args = parser.parse_args()
    if min(args.rounds, args.rows, args.repetitions) < 1 or args.rounds > 30 or args.rows > 20_000:
        parser.error("rounds, rows and repetitions must be positive; rounds <= 30 and rows <= 20000")
    args.out.mkdir(parents=True, exist_ok=False)
    runs = [run_once(args.binary.resolve(), args.out / f"run-{index}", args.rounds,
                     args.rows, args.profile) for index in range(args.repetitions)]
    if any(run["fingerprint"] != runs[0]["fingerprint"] for run in runs):
        raise RuntimeError("traffic replay changed its stored result across repetitions")
    summary = {"config": {"rounds": args.rounds, "rows": args.rows,
                          "repetitions": args.repetitions, "devices": 4},
               "median_wall_ms": statistics.median(run["wall_ms"] for run in runs),
               "median_server_cpu_ms": statistics.median(run["server_cpu_ms"] for run in runs),
               "runs": runs}
    (args.out / "report.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({"median_wall_ms": summary["median_wall_ms"],
                      "median_server_cpu_ms": summary["median_server_cpu_ms"],
                      "accepted": runs[0]["accepted"], "rejected": runs[0]["rejected"],
                      "database": runs[0]["database"], "fingerprint": runs[0]["fingerprint"]}))


if __name__ == "__main__":
    main()
