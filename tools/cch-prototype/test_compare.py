"""Ensure the experimental comparison rejects incorrect paths instead of timing them as successes."""
import unittest
from compare import compare


class ComparisonTest(unittest.TestCase):
    def rows(self, weight='10', found='true', edges='2'):
        return {(0, i): {'weight': weight, 'found': found, 'edges': edges, 'ms': '1'} for i in range(2)}

    def test_accepts_only_bounded_rounding(self):
        self.assertEqual(compare(self.rows(), self.rows('10.001'))['matched_rows'], 2)
        with self.assertRaisesRegex(ValueError, 'Cost mismatch'):
            compare(self.rows(), self.rows('11'))

    def test_unreachable_is_not_a_fast_success(self):
        with self.assertRaisesRegex(ValueError, 'Reachability mismatch'):
            compare(self.rows(), self.rows(found='false'))

    def test_missing_queries_cannot_disappear(self):
        with self.assertRaisesRegex(ValueError, 'coverage'):
            compare(self.rows(), {(0, 1): self.rows()[(0, 1)]})

    def test_nan_is_not_accepted(self):
        with self.assertRaisesRegex(ValueError, 'Cost mismatch'):
            compare(self.rows(), self.rows('nan'))
