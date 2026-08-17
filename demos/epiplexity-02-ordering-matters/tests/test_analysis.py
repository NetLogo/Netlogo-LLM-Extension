import csv
import sys
import unittest
from pathlib import Path

DEMO_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEMO_DIR))

import trajectory_analysis as ta  # noqa: E402


class TestTrajectoryAnalysis(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data_path = DEMO_DIR / "data" / "trajectory-raw.txt"
        cls.output_path = DEMO_DIR / "results" / "trajectory-analysis.csv"

        if not cls.data_path.exists() or cls.data_path.stat().st_size == 0:
            ta.bootstrap_data(cls.data_path)

        ta.main(
            [
                "--mode",
                "mock",
                "--input",
                "data/trajectory-raw.txt",
                "--output",
                "results/trajectory-analysis.csv",
                "--config",
                "config.txt",
                "--window-size",
                "8",
                "--shuffle-seed",
                "177",
            ]
        )

    def test_raw_trajectory_parse(self):
        events = ta.parse_trajectory(self.data_path)
        self.assertGreaterEqual(len(events), 100)
        self.assertEqual(7, len(self.data_path.read_text(encoding="utf-8").splitlines()[0].split(",")))

    def test_three_orderings_preserve_count(self):
        events = ta.parse_trajectory(self.data_path)
        orderings = ta.build_orderings(events, seed=177)
        self.assertEqual({"forward", "reversed", "shuffled"}, set(orderings.keys()))
        self.assertEqual(len(events), len(orderings["forward"]))
        self.assertEqual(len(events), len(orderings["reversed"]))
        self.assertEqual(len(events), len(orderings["shuffled"]))

    def test_output_csv_schema(self):
        self.assertTrue(self.output_path.exists())
        with self.output_path.open("r", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            self.assertEqual(ta.REQUIRED_COLUMNS, reader.fieldnames)

    def test_no_nan_or_empty_values(self):
        with self.output_path.open("r", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                for col in ta.REQUIRED_COLUMNS:
                    val = row[col]
                    self.assertNotEqual("", val)
                    self.assertNotEqual("nan", str(val).strip().lower())
                    self.assertNotEqual("none", str(val).strip().lower())

    def test_prediction_choices_valid(self):
        with self.output_path.open("r", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                self.assertIn(row["predicted_action"], ta.ACTIONS)
                self.assertIn(row["actual_action"], ta.ACTIONS)

    def test_coherence_and_entropy_bounds(self):
        with self.output_path.open("r", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                coherence = float(row["coherence"])
                entropy = float(row["prediction_entropy"])
                self.assertGreaterEqual(coherence, 0.0)
                self.assertLessEqual(coherence, 1.0)
                self.assertGreaterEqual(entropy, 0.0)
                self.assertLessEqual(entropy, 1.0)

    def test_expected_ordering_gap(self):
        with self.output_path.open("r", encoding="utf-8") as handle:
            reader = csv.DictReader(handle)
            rows = [
                ta.AnalysisRow(
                    ordering=r["ordering"],
                    tick=int(r["tick"]),
                    event_index=int(r["event_index"]),
                    agent_id=r["agent_id"],
                    rule_hypothesis=r["rule_hypothesis"],
                    predicted_action=r["predicted_action"],
                    actual_action=r["actual_action"],
                    accuracy=int(r["accuracy"]),
                    coherence=float(r["coherence"]),
                    prediction_entropy=float(r["prediction_entropy"]),
                )
                for r in reader
            ]

        summary = ta.summarize(rows)
        self.assertGreater(summary["forward"]["accuracy"], summary["reversed"]["accuracy"])
        self.assertGreater(summary["reversed"]["accuracy"], summary["shuffled"]["accuracy"])

        # Target demo thresholds from the spec.
        self.assertGreaterEqual(summary["forward"]["accuracy"], 0.70)
        self.assertLessEqual(summary["reversed"]["accuracy"], 0.50)
        self.assertLessEqual(summary["shuffled"]["accuracy"], 0.40)


if __name__ == "__main__":
    unittest.main()
