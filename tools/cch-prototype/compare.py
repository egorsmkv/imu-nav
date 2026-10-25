#!/usr/bin/env python3
"""Validate CCH reachability/rounded costs against GraphHopper; summarize warm kernel timings."""
import argparse
import csv
import json
import math
import statistics
from pathlib import Path


def read(path):
    with path.open() as stream:
        rows = list(csv.DictReader(stream))
    indexed = {(int(row['query']), int(row['iteration'])): row for row in rows}
    if len(indexed) != len(rows) or not rows:
        raise ValueError('Missing or duplicate results')
    return indexed


def summarize(rows):
    groups = {}
    for (query, iteration), row in rows.items():
        if iteration > 0:
            groups.setdefault(query, []).append(float(row['ms']))
    medians = sorted(statistics.median(samples) for samples in groups.values())
    return {'queries': len(medians), 'warm_samples': sum(map(len, groups.values())),
            'median_query_ms': statistics.median(medians),
            'p95_query_medians_ms': medians[math.ceil(len(medians) * .95) - 1],
            'max_query_median_ms': max(medians)}


def compare(reference, candidate):
    if reference.keys() != candidate.keys():
        raise ValueError('Query/iteration coverage differs')
    max_error = 0.0
    for key, actual in candidate.items():
        expected = reference[key]
        if actual['found'] != expected['found']:
            raise ValueError(f'Reachability mismatch: {key}')
        if actual['found'] == 'true':
            error = abs(float(actual['weight']) - float(expected['weight']))
            # Each transition is rounded to the nearest 0.001 weight unit. Compare the two
            # potentially different optimal paths using the sum of their rounding bounds.
            bound = .0005 * (int(actual['edges']) + int(expected['edges'])) + .00001
            if not math.isfinite(error) or error > bound:
                raise ValueError(f'Cost mismatch: {key}: {error} > {bound}')
            max_error = max(max_error, error)
    return {'matched_rows': len(reference), 'maximum_weight_error': max_error,
            'graphhopper': summarize(reference), 'cch': summarize(candidate)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reference', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--candidate-name', default='cch')
    args = parser.parse_args()
    report = compare(read(args.reference), read(args.candidate))
    if args.candidate_name != "cch":
        report[args.candidate_name] = report.pop("cch")
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
