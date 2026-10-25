"""The benchmark gate rejects missing evidence and routing regressions."""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from verify_routing_equivalence import compare  # noqa: E402


def evidence():
    route = {
        'geometry': [[50.0, 30.0], [50.1, 30.1]], 'durationS': 100,
        'maxspeedKmh': [50], 'segmentSpeedMps': [10], 'elevationM': None,
        'steps': [{'type': 'depart', 'modifier': None, 'name': 'Test', 'geometryIndex': 0, 'roundaboutExit': None}],
    }
    return [
        {'profile': profile, 'states': 2, 'vertices': 2,
         'paths': [{'source': 0, 'target': 1, 'result': [10, [0, 1]]} for _ in range(64)],
         'queries': [{'query': 0, 'error': None, 'route': copy.deepcopy(route), 'warmMs': [1.0] * 30}]}
        for profile in ('car', 'foot')
    ]


class RoutingEquivalenceTest(unittest.TestCase):
    def test_same_results_and_small_rounding_pass(self):
        baseline = evidence()
        compact = copy.deepcopy(baseline)
        compact[0]['queries'][0]['route']['geometry'][0][0] += 0.00000004
        self.assertEqual(len(compare(baseline, compact)), 2)

    def test_changed_cost_or_missing_profile_is_rejected(self):
        baseline = evidence()
        compact = copy.deepcopy(baseline)
        compact[0]['paths'][0]['result'][0] += 1
        with self.assertRaisesRegex(ValueError, 'fixed-vertex'):
            compare(baseline, compact)
        with self.assertRaisesRegex(ValueError, 'profiles'):
            compare(baseline, baseline[:1])

    def test_geometry_and_success_changes_are_rejected(self):
        baseline = evidence()
        compact = copy.deepcopy(baseline)
        compact[0]['queries'][0]['route']['geometry'][0][0] += 0.001
        with self.assertRaisesRegex(ValueError, 'geometry'):
            compare(baseline, compact)
        compact = copy.deepcopy(baseline)
        compact[0]['queries'][0]['error'] = 'no road connection'
        with self.assertRaisesRegex(ValueError, 'success/failure'):
            compare(baseline, compact)
