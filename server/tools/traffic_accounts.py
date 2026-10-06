"""Synthetic private-account traffic shared by the profiling driver."""

import json

NAMES = ("sync_read", "sync_batch", "sync_retry", "sync_conflict", "sync_delete",
         "trip_upload", "trip_retry", "trip_conflict", "trip_list", "trip_read", "trip_delete")


def trip_body(points):
    """A bounded synthetic drive; no recordings or personal location data are read."""
    return json.dumps({
        "version": 1, "incomplete": False,
        "summary": {"start_ms": 1000, "end_ms": 1000 + points * 1000, "mode": "CAR",
                    "arrived": True, "distance_m": points, "duration_s": points,
                    "moving_s": points, "blind_s": 0, "blind_m": 0,
                    "max_uncertainty_m": 10, "route_length_m": points, "reroutes": 0},
        "positions": [{"time_ms": i * 1000, "segment": 0, "lat": 50 + i % 100 * .00001,
                       "lon": 30, "uncertainty_m": 5, "source": "GPS"} for i in range(points)],
        "routes": [{"time_ms": 0, "segment": 0, "points": [[30, 50], [30, 50.001]]}]
    }, separators=(",", ":")).encode()


def prepare(rounds, points, samples):
    """Encode fixtures once before timing, keeping driver JSON work out of comparisons."""
    trip = trip_body(points)
    document = json.loads(trip)
    different = dict(document, incomplete=True)
    batches = []
    for round_number in range(rounds):
        entries = [{"kind": "bookmark", "key": f"b{round_number}_{i}", "revision": 0,
                    "value": {"type": "place", "name": f"Synthetic {i}",
                              "endpoint": {"point": {"lat": 50, "lon": 30}, "label": None}}}
                   for i in range(samples)]

        def encode(changes):
            return json.dumps({"version": 1, "generation": 0, "changes": changes},
                              separators=(",", ":")).encode()

        stale = dict(entries[0], value=None)
        batches.append((encode(entries), encode([stale]), encode([dict(stale, revision=1)]), stale["key"]))
    return trip, json.dumps(different, separators=(",", ":")).encode(), document, batches


def enable(request, base, tokens):
    seed = json.loads(trip_body(2))
    seed["summary"]["start_ms"] = 0
    seed = json.dumps(seed).encode()
    for token in tokens:
        for purpose in ("trip_archive", "account_sync"):
            request(base, f"/v1/privacy/consents/{purpose}", "PUT",
                    b'{"notice_version":"local"}', token=token)
        for index in range(51):
            request(base, f"/v1/trips/seed-{index:02d}", "PUT", seed, token=token, expected=204)


def exercise(request, base, token, round_number, fixtures, samples):
    """One account's ordered requests; callers run accounts concurrently."""
    timings = {name: [] for name in NAMES}

    def call(name, path, method="GET", body=None, expected=200):
        payload, elapsed = request(base, path, method, body, token=token, expected=expected)
        timings[name].append(elapsed)
        return json.loads(payload) if payload else None

    endpoint = "/v1/account-sync"
    snapshot = call("sync_read", endpoint)
    assert snapshot["generation"] == 0
    trip, different_trip, document, batches = fixtures
    batch, conflict, deletion, key = batches[round_number]
    for name in ("sync_batch", "sync_retry"):
        result = call(name, endpoint, "PUT", batch)
        assert not result["conflicts"]
        assert len(result["entries"]) == (round_number + 1) * samples
        assert all(e["revision"] == 1 for e in result["entries"] if e["key"].startswith(f"b{round_number}_"))
    result = call("sync_conflict", endpoint, "PUT", conflict)
    assert result["conflicts"] == [f"bookmark:{key}"]
    result = call("sync_delete", endpoint, "PUT", deletion)
    assert not result["conflicts"]
    assert next(e for e in result["entries"] if e["key"] == key)["value"] is None
    path = f"/v1/trips/sim-{round_number}"
    for name in ("trip_upload", "trip_retry"):
        call(name, path, "PUT", trip, 204)
    call("trip_conflict", path, "PUT", different_trip, 409)
    listing = call("trip_list", "/v1/trips")
    assert listing["count"] == 52 + round_number
    assert any(t["id"] == f"sim-{round_number}" for t in listing["trips"])
    assert call("trip_read", path) == document
    # Keep earlier trips for pagination/quota aggregation, delete a separate retryable copy.
    temporary = f"/v1/trips/delete-{round_number}"
    call("trip_upload", temporary, "PUT", trip, 204)
    call("trip_delete", temporary, "DELETE", expected=204)
    page = call("trip_list", "/v1/trips?offset=50")
    assert len(page["trips"]) == round_number + 2
    assert not page["more"]
    return timings
