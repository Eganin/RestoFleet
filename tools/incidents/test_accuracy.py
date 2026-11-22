"""Тесты сопоставления эталона и инцидентов: python3 -I tools/incidents/test_accuracy.py."""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import accuracy  # noqa: E402

MIN = 60 * 1000


def truth(restaurant, cls, start, end, device=None):
    return {"scenario": "t", "restaurant": restaurant, "device": device, "expected_class": cls, "from": start,
            "to": end}


def incident(kind, codes, start, end=None):
    return {"kind": kind, "restaurants": [{"code": c} for c in codes], "startedAt": start, "resolvedAt": end}


def alert(device, start, end=None, parent=None, rule="R-01"):
    return {"ruleId": rule, "deviceId": device, "openedAt": start, "resolvedAt": end, "parentIncidentId": parent}


class EvaluateTest(unittest.TestCase):
    def test_matching_class_and_restaurant_within_tolerance_is_correct(self):
        t = [truth("S8-01", "backend_global", 0, 10 * MIN), truth("S8-02", "backend_global", 0, 10 * MIN)]
        incs = [incident("backend_global", ["S8-01", "S8-02"], 2 * MIN, 12 * MIN)]
        r = accuracy.evaluate(t, incs, [])
        self.assertEqual((r["correct"], r["total"]), (2, 2))

    def test_incident_starting_just_inside_tolerance_counts_and_outside_does_not(self):
        row = [truth("S8-01", "backend_local", 0, MIN)]
        inside = accuracy.evaluate(row, [incident("backend_local", ["S8-01"], 3 * MIN + MIN - 1, 5 * MIN)], [])
        outside = accuracy.evaluate(row, [incident("backend_local", ["S8-01"], 4 * MIN, 5 * MIN)], [])
        self.assertEqual(inside["correct"], 1)
        self.assertEqual(outside["correct"], 0)
        self.assertEqual(outside["matrix"][("backend_local", "none")], 1)

    def test_wrong_class_is_recorded_in_the_matrix(self):
        r = accuracy.evaluate([truth("S8-03", "restaurant_network", 0, MIN)],
                              [incident("backend_local", ["S8-03"], 0, MIN)], [])
        self.assertEqual(r["matrix"][("restaurant_network", "backend_local")], 1)
        self.assertEqual(r["accuracy"], 0.0)

    def test_expected_class_wins_among_several_incidents(self):
        incs = [incident("backend_local", ["S8-04"], 0, MIN), incident("backend_global", ["S8-04"], MIN, 2 * MIN)]
        r = accuracy.evaluate([truth("S8-04", "backend_global", 0, 2 * MIN)], incs, [])
        self.assertEqual(r["correct"], 1)

    def test_device_class_needs_an_unsuppressed_r01_of_that_device(self):
        row = [truth("S8-05", "device", 0, MIN, device="d-1")]
        self.assertEqual(accuracy.evaluate(row, [], [alert("d-1", 30 * 1000)])["correct"], 1)
        self.assertEqual(accuracy.evaluate(row, [], [alert("d-1", 30 * 1000, parent="i-1")])["correct"], 0)
        self.assertEqual(accuracy.evaluate(row, [], [alert("d-2", 30 * 1000)])["correct"], 0)
        self.assertEqual(accuracy.evaluate(row, [], [alert("d-1", 30 * 1000, rule="R-05")])["correct"], 0)

    def test_negative_example_is_correct_without_incident_and_wrong_with_one(self):
        row = [truth("S8-06", "none", 0, MIN)]
        self.assertEqual(accuracy.evaluate(row, [], [])["correct"], 1)
        self.assertEqual(accuracy.evaluate(row, [incident("restaurant_network", ["S8-06"], 0, MIN)], [])["correct"], 0)

    def test_open_incident_extends_to_the_end_of_data(self):
        r = accuracy.evaluate([truth("S8-07", "backend_local", 10 * MIN, 11 * MIN)],
                              [incident("backend_local", ["S8-07"], 0, None)], [])
        self.assertEqual(r["correct"], 1)

    def test_degradation_is_excluded_and_empty_truth_has_no_accuracy(self):
        r = accuracy.evaluate([truth("S8-08", "degradation", 0, MIN)], [], [])
        self.assertEqual(r["total"], 0)
        self.assertIsNone(r["accuracy"])


    def test_incidents_after_the_end_of_the_run_are_ignored(self):
        row = [truth("S8-09", "none", 0, MIN)]
        late = [incident("restaurant_network", ["S8-09"], 3 * MIN, None)]
        self.assertEqual(accuracy.evaluate(row, late, [])["correct"], 0)
        self.assertEqual(accuracy.evaluate(row, late, [], until=2 * MIN)["correct"], 1)
        dev = [truth("S8-10", "device", 0, MIN, device="d-1")]
        self.assertEqual(accuracy.evaluate(dev, [], [alert("d-1", 3 * MIN)], until=2 * MIN)["correct"], 0)

    def test_incidents_left_open_by_the_previous_run_are_ignored(self):
        row = [truth("S8-09", "none", 10 * MIN, 11 * MIN)]
        left = [incident("restaurant_network", ["S8-09"], 0, None)]
        self.assertEqual(accuracy.evaluate(row, left, [])["correct"], 0)
        self.assertEqual(accuracy.evaluate(row, left, [], since=5 * MIN)["correct"], 1)


class CliTest(unittest.TestCase):
    def test_main_reads_files_and_writes_markdown(self):
        with tempfile.TemporaryDirectory() as d:
            truth_path = os.path.join(d, "truth.csv")
            with open(truth_path, "w", encoding="utf-8") as f:
                f.write("scenario,restaurant,device,expected_class,from,to\n")
                f.write(f"t,S8-01,,backend_local,0,{MIN}\n")
                f.write(f"t,S8-02,d-9,device,0,{MIN}\n")
            inc_path = os.path.join(d, "inc.json")
            with open(inc_path, "w", encoding="utf-8") as f:
                json.dump({"items": [incident("backend_local", ["S8-01"], 0, MIN)]}, f)
            al_path = os.path.join(d, "al.json")
            with open(al_path, "w", encoding="utf-8") as f:
                json.dump([alert("d-9", 0, MIN)], f)
            out = os.path.join(d, "acc.md")
            self.assertEqual(accuracy.main(["--truth", truth_path, "--incidents", inc_path, "--alerts", al_path,
                                            "--out", out, "--title", "T"]), 0)
            with open(out, encoding="utf-8") as f:
                text = f.read()
            self.assertIn("# T", text)
            self.assertIn("**100.0 %**", text)
            self.assertIn("| backend_local | 0 | 1 | 0 | 0 | 0 |", text)


if __name__ == "__main__":
    unittest.main()
