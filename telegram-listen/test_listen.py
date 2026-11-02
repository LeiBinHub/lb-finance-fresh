import unittest
from datetime import datetime, timedelta, timezone

import backfill as listen


class CutoffTest(unittest.TestCase):
    def test_defaults_to_one_week_when_never_stopped(self):
        now = datetime(2026, 10, 28, 12, 0, tzinfo=timezone.utc)
        self.assertEqual(listen.cutoff_at(now, None, days=7), now - timedelta(days=7))

    def test_resumes_from_last_stop_when_gap_is_under_a_week(self):
        now = datetime(2026, 10, 28, 12, 0, tzinfo=timezone.utc)
        stopped = now - timedelta(days=2, hours=3)
        got = listen.cutoff_at(now, stopped, days=7, overlap_sec=60)
        self.assertEqual(got, stopped - timedelta(seconds=60))

    def test_caps_at_one_week_when_stopped_longer(self):
        now = datetime(2026, 10, 28, 12, 0, tzinfo=timezone.utc)
        stopped = now - timedelta(days=10)
        self.assertEqual(listen.cutoff_at(now, stopped, days=7), now - timedelta(days=7))

    def test_naive_last_stop_is_treated_as_utc(self):
        now = datetime(2026, 10, 28, 12, 0, tzinfo=timezone.utc)
        stopped = datetime(2026, 10, 27, 12, 0)
        got = listen.cutoff_at(now, stopped, days=7, overlap_sec=0)
        self.assertEqual(got, datetime(2026, 10, 27, 12, 0, tzinfo=timezone.utc))


if __name__ == "__main__":
    unittest.main()
