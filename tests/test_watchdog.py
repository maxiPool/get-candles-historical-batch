"""Tests for bin/notify_email.py and bin/freshness-check.py.  Run: /usr/bin/python3 -m unittest discover -s tests"""
import datetime as dt
import importlib.util
import os
import sys
import tempfile
import unittest

BIN = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "bin")
sys.path.insert(0, BIN)
import notify_email  # noqa: E402

_spec = importlib.util.spec_from_file_location("freshness_check", os.path.join(BIN, "freshness-check.py"))
fc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fc)

UTC = dt.timezone.utc


def toronto(y, m, d, hh, mm=0):
    return dt.datetime(y, m, d, hh, mm, tzinfo=fc.TORONTO)


class NotifyEmailTest(unittest.TestCase):

    def write(self, text):
        f = tempfile.NamedTemporaryFile("w", suffix=".yml", delete=False)
        f.write(text)
        f.close()
        self.addCleanup(os.unlink, f.name)
        return f.name

    def test_dotted_spring_mail_key(self):
        cfg = notify_email.load_mail_config(self.write(
            "infra:\n  oanda:\n    v20:\n      token: x\nspring.mail:\n  username: a@x\n  password: p\n  recipient: r@x\n"))
        self.assertEqual(("a@x", "p", "r@x", "smtp.gmail.com", 587),
                         (cfg["username"], cfg["password"], cfg["recipient"], cfg["host"], cfg["port"]))

    def test_nested_spring_mail_key(self):
        cfg = notify_email.load_mail_config(self.write(
            "spring:\n  mail:\n    username: a@x\n    password: p\n    recipient: r@x\n    host: h\n    port: 25\n"))
        self.assertEqual(("h", 25), (cfg["host"], cfg["port"]))

    def test_missing_keys_raise_without_echoing_values(self):
        with self.assertRaises(ValueError) as e:
            notify_email.load_mail_config(self.write("spring.mail:\n  username: a@x\n  password: secretvalue\n"))
        self.assertIn("recipient", str(e.exception))
        self.assertNotIn("secretvalue", str(e.exception))

    def test_send_uses_starttls_and_login(self):
        calls = []

        class FakeSMTP:
            def __init__(self, host, port, timeout):
                calls.append(("connect", host, port))

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def starttls(self):
                calls.append(("starttls",))

            def login(self, u, p):
                calls.append(("login", u))

            def send_message(self, msg):
                calls.append(("send", msg["To"], msg["Subject"], msg.get_content().strip()))

        path = self.write("spring.mail:\n  username: a@x\n  password: p\n  recipient: r@x\n")
        notify_email.notify("run failed (exit 1)", "body text", config_path=path, smtp_factory=FakeSMTP)
        self.assertEqual([("connect", "smtp.gmail.com", 587), ("starttls",), ("login", "a@x"),
                          ("send", "r@x", "MONITORING-GetCandlesBatch: run failed (exit 1)", "body text")], calls)


class FreshnessCheckTest(unittest.TestCase):

    def marker(self, when):
        f = tempfile.NamedTemporaryFile("w", delete=False)
        f.write("%d %s new_candles=1 pairs=246\n" % (int(when.timestamp()), when.isoformat()))
        f.close()
        self.addCleanup(os.unlink, f.name)
        return f.name

    def run_check(self, marker, now, dry_run=False, notifier=None):
        sent = []
        code = fc.main(["--marker", marker] + (["--dry-run"] if dry_run else []), now=now,
                       notifier=notifier or (lambda s, b: sent.append((s, b))))
        return code, sent

    def test_one_missed_weekday_is_tolerated(self):
        last = toronto(2026, 11, 2, 17, 1)   # Monday success
        code, sent = self.run_check(self.marker(last), toronto(2026, 11, 3, 18))  # Tuesday failed
        self.assertEqual((0, []), (code, sent))

    def test_two_missed_weekdays_alert(self):
        last = toronto(2026, 11, 2, 17, 1)
        code, sent = self.run_check(self.marker(last), toronto(2026, 11, 4, 18))  # Tue + Wed failed
        self.assertEqual(2, code)
        self.assertEqual(1, len(sent))
        self.assertIn("not succeeded for 2 weekdays", sent[0][1])

    def test_weekend_days_do_not_count(self):
        last = toronto(2026, 10, 29, 17, 1)  # Thursday success, Friday failed
        self.assertEqual(0, self.run_check(self.marker(last), toronto(2026, 11, 1, 18))[0])  # Sunday
        self.assertEqual(2, self.run_check(self.marker(last), toronto(2026, 11, 2, 18))[0])  # Monday failed too

    def test_today_counts_only_after_the_daily_runs(self):
        last = toronto(2026, 11, 2, 17, 1)
        self.assertEqual(1, fc.missed_weekdays(last, toronto(2026, 11, 4, 9)))   # Wed morning: only Tue missed
        self.assertEqual(2, fc.missed_weekdays(last, toronto(2026, 11, 4, 18)))  # Wed evening

    def test_missing_or_garbage_marker_alerts(self):
        code, sent = self.run_check("/nonexistent/last-success.txt", toronto(2026, 11, 4, 18))
        self.assertEqual(2, code)
        self.assertIn("No readable success marker", sent[0][1])
        f = tempfile.NamedTemporaryFile("w", delete=False)
        f.write("garbage")
        f.close()
        self.addCleanup(os.unlink, f.name)
        self.assertEqual(2, self.run_check(f.name, toronto(2026, 11, 4, 18))[0])

    def test_dry_run_does_not_send(self):
        code, sent = self.run_check("/nonexistent", toronto(2026, 11, 4, 18), dry_run=True)
        self.assertEqual((2, []), (code, sent))

    def test_alert_send_failure_exits_1(self):
        def boom(s, b):
            raise OSError("smtp down")
        code, _ = self.run_check("/nonexistent", toronto(2026, 11, 4, 18), notifier=boom)
        self.assertEqual(1, code)

    def test_marker_format_matches_the_java_writer(self):
        # BatchRunner writes "<epoch> <ISO instant> new_candles=N pairs=M"
        f = tempfile.NamedTemporaryFile("w", delete=False)
        f.write("1793656800 2026-11-02T22:00:00Z new_candles=1234 pairs=246\n")
        f.close()
        self.addCleanup(os.unlink, f.name)
        self.assertEqual(dt.datetime(2026, 11, 2, 22, 0, tzinfo=UTC), fc.read_last_success(f.name))


if __name__ == "__main__":
    unittest.main()
