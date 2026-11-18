import unittest
from performance_report import benchmark_report, percentiles


class PerformanceReportTest(unittest.TestCase):
    def test_nearest_rank_and_missing_results(self):
        self.assertEqual({"count": 20, "p50": 10, "p95": 19}, percentiles(list(range(1, 21))))
        self.assertEqual({"count": 0, "p50": None, "p95": None}, percentiles([]))
        self.assertEqual([], benchmark_report({})["results"])

    def test_androidx_metrics_keep_iterations_separate_from_pooled_frames(self):
        report = benchmark_report({"benchmarks": [{
            "className": "StartupBenchmark", "name": "cold",
            "metrics": {"timeToInitialDisplayMs": {"runs": [101, 90, 102]}},
            "sampledMetrics": {"frameDurationCpuMs": {"runs": [[4, 6], [8, 10]]}},
        }]})
        startup, frames = report["results"]
        self.assertEqual((101, 102, "iteration"), (startup["p50"], startup["p95"], startup["sample_kind"]))
        self.assertEqual((6, 10, 4, "pooled_samples"), (frames["p50"], frames["p95"], frames["count"], frames["sample_kind"]))

    def test_invalid_numbers_are_not_silently_reported_as_measurements(self):
        report = benchmark_report({"benchmarks": [{"metrics": {"missing": {"runs": [None, True, float("nan")]}}}]})
        self.assertEqual(0, report["results"][0]["count"])
        self.assertIsNone(report["results"][0]["p95"])


if __name__ == "__main__":
    unittest.main()
