import unittest
from instrumentation_report import parse
from generate_predevice_fixtures import dataset


class InstrumentationReportTest(unittest.TestCase):
    def test_assumptions_are_separate_from_passes(self):
        output = '\n'.join(['INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS_CODE: 0',
                            'INSTRUMENTATION_STATUS_CODE: -4', 'OK (2 tests)'])
        report = parse(output, 'emulator-tested')
        self.assertTrue(report['complete'])
        self.assertEqual(1, report['passed'])
        self.assertEqual(1, report['assumptions'])

    def test_failure_or_missing_completion_is_not_success(self):
        for output in ['INSTRUMENTATION_STATUS_CODE: -2\nOK (1 test)',
                       'INSTRUMENTATION_STATUS_CODE: 1', 'OK (0 tests)']:
            self.assertFalse(parse(output, 'emulator-tested')['complete'])

    def test_fixture_generation_is_deterministic_and_bilingual(self):
        self.assertEqual(dataset(), dataset())
        self.assertEqual({'en', 'sw'}, {case['language'] for case in dataset()['queries']})
        self.assertEqual(4, sum(case['expected'] is None for case in dataset()['finance']))


if __name__ == '__main__':
    unittest.main()
