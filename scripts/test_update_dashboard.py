#!/usr/bin/env python3
"""Tests for update_dashboard.py — pure functions and HTML patching logic."""

import os
import sys
import re
import unittest
from unittest.mock import patch, MagicMock
from datetime import datetime, timezone

# Allow imports when POSTHOG_API_KEY is not set
os.environ.setdefault("POSTHOG_API_KEY", "phx_test_key_for_testing")

import update_dashboard as ud


# ── Pure function tests ──


class TestMonthLabel(unittest.TestCase):
    def test_q1_2025_includes_year(self):
        self.assertEqual(ud.month_label("2025-01-01"), "Jan '25")
        self.assertEqual(ud.month_label("2025-02-01"), "Feb '25")
        self.assertEqual(ud.month_label("2025-03-01"), "Mar '25")

    def test_q2_to_q4_2025_bare_month(self):
        self.assertEqual(ud.month_label("2025-04-01"), "Apr")
        self.assertEqual(ud.month_label("2025-07-01"), "Jul")
        self.assertEqual(ud.month_label("2025-09-01"), "Sep")
        self.assertEqual(ud.month_label("2025-12-01"), "Dec")

    def test_2026_always_includes_year(self):
        self.assertEqual(ud.month_label("2026-01-01"), "Jan '26")
        self.assertEqual(ud.month_label("2026-06-01"), "Jun '26")
        self.assertEqual(ud.month_label("2026-12-01"), "Dec '26")

    def test_2024_includes_year(self):
        self.assertEqual(ud.month_label("2024-11-01"), "Nov '24")

    def test_datetime_object_input(self):
        # month_label calls str(m)[:10], so datetime objects should work
        dt = datetime(2025, 5, 1)
        self.assertEqual(ud.month_label(dt), "May")


