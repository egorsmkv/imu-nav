#!/usr/bin/env python3
"""Compare benchmark-routing JSON from identical public workloads before/after a format change.

Geometry tolerance includes endpoint projection; E7 rounding itself stays below 1 cm.
Reports query IDs and timings, never source coordinates.
"""
import argparse
import json
import math
from pathlib import Path


def require(condition, message):
    """Do not let Python's optimization flag disable this validation gate."""
    if not condition:
        raise ValueError(message)


def compare_paths(baseline, compact, context):
    require(len(baseline) == len(compact) == 64, f"{context}: incomplete path evidence")
    for previous, current in zip(baseline, compact):
        require(previous == current, f"{context}: fixed-vertex cost, path or endpoints changed")


def compare_queries(baseline, compact, context):
    require(len(baseline) == len(compact) > 0, f"{context}: incomplete query evidence")
    rows = []
    for previous, current in zip(baseline, compact):
        query = f"{context} query={previous['query']}"
        require(previous['query'] == current['query'], f"{query}: query order changed")
        require(previous['error'] == current['error'], f"{query}: success/failure changed")
        if previous['error']:
            continue
        old_route, new_route = previous['route'], current['route']
        require(len(old_route['geometry']) == len(new_route['geometry']), f"{query}: point count changed")
        worst = 0.0
        for old_point, new_point in zip(old_route['geometry'], new_route['geometry']):
            distance = 111_195 * math.hypot(
                old_point[0] - new_point[0],
                (old_point[1] - new_point[1]) * math.cos(math.radians(old_point[0])),
            )
            worst = max(worst, distance)
        require(worst < 0.03, f"{query}: geometry exceeds 3 cm projection tolerance")
        require(abs(old_route['durationS'] - new_route['durationS']) < 0.1, f"{query}: duration changed")
        for field in ('maxspeedKmh', 'segmentSpeedMps', 'elevationM'):
            require(old_route[field] == new_route[field], f"{query}: {field} changed")
        require(len(old_route['steps']) == len(new_route['steps']), f"{query}: maneuver count changed")
        for old_step, new_step in zip(old_route['steps'], new_route['steps']):
            for field in ('type', 'modifier', 'name', 'geometryIndex', 'roundaboutExit'):
                require(old_step[field] == new_step[field], f"{query}: maneuver {field} changed")
        require(len(previous['warmMs']) == len(current['warmMs']) == 30, f"{query}: incomplete timing evidence")
        rows.append({
            'profile': context, 'query': previous['query'],
            'baselineP50Ms': previous['warmMs'][14], 'compactP50Ms': current['warmMs'][14],
            'baselineP95Ms': previous['warmMs'][28], 'compactP95Ms': current['warmMs'][28],
            'maxGeometryErrorM': worst,
        })
    return rows


def compare(baseline, compact):
    """Require exact graph-path evidence and bounded coordinate changes for both profiles."""
    require([item['profile'] for item in baseline] == ['car', 'foot'], 'incomplete baseline profiles')
    require([item['profile'] for item in compact] == ['car', 'foot'], 'incomplete compact profiles')
    rows = []
    for previous, current in zip(baseline, compact):
        profile = previous['profile']
        for field in ('states', 'vertices'):
            require(previous[field] == current[field], f"{profile}: {field} changed")
        compare_paths(previous['paths'], current['paths'], profile)
        rows.extend(compare_queries(previous['queries'], current['queries'], profile))
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('compact', type=Path)
    args = parser.parse_args()
    rows = compare(json.loads(args.baseline.read_text()), json.loads(args.compact.read_text()))
    print(json.dumps({'equivalent': True, 'queries': rows}, indent=2))


if __name__ == '__main__':
    main()
