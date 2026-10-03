"""Guard against incomplete reports, source duplication, and rounded gates passing."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location('kotlin_coverage', Path(__file__).parents[1] / 'kotlin_coverage.py')
coverage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage)


class KotlinCoverageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.reports = {}
        for module in coverage.MODULES:
            path = self.root / module / 'src/main/kotlin/Example.kt'
            path.parent.mkdir(parents=True)
            path.write_text(f'package org.imunav.{module}\nfun first() = 1\nfun second() = 2\n')
            self.reports[module] = ET.fromstring(
                f'<report><package name="org/imunav/{module}"><class name="org/imunav/{module}/ExampleKt" sourcefilename="Example.kt"/>'
                '<sourcefile name="Example.kt"><line nr="2" mi="0" ci="2"/>'
                '<line nr="3" mi="2" ci="0"/></sourcefile></package></report>')

    def merged(self):
        return coverage.merge_reports(self.reports, coverage.source_inventory(self.root), self.root)

    def baseline(self, covered=1, lines=2):
        return {'schema': coverage.SCHEMA, 'jacoco': coverage.JACOCO,
                'modules': {module: {'covered': covered, 'lines': lines} for module in coverage.MODULES}}

    def test_unexecuted_lines_and_modules_stay_in_the_denominator(self):
        sources = self.merged()
        self.assertEqual(len(sources), 4)
        self.assertEqual(sources['core/src/main/kotlin/Example.kt'], {2: True, 3: False})
        summary = coverage.summarize(sources)
        for counts in summary.values():
            self.assertEqual(counts, {'covered': 1, 'lines': 2, 'percent': 50.0})

    def test_shared_wrappers_merge_once_under_the_app_owner(self):
        wrapper = self.root / 'app/src/main/kotlin/nativecore/Wrapper.kt'
        wrapper.parent.mkdir()
        wrapper.write_text('package org.imunav.app.nativecore\nfun first() = 1\nfun second() = 2\n')
        for domain, counts in [('replay', [(2, 1, 0), (3, 0, 2)]), ('app', [(2, 0, 2), (3, 2, 0)])]:
            package = ET.SubElement(self.reports[domain], 'package', name='org/imunav/app/nativecore')
            ET.SubElement(package, 'class', name='org/imunav/app/nativecore/Wrapper', sourcefilename='Wrapper.kt')
            source = ET.SubElement(package, 'sourcefile', name='Wrapper.kt')
            for number, missed, covered in counts:
                ET.SubElement(source, 'line', nr=str(number), mi=str(missed), ci=str(covered))
        sources = self.merged()
        self.assertEqual(sources['app/src/main/kotlin/nativecore/Wrapper.kt'], {2: True, 3: True})
        self.assertEqual(coverage.summarize(sources)['app']['lines'], 4)
        self.assertEqual(coverage.summarize(sources)['replay']['lines'], 2)

    def test_unknown_source_or_wrong_ownership_fails_instead_of_excluding_it(self):
        source = self.reports['core'].find('package/sourcefile')
        source.set('name', 'Missing.kt')
        with self.assertRaisesRegex(RuntimeError, 'Unresolved production source'):
            self.merged()
        source.set('name', 'Example.kt')
        self.reports['core'].find('package').set('name', 'org/imunav/app')
        with self.assertRaisesRegex(RuntimeError, 'Unexpected app source in core'):
            self.merged()

    def test_empty_reports_and_impossible_line_numbers_fail(self):
        self.reports['core'].find('package/sourcefile/line').set('nr', '999')
        with self.assertRaisesRegex(RuntimeError, 'Invalid coverage line'):
            self.merged()
        self.reports['core'] = ET.fromstring('<report/>')
        with self.assertRaisesRegex(RuntimeError, 'Empty or invalid core'):
            self.merged()

    def test_duplicate_source_paths_are_not_guessed(self):
        path = self.root / 'routing/src/main/kotlin/duplicate/Example.kt'
        path.parent.mkdir()
        path.write_text('package org.imunav.core\nfun duplicate() = 1')
        with self.assertRaisesRegex(RuntimeError, 'Ambiguous source ownership'):
            coverage.source_inventory(self.root)

    def test_empty_filtered_inline_dependencies_do_not_hide_executable_sources(self):
        package = self.reports['core'].find('package')
        ET.SubElement(package, 'class', name='Example$$inlined$compareBy$1', sourcefilename='Comparisons.kt')
        source = ET.SubElement(package, 'sourcefile', name='Comparisons.kt')
        self.merged()
        ET.SubElement(source, 'line', nr='1', mi='2', ci='0')
        with self.assertRaisesRegex(RuntimeError, 'Unresolved production source'):
            self.merged()

    def test_omitting_a_production_file_cannot_raise_coverage(self):
        extra = self.root / 'core/src/main/kotlin/Uncovered.kt'
        extra.write_text('package org.imunav.core\nfun uncovered() = 1')
        with self.assertRaisesRegex(RuntimeError, 'Missing production sources'):
            self.merged()

    def test_exact_ratios_gate_without_rounding(self):
        summary = {module: {'covered': 89999, 'lines': 100000} for module in coverage.MODULES}
        self.assertFalse(coverage.verify_baseline(summary, self.baseline(9, 10)))
        self.assertEqual(round(100 * 89999 / 100000, 2), 90.0)
        for counts in summary.values():
            counts['covered'] = 90000
        self.assertTrue(coverage.verify_baseline(summary, self.baseline(9, 10)))

    def test_invalid_and_incomplete_baselines_are_rejected(self):
        summary = coverage.summarize(self.merged())
        for baseline in [self.baseline(3, 2), self.baseline(0, 0), self.baseline(True, 2),
                         dict(self.baseline(), jacoco='different'), dict(self.baseline(), modules={})]:
            with self.assertRaises(RuntimeError):
                coverage.verify_baseline(summary, baseline)

    def test_every_execution_and_production_output_is_required(self):
        directory = self.root / 'output'
        with self.assertRaisesRegex(RuntimeError, 'execution data'):
            coverage.validate_inputs(directory)
        (directory / 'execution').mkdir(parents=True)
        for name in coverage.EXECUTIONS:
            (directory / 'execution' / (name + '.exec')).write_bytes(b'profile')
        with self.assertRaisesRegex(RuntimeError, 'class inventory'):
            coverage.validate_inputs(directory)
        for module in coverage.MODULES:
            folder = directory / module
            (folder / 'html').mkdir(parents=True)
            bytecode = folder / 'Example.class'
            bytecode.write_bytes(b'bytecode')
            (folder / 'classes.txt').write_text(str(bytecode) + '\n')
            (folder / 'report.xml').write_text('<report/>')
            (folder / 'html/index.html').write_text('report')
        coverage.validate_inputs(directory)
        (directory / 'core/Example.class').unlink()
        with self.assertRaisesRegex(RuntimeError, 'Missing production class'):
            coverage.validate_inputs(directory)
        (directory / 'execution/core-test.exec').write_bytes(b'')
        with self.assertRaisesRegex(RuntimeError, 'execution data'):
            coverage.validate_inputs(directory)

    def test_class_mapping_warnings_fail_even_when_gradle_succeeds(self):
        for warning in ['Execution data for class Example does not match.',
                        'Classes in bundle app do not match with execution data.',
                        'Can\'t add different class with same name: Example']:
            with self.assertRaises(RuntimeError):
                coverage.validate_log(warning, 0)
        coverage.validate_log('BUILD SUCCESSFUL', 0)
        with self.assertRaises(RuntimeError):
            coverage.validate_log('BUILD FAILED', 1)

    def test_published_reports_preserve_zero_hits_and_source_text(self):
        sources = self.merged()
        summary = coverage.summarize(sources)
        coverage.verify_baseline(summary, self.baseline())
        output = self.root / 'output'
        directory = output / 'runs/example'
        directory.mkdir(parents=True)
        coverage.write_reports(output, directory, sources, summary, self.root)
        lcov = (output / 'coverage.lcov').read_text()
        self.assertEqual(lcov.count('LF:2'), 4)
        self.assertEqual(lcov.count('DA:3,0'), 4)
        self.assertEqual(json.loads((output / 'summary.json').read_text())['modules']['core']['covered'], 1)
        self.assertIn('class="miss"', (output / 'sources/core/src/main/kotlin/Example.kt.html').read_text())
        self.assertIn('runs/example/core/html/index.html', (output / 'index.html').read_text())


if __name__ == '__main__':
    unittest.main()