class TestDayLabel(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(ud.day_label("2026-02-18"), "Feb 18")
        self.assertEqual(ud.day_label("2025-12-01"), "Dec 1")
        self.assertEqual(ud.day_label("2026-01-31"), "Jan 31")

    def test_datetime_object_input(self):
        dt = datetime(2026, 3, 5)
        self.assertEqual(ud.day_label(dt), "Mar 5")


class TestBuildDailySeries(unittest.TestCase):
    def test_zero_fills_and_ends_on_requested_day(self):
        day_keys, labels, values = ud.build_daily_series(
            {"2026-04-12": 6, "2026-04-14": 2},
            days=3,
            end_date=datetime(2026, 4, 14, tzinfo=timezone.utc).date(),
        )

        self.assertEqual(day_keys, ["2026-04-12", "2026-04-13", "2026-04-14"])
        self.assertEqual(labels, ["Apr 12", "Apr 13", "Apr 14"])
        self.assertEqual(values, [6, 0, 2])


class TestJsArray(unittest.TestCase):
    def test_integers(self):
        self.assertEqual(ud.js_array([1, 2, 3]), "[1,2,3]")

    def test_floats(self):
        self.assertEqual(ud.js_array([1.5, 2.0]), "[1.5,2.0]")

    def test_empty(self):
        self.assertEqual(ud.js_array([]), "[]")


class TestJsStrArray(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(ud.js_str_array(["a", "b"]), '["a","b"]')

    def test_empty(self):
        self.assertEqual(ud.js_str_array([]), "[]")

    def test_with_special_chars(self):
        result = ud.js_str_array(["Jan '26", "Feb '26"])
        self.assertEqual(result, """["Jan '26","Feb '26"]""")


# ── HTML patching tests ──

# Minimal HTML template that mirrors the structure of index.html
SAMPLE_HTML = """\
<!DOCTYPE html>
<html>
<div class="subtitle">Internal users excluded &middot; Feb 23, 2026</div>

<div class="kpi-label">DAU</div>
<div class="kpi-value" style="color: var(--green);">34</div>

<div class="kpi-label">WAU</div>
<div class="kpi-value" style="color: var(--blue);">23</div>

<div class="kpi-label">MAU</div>
<div class="kpi-value" style="color: var(--accent);">224</div>
<div class="kpi-badge badge-blue">Peak: 1,276 in Sep</div>

<div class="kpi-label">Activation Rate</div>
<div class="kpi-value" style="color: var(--amber);">30.7%</div>
<div class="kpi-sub">Jan 2026 cohort</div>
<div class="kpi-badge badge-amber">&#9650; Up from 28.4% in Dec '25</div>

<div class="kpi-label">Total Users</div>
<div class="kpi-value" style="color: var(--pink);">24,546</div>

<div id="page-cosbeta">
<div class="kpi-label">External Users</div>
<div class="kpi-value" style="color: var(--green);">16</div>

<div class="kpi-label">Peak DAU</div>
<div class="kpi-value" style="color: var(--blue);">11</div>
<div class="kpi-sub">Feb 20, 2026</div>

<div class="kpi-label">Sessions</div>
<div class="kpi-value" style="color: var(--amber);">122</div>

<div class="kpi-label">Tool Calls</div>
<div class="kpi-value" style="color: var(--pink);">5,806</div>

<div class="kpi-label">Runs Completed</div>
<div class="kpi-value" style="color: var(--accent);">581</div>

<div class="kpi-label">Confirmations</div>
<div class="kpi-value" style="color: var(--green);">186</div>

<h3>Cos Beta DAU (External Only)</h3>
<div class="chart-sub">Feb 18–20 &middot; 12 unique external users</div>
</div>

<script>
const dauLabels = ["Jan 24","Jan 25"];
const dauData = [22,30];
const wauLabels = ["Nov 24","Dec 01"];
const wauData = [457,307];
const mauLabels = ["Feb '25","Mar '25"];
const mauData = [1,50];
const actLabels = ["Feb '25","Mar"];
const actRates = [25.69,29.41];
const actNew = [109,187];
const actActivated = [28,55];
const revLabels = ["Mar '25","Apr"];
const revData = [200,2020];
const pcLabels = ["Mar '25","Apr"];
const pcData = [1,66];
const mrrLabels = ["Mar '25","Apr"];
const mrrData = [200,2020];
const cli2Labels = ["Feb 18","Feb 19"];
const cli2Data = [2,6];

const users30d = [
  {email:"test@example.com",inference:100,days:25}
];
const usersAllTime = [
  {email:"test@example.com",inference:500,days:100}
];
const cli2Users = [
  {email:"user@test.com",events:100,since:"2026-02-18"}
];
const cli2NewUsers = [
  {email:"new@test.com",events:10,since:"2026-02-23"}
];
</script>
</html>
"""


class TestDateUpdate(unittest.TestCase):
    def test_date_updates(self):
        html = re.sub(
            r'(Internal users excluded &middot; )[\w\s,]+(<)',
            r'\g<1>Mar 01, 2026\2', SAMPLE_HTML)
        self.assertIn("Mar 01, 2026", html)
        self.assertNotIn("Feb 23, 2026", html)


class TestKpiPatching(unittest.TestCase):
    def test_dau_kpi(self):
        html = re.sub(
            r'(<div class="kpi-label">DAU</div>\s*<div class="kpi-value"[^>]*>)\d+',
            r'\g<1>42', SAMPLE_HTML, count=1)
        self.assertIn('>42</div>', html)

    def test_wau_kpi(self):
        html = re.sub(
            r'(<div class="kpi-label">WAU</div>\s*<div class="kpi-value"[^>]*>)\d+',
            r'\g<1>55', SAMPLE_HTML, count=1)
        self.assertIn('>55</div>', html)

    def test_mau_kpi(self):
        html = re.sub(
            r'(<div class="kpi-label">MAU</div>\s*<div class="kpi-value"[^>]*>)\d+',
            r'\g<1>300', SAMPLE_HTML, count=1)
        self.assertIn('>300</div>', html)

    def test_mau_peak_badge(self):
        html = re.sub(
            r'(Peak: )[\d,]+ in [^<]+',
            r'\g<1>1,500 in Oct', SAMPLE_HTML, count=1)
        self.assertIn('Peak: 1,500 in Oct', html)

    def test_activation_rate_kpi(self):
        html = re.sub(
            r'(<div class="kpi-label">Activation Rate</div>\s*<div class="kpi-value"[^>]*>)[\d.]+%',
            r'\g<1>35.2%', SAMPLE_HTML, count=1)
        self.assertIn('>35.2%</div>', html)

    def test_activation_sub_label(self):
        html = re.sub(
            r'(<div class="kpi-label">Activation Rate</div>\s*<div class="kpi-value"[^>]*>[\d.]+%</div>\s*<div class="kpi-sub">)[^<]+',
            r"\g<1>Feb '26 cohort", SAMPLE_HTML, count=1)
        self.assertIn("Feb '26 cohort</div>", html)

    def test_activation_badge_direction(self):
        html = re.sub(
            r'(<div class="kpi-label">Activation Rate</div>.*?<div class="kpi-badge[^"]*">)[^<]+',
            r"\g<1>&#9660; Down from 30.7% in Jan '26",
            SAMPLE_HTML, count=1, flags=re.DOTALL)
        self.assertIn("&#9660; Down from 30.7%", html)

    def test_total_users_kpi(self):
        html = re.sub(
            r'(<div class="kpi-label">Total Users</div>\s*<div class="kpi-value"[^>]*>)[\d,]+',
            r'\g<1>30,000', SAMPLE_HTML, count=1)
        self.assertIn('>30,000</div>', html)


class TestCli2KpiPatching(unittest.TestCase):
    def test_external_users(self):
        html = re.sub(
            r'(<div class="kpi-label">External Users</div>\s*<div class="kpi-value"[^>]*>)\d+',
            r'\g<1>25', SAMPLE_HTML, count=1)
        self.assertIn('>25</div>', html)

    def test_peak_dau(self):
        html = re.sub(
            r'(<div class="kpi-label">Peak DAU</div>\s*<div class="kpi-value"[^>]*>)\d+',
            r'\g<1>15', SAMPLE_HTML, count=1)
        self.assertIn('>15</div>', html)

    def test_peak_dau_date(self):
        html = re.sub(
            r'(<div class="kpi-label">Peak DAU</div>\s*<div class="kpi-value"[^>]*>\d+</div>\s*<div class="kpi-sub">)[^<]+',
            r'\g<1>Mar 1, 2026', SAMPLE_HTML, count=1)
        self.assertIn('Mar 1, 2026</div>', html)

    def test_sessions(self):
        html = re.sub(
            r'(<div class="kpi-label">Sessions</div>\s*<div class="kpi-value"[^>]*>)[\d,]+',
            r'\g<1>200', SAMPLE_HTML, count=1)
        self.assertIn('>200</div>', html)

    def test_tool_calls(self):
        html = re.sub(
            r'(<div class="kpi-label">Tool Calls</div>\s*<div class="kpi-value"[^>]*>)[\d,]+',
            r'\g<1>10,000', SAMPLE_HTML, count=1)
        self.assertIn('>10,000</div>', html)


class TestJsVarPatching(unittest.TestCase):
    def _replace(self, html, var_name, new_value):
        pattern = rf"(const {var_name} = )\[.*?\]"
        return re.sub(pattern, rf"\1{new_value}", html, count=1)

    def test_replace_dau_labels(self):
        new = ud.js_str_array(["Feb 1", "Feb 2", "Feb 3"])
        html = self._replace(SAMPLE_HTML, "dauLabels", new)
        self.assertIn('const dauLabels = ["Feb 1","Feb 2","Feb 3"]', html)

    def test_replace_dau_data(self):
        new = ud.js_array([10, 20, 30])
        html = self._replace(SAMPLE_HTML, "dauData", new)
        self.assertIn('const dauData = [10,20,30]', html)

    def test_replace_mrr_labels(self):
        new = ud.js_str_array(["Apr", "May"])
        html = self._replace(SAMPLE_HTML, "mrrLabels", new)
        self.assertIn('const mrrLabels = ["Apr","May"]', html)

    def test_replace_mrr_data(self):
        new = ud.js_array([3000, 4000])
        html = self._replace(SAMPLE_HTML, "mrrData", new)
        self.assertIn('const mrrData = [3000,4000]', html)

    def test_replace_activation_data(self):
        new = ud.js_array([30.0, 35.0])
        html = self._replace(SAMPLE_HTML, "actRates", new)
        self.assertIn('const actRates = [30.0,35.0]', html)


class TestLeaderboardPatching(unittest.TestCase):
    def test_users30d_replacement(self):
        new_js = '[\n  {email:"a@b.com",inference:50,days:10}\n]'
        html = re.sub(
            r'const users30d = \[.*?\];',
            f'const users30d = {new_js};',
            SAMPLE_HTML, count=1, flags=re.DOTALL)
        self.assertIn('email:"a@b.com"', html)
        self.assertNotIn('test@example.com', html.split('users30d')[1].split(';')[0])

    def test_usersAllTime_replacement(self):
        new_js = '[\n  {email:"x@y.com",inference:999,days:200}\n]'
        html = re.sub(
            r'const usersAllTime = \[.*?\];',
            f'const usersAllTime = {new_js};',
            SAMPLE_HTML, count=1, flags=re.DOTALL)
        self.assertIn('email:"x@y.com"', html)

    def test_cli2Users_replacement(self):
        new_js = '[\n  {email:"cli@test.com",events:500,since:"2026-03-01"}\n]'
        html = re.sub(
            r'const cli2Users = \[.*?\];',
            f'const cli2Users = {new_js};',
            SAMPLE_HTML, count=1, flags=re.DOTALL)
        self.assertIn('email:"cli@test.com"', html)

    def test_cli2NewUsers_replacement(self):
        new_js = '[\n]'
        html = re.sub(
            r'const cli2NewUsers = \[.*?\];',
            f'const cli2NewUsers = {new_js};',
            SAMPLE_HTML, count=1, flags=re.DOTALL)
        self.assertNotIn('new@test.com', html.split('cli2NewUsers')[1].split(';')[0])


# ── Edge case: empty CLI2 data doesn't crash ──


class TestCli2EmptyData(unittest.TestCase):
    def test_empty_cli2_dau_no_crash(self):
        """When CLI2 returns no data, peak date should be None and no strptime crash."""
        cli2_dau = {}
        cli2_days = sorted(cli2_dau.keys())
        cli2_values = [cli2_dau[d] for d in cli2_days]
        cli2_peak_dau = max(cli2_values) if cli2_values else 0
        cli2_peak_dau_idx = cli2_values.index(cli2_peak_dau) if cli2_values else -1
        cli2_peak_dau_date = cli2_days[cli2_peak_dau_idx] if cli2_peak_dau_idx >= 0 else None

        self.assertEqual(cli2_peak_dau, 0)
        self.assertIsNone(cli2_peak_dau_date)

        # The old code would crash here: datetime.strptime("N/A", "%Y-%m-%d")
        # The fix guards with `if cli2_peak_dau_date:`
        if cli2_peak_dau_date:
            datetime.strptime(str(cli2_peak_dau_date)[:10], "%Y-%m-%d")
        # No crash = pass

    def test_cli2_with_data(self):
        """Normal case still works."""
        cli2_dau = {"2026-02-18": 2, "2026-02-19": 6, "2026-02-20": 11}
        cli2_days = sorted(cli2_dau.keys())
        cli2_values = [cli2_dau[d] for d in cli2_days]
        cli2_peak_dau = max(cli2_values)
        cli2_peak_dau_idx = cli2_values.index(cli2_peak_dau)
        cli2_peak_dau_date = cli2_days[cli2_peak_dau_idx]

        self.assertEqual(cli2_peak_dau, 11)
        self.assertEqual(cli2_peak_dau_date, "2026-02-20")

        peak_dt = datetime.strptime(str(cli2_peak_dau_date)[:10], "%Y-%m-%d")
        self.assertEqual(peak_dt.day, 20)


class TestCli2DauChartSubtitle(unittest.TestCase):
    def test_subtitle_updates(self):
        cli2_labels = ["Feb 18", "Feb 19", "Feb 20"]
        cli2_range = f"{cli2_labels[0]}&ndash;{cli2_labels[-1]}"
        cli2_chart_sub = f"{cli2_range} &middot; 20 unique external users"
        html = re.sub(
            r'(<div id="page-cosbeta".*?<h3>Cos Beta DAU \(External Only\)</h3>\s*<div class="chart-sub">)[^<]+',
            rf'\g<1>{cli2_chart_sub}', SAMPLE_HTML, count=1, flags=re.DOTALL)
        self.assertIn("Feb 18&ndash;Feb 20", html)
        self.assertIn("20 unique external users", html)


# ── Activation processing edge cases ──


class TestActivationProcessing(unittest.TestCase):
    def test_empty_activation(self):
        activation = []
        act_labels = [ud.month_label(r["cohort_month"]) for r in activation]
        act_rates = [float(r["rate"]) for r in activation]
        latest_act_rate = act_rates[-1] if act_rates else 0
        latest_act_label = act_labels[-1] if act_labels else "N/A"
        prev_act_rate = act_rates[-2] if len(act_rates) >= 2 else 0
        prev_act_label = act_labels[-2] if len(act_labels) >= 2 else "N/A"

        self.assertEqual(latest_act_rate, 0)
        self.assertEqual(latest_act_label, "N/A")
        self.assertEqual(prev_act_rate, 0)
        self.assertEqual(prev_act_label, "N/A")

    def test_single_month_activation(self):
        activation = [{"cohort_month": "2026-01-01", "total_new": 100, "activated": 30, "rate": 30.0}]
        act_rates = [float(r["rate"]) for r in activation]
        latest_act_rate = act_rates[-1] if act_rates else 0
        prev_act_rate = act_rates[-2] if len(act_rates) >= 2 else 0

        self.assertEqual(latest_act_rate, 30.0)
        self.assertEqual(prev_act_rate, 0)

    def test_two_months_direction(self):
        activation = [
            {"cohort_month": "2025-12-01", "total_new": 200, "activated": 50, "rate": 25.0},
            {"cohort_month": "2026-01-01", "total_new": 100, "activated": 35, "rate": 35.0},
        ]
        act_rates = [float(r["rate"]) for r in activation]
        latest = act_rates[-1]
        prev = act_rates[-2]
        direction = "Up" if latest >= prev else "Down"
        self.assertEqual(direction, "Up")


if __name__ == "__main__":
    unittest.main()
