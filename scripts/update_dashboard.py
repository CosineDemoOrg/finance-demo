#!/usr/bin/env python3
"""
Regenerates the Cosine team dashboard (index.html) with fresh data from PostHog.

Usage:
  export POSTHOG_API_KEY=phx_...
  python3 scripts/update_dashboard.py

Queries PostHog EU instance (projects 16111 PROD + 16114 Staging) via HogQL.
"""

import csv
import os
import sys
import json
import re
from collections import defaultdict
from datetime import datetime, timedelta, timezone

try:
    import requests
except ImportError:
    sys.exit("Missing 'requests' package. Run: pip3 install requests")

API_KEY = os.environ.get("POSTHOG_API_KEY", "")
if not API_KEY:
    sys.exit("Set POSTHOG_API_KEY env var (PostHog personal API key).")

BASE_URL = "https://eu.posthog.com"
PROD_PROJECT = 16111

# Resolve paths relative to this script's location (scripts/ -> parent)
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DASHBOARD_PATH = os.path.join(SCRIPT_DIR, "..", "index.html")
STRIPE_CSV_PATH = os.path.join(SCRIPT_DIR, "..", "data", "stripe_charges.csv")

INTERNAL_FILTER = """
  AND person.properties.email NOT ILIKE '%@cosine.sh%'
  AND person.properties.email != 'destkramers@gmail.com'
  AND person.properties.email != 'curtisahuang@gmail.com'
  AND person.properties.email != 'tomadowley@gmail.com'
"""

INTERNAL_FILTER_PERSONS = """
  AND properties.email NOT ILIKE '%@cosine.sh%'
  AND properties.email != 'destkramers@gmail.com'
  AND properties.email != 'curtisahuang@gmail.com'
  AND properties.email != 'tomadowley@gmail.com'
"""

# Active user = created or worked on at least one task (any platform).
# Includes web app task events + CLI2/Desktop run completions.
WEBAPP_EVENTS = (
    # Task lifecycle (web app)
    "'create_task','task_started','task_succeeded','task_failed',"
    "'task_closed','task_completed','task_deployed',"
    # Task interaction (web app)
    "'task_chat_message','task_prompt_edited','task_prompt_submitted',"
    "'task_step_retried','task_execution_nudged','task_reopened',"
    "'task_forked','commit_task','merge_task',"
    # Native app task runs (CLI2 + Desktop)
    "'cli2_run_completed','desktop_run_completed'"
)
TASK_EVENTS = WEBAPP_EVENTS

# ── API helpers ──

def hogql(project_id, query):
    """Run a HogQL query and return results as list of dicts."""
    url = f"{BASE_URL}/api/projects/{project_id}/query/"
    resp = requests.post(url, headers={
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    }, json={
        "query": {
            "kind": "HogQLQuery",
            "query": query,
        }
    }, timeout=60)
    resp.raise_for_status()
    data = resp.json()
    cols = data.get("columns", [])
    rows = data.get("results", [])
    return [dict(zip(cols, row)) for row in rows]


def fetch_dau():
    """DAU for past 30 days — all task interactions, external users only."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT toDate(timestamp) as day, count(DISTINCT person_id) as v
        FROM events
        WHERE event IN ({TASK_EVENTS})
          AND timestamp >= now() - INTERVAL 30 DAY
          {INTERNAL_FILTER}
        GROUP BY day ORDER BY day
    """)
    return {str(r["day"]): int(r["v"]) for r in rows}


def fetch_wau():
    """WAU for past 90 days."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT toStartOfWeek(timestamp, 1) as week, count(DISTINCT person_id) as v
        FROM events
        WHERE event IN ({TASK_EVENTS})
          AND timestamp >= now() - INTERVAL 90 DAY
          {INTERNAL_FILTER}
        GROUP BY week ORDER BY week
    """)
    return {str(r["week"]): int(r["v"]) for r in rows}


def fetch_mau():
    """MAU for past 12 months."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT toStartOfMonth(timestamp) as month, count(DISTINCT person_id) as v
        FROM events
        WHERE event IN ({TASK_EVENTS})
          AND timestamp >= now() - INTERVAL 12 MONTH
          {INTERNAL_FILTER}
        GROUP BY month ORDER BY month
    """)
    return {str(r["month"]): int(r["v"]) for r in rows}


def fetch_activation():
    """Monthly activation rate — % of signups who created at least one task.

    Single query: get signup totals per month from persons table,
    then a separate query for activated totals per signup month.
    Avoids passing thousands of emails in an IN clause.
    """
    # Total signups per month
    totals = hogql(PROD_PROJECT, f"""
        SELECT
          toStartOfMonth(created_at) as cohort_month,
          count() as total
        FROM persons
        WHERE created_at >= now() - INTERVAL 12 MONTH
          AND properties.email IS NOT NULL
          AND properties.email != ''
          {INTERNAL_FILTER_PERSONS}
        GROUP BY cohort_month
        ORDER BY cohort_month
    """)
    if not totals:
        return []

    # Activated signups per month — join persons (for signup month) with
    # events (for activation signal) via email.
    activated = hogql(PROD_PROJECT, f"""
        SELECT
          toStartOfMonth(p.created_at) as cohort_month,
          count(DISTINCT p.properties.email) as activated
        FROM persons p
        WHERE p.created_at >= now() - INTERVAL 12 MONTH
          AND p.properties.email IS NOT NULL
          AND p.properties.email != ''
          AND p.properties.email NOT ILIKE '%@cosine.sh%'
          AND p.properties.email != 'destkramers@gmail.com'
          AND p.properties.email != 'curtisahuang@gmail.com'
          AND p.properties.email != 'tomadowley@gmail.com'
          AND p.properties.email IN (
            SELECT DISTINCT person.properties.email
            FROM events
            WHERE event IN ('create_task',
                            'cli2_run_completed', 'desktop_run_completed')
          )
        GROUP BY cohort_month
        ORDER BY cohort_month
    """)
    act_map = {str(r["cohort_month"])[:10]: int(r["activated"]) for r in activated}

    result = []
    for r in totals:
        m = str(r["cohort_month"])[:10]
        t = int(r["total"])
        a = act_map.get(m, 0)
        rate = round(a * 100.0 / t, 2) if t else 0
        result.append({
            "cohort_month": m,
            "total_new": t,
            "activated": a,
            "rate": rate,
        })
    return result[-12:]


def _load_stripe_csv():
    """Load and filter the raw Stripe charges CSV (Paid, amount > 0, since 2025-03)."""
    rows = []
    with open(STRIPE_CSV_PATH, newline="") as f:
        for row in csv.DictReader(f):
            if row["Status"].strip() != "Paid":
                continue
            amount = float(row["Amount"] or 0)
            if amount <= 0:
                continue
            date_str = row["Created date (UTC)"][:10]  # YYYY-MM-DD
            if date_str < "2025-03-01":
                continue
            refunded = float(row["Amount Refunded"] or 0)
            rows.append({
                "date": date_str,
                "month": date_str[:7],  # YYYY-MM
                "net_amount": amount - refunded,
                "customer_id": row["Customer ID"],
            })
    return rows


def fetch_stripe_revenue():
    """Monthly revenue + customer count from raw Stripe CSV."""
    charges = _load_stripe_csv()
    buckets = defaultdict(lambda: {"revenue": 0.0, "customers": set()})
    for c in charges:
        buckets[c["month"]]["revenue"] += c["net_amount"]
        buckets[c["month"]]["customers"].add(c["customer_id"])
    return [
        {"month": f"{m}-01", "revenue": buckets[m]["revenue"], "customers": len(buckets[m]["customers"])}
        for m in sorted(buckets)
    ]


def fetch_mrr():
    """Monthly recurring revenue from raw Stripe CSV."""
    charges = _load_stripe_csv()
    buckets = defaultdict(float)
    for c in charges:
        buckets[c["month"]] += c["net_amount"]
    return [
        {"month": f"{m}-01", "mrr": buckets[m]}
        for m in sorted(buckets)
    ]


def fetch_total_users():
    """Count of all external users who ever triggered an event."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT count(DISTINCT person_id) as total
        FROM events
        WHERE timestamp >= toDateTime('2024-01-01')
          {INTERNAL_FILTER}
    """)
    return int(rows[0]["total"]) if rows else 0


def fetch_leaderboard_30d():
    """Top external users by inference spend over the last 30 days."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT
          person.properties.email as email,
          count(DISTINCT toDate(timestamp)) as days,
          round(sum(toFloat(properties."$ai_total_cost_usd"))) as inference
        FROM events
        WHERE event = '$ai_generation'
          AND timestamp >= now() - INTERVAL 30 DAY
          AND person.properties.email IS NOT NULL
          {INTERNAL_FILTER}
        GROUP BY email
        ORDER BY inference DESC
        LIMIT 15
    """)
    return rows


def fetch_leaderboard_alltime():
    """Top external users by inference spend all time."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT
          person.properties.email as email,
          count(DISTINCT toDate(timestamp)) as days,
          round(sum(toFloat(properties."$ai_total_cost_usd"))) as inference
        FROM events
        WHERE event = '$ai_generation'
          AND timestamp >= toDateTime('2024-01-01')
          AND person.properties.email IS NOT NULL
          {INTERNAL_FILTER}
        GROUP BY email
        ORDER BY inference DESC
        LIMIT 15
    """)
    return rows


# ── North Star Metrics ──

def fetch_wap():
    """Weekly Active Prompters — unique users who submitted at least one task/prompt per week.

    Covers web app (create_task, task_prompt_submitted) and native apps (cli2/desktop_run_completed).
    """
    rows = hogql(PROD_PROJECT, f"""
        SELECT toStartOfWeek(timestamp, 1) as week, count(DISTINCT person_id) as v
        FROM events
        WHERE event IN ('create_task','task_prompt_submitted',
                        'cli2_run_completed','desktop_run_completed')
          AND timestamp >= now() - INTERVAL 90 DAY
          AND person.properties.email IS NOT NULL
          {INTERNAL_FILTER}
        GROUP BY week ORDER BY week
    """)
    return {str(r["week"]): int(r["v"]) for r in rows}


def fetch_activation_7d():
    """7-day activation rate — % of signups who ran their first task within 7 days.

    Two aggregated queries (HogQL 100-row default limit makes row-by-row infeasible):
    1. Total signups per month from persons.created_at
    2. Signups who had a qualifying event within 7 days of signup — via subquery join
    """
    # Total signups per month
    totals = hogql(PROD_PROJECT, f"""
        SELECT toStartOfMonth(created_at) as cohort_month, count() as total
        FROM persons
        WHERE created_at >= now() - INTERVAL 12 MONTH
          AND properties.email IS NOT NULL
          AND properties.email != ''
          {INTERNAL_FILTER_PERSONS}
        GROUP BY cohort_month ORDER BY cohort_month
    """)
    if not totals:
        return []

    # For each signup cohort month, count users whose first task event was
    # within 7 days of their signup date.
    # Strategy: get (signup_month, signup_date) per email from persons aggregated,
    # and (email, first_task_date) per email from events aggregated — both as
    # COUNT/MIN queries so results fit in memory without a row-per-person fetch.
    signup_summary = hogql(PROD_PROJECT, f"""
        SELECT
          toStartOfMonth(created_at) as cohort_month,
          properties.email as email,
          toStartOfDay(created_at) as signup_day
        FROM persons
        WHERE created_at >= now() - INTERVAL 12 MONTH
          AND properties.email IS NOT NULL
          AND properties.email != ''
          {INTERNAL_FILTER_PERSONS}
        ORDER BY created_at
        LIMIT 50000
    """)
    first_task_summary = hogql(PROD_PROJECT, f"""
        SELECT
          person.properties.email as email,
          min(toStartOfDay(timestamp)) as first_task_day
        FROM events
        WHERE event IN ('create_task','cli2_run_completed','desktop_run_completed')
          AND person.properties.email IS NOT NULL
          AND timestamp >= now() - INTERVAL 13 MONTH
          {INTERNAL_FILTER}
        GROUP BY email
        LIMIT 50000
    """)
    first_task_map = {r["email"]: str(r["first_task_day"])[:10] for r in first_task_summary}

    act_buckets = defaultdict(int)
    for r in signup_summary:
        m = str(r["cohort_month"])[:10]
        email = r["email"]
        signup_day = str(r["signup_day"])[:10]
        first_task = first_task_map.get(email)
        if first_task and signup_day:
            try:
                delta = (datetime.strptime(first_task, "%Y-%m-%d") -
                         datetime.strptime(signup_day, "%Y-%m-%d")).days
                if 0 <= delta <= 7:
                    act_buckets[m] += 1
            except Exception:
                pass
    act_map = dict(act_buckets)

    result = []
    for r in totals:
        m = str(r["cohort_month"])[:10]
        t = int(r["total"])
        a = act_map.get(m, 0)
        rate = round(a * 100.0 / t, 2) if t else 0
        result.append({"cohort_month": m, "total_new": t, "activated": a, "rate": rate})
    return result[-12:]


def compute_nrr():
    """Net Revenue Retention from Stripe CSV — month-over-month expansion minus churn."""
    charges = _load_stripe_csv()
    monthly = defaultdict(lambda: defaultdict(float))
    for c in charges:
        monthly[c["month"]][c["customer_id"]] += c["net_amount"]

    months = sorted(monthly.keys())
    result = []
    for i, m in enumerate(months[1:], 1):
        prev_m = months[i - 1]
        prev_custs = set(monthly[prev_m].keys())
        curr_custs = set(monthly[m].keys())
        retained = prev_custs & curr_custs
        expansion = sum(max(0, monthly[m][c] - monthly[prev_m][c]) for c in retained)
        contraction = sum(max(0, monthly[prev_m][c] - monthly[m][c]) for c in retained)
        churned = sum(monthly[prev_m][c] for c in prev_custs - curr_custs)
        prev_rev = sum(monthly[prev_m].values())
        nrr = round((sum(monthly[prev_m][c] for c in retained) + expansion - contraction)
                    / prev_rev * 100, 1) if prev_rev else 0
        result.append({
            "month": f"{m}-01",
            "nrr": nrr,
            "expansion": int(expansion),
            "contraction": int(contraction),
            "churned": int(churned),
        })
    return result[-12:]


def fetch_cli2_dau():
    """Cos Beta DAU (CLI2 + Desktop) from PROD project, external users only."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT toDate(timestamp) as day, count(DISTINCT person_id) as v
        FROM events
        WHERE (event LIKE 'cli2_%' OR event LIKE 'desktop_%')
          AND person.properties.email IS NOT NULL
          {INTERNAL_FILTER}
        GROUP BY day ORDER BY day
    """)
    return {str(r["day"]): int(r["v"]) for r in rows}


def fetch_cli2_users():
    """Cos Beta (CLI2 + Desktop) external user leaderboard from PROD."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT
          person.properties.email as email,
          count(*) as events,
          min(toDate(timestamp)) as first_seen
        FROM events
        WHERE (event LIKE 'cli2_%' OR event LIKE 'desktop_%')
          AND person.properties.email IS NOT NULL
          {INTERNAL_FILTER}
        GROUP BY email
        ORDER BY events DESC
        LIMIT 20
    """)
    return rows


def fetch_cli2_new_users_this_week():
    """Cos Beta (CLI2 + Desktop) users whose first event was this week (Mon–Sun), external only."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT
          email,
          events,
          first_seen
        FROM (
          SELECT
            person.properties.email as email,
            count(*) as events,
            min(toDate(timestamp)) as first_seen
          FROM events
          WHERE (event LIKE 'cli2_%' OR event LIKE 'desktop_%')
            AND person.properties.email IS NOT NULL
            {INTERNAL_FILTER}
          GROUP BY email
        )
        WHERE first_seen >= toStartOfWeek(now(), 1)
        ORDER BY events DESC
        LIMIT 30
    """)
    return rows



def fetch_signups_daily():
    """Daily sign-up counts for the last 30 days (unlimited — for KPI + chart)."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT
          dateTrunc('day', created_at) as signup_date,
          count() as cnt
        FROM persons
        WHERE created_at >= now() - INTERVAL 30 DAY
          AND properties.email IS NOT NULL
          AND properties.email != ''
          {INTERNAL_FILTER_PERSONS}
        GROUP BY signup_date
        ORDER BY signup_date
    """)
    return {str(r["signup_date"])[:10]: int(r["cnt"]) for r in rows}


def fetch_signups_list():
    """Recent external sign-ups (last 30 days) — for table display + activation check."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT
          properties.email as email,
          properties.name as name,
          dateTrunc('day', created_at) as signup_date
        FROM persons
        WHERE created_at >= now() - INTERVAL 30 DAY
          AND properties.email IS NOT NULL
          AND properties.email != ''
          {INTERNAL_FILTER_PERSONS}
        ORDER BY created_at DESC
        LIMIT 500
    """)
    return rows


def fetch_signup_activation(emails):
    """Check which signup emails activated (created at least one task).

    Consistent with fetch_activation() — activation means the user
    explicitly created a task (web app) or completed a run (CLI2/Desktop).
    """
    if not emails:
        return set()
    email_list = ",".join(f"'{e}'" for e in emails)
    rows = hogql(PROD_PROJECT, f"""
        SELECT DISTINCT person.properties.email as email
        FROM events
        WHERE person.properties.email IN ({email_list})
          AND event IN ('create_task',
                        'cli2_run_completed', 'desktop_run_completed')
    """)
    return {r["email"] for r in rows}


def fetch_inference_costs_for_emails(emails):
    """Get all-time inference cost for a list of emails."""
    if not emails:
        return {}
    email_list = ",".join(f"'{e}'" for e in emails)
    rows = hogql(PROD_PROJECT, f"""
        SELECT
          person.properties.email as email,
          sum(toFloat(properties."$ai_total_cost_usd")) as cost_usd
        FROM events
        WHERE event = '$ai_generation'
          AND person.properties.email IN ({email_list})
        GROUP BY email
    """)
    return {r["email"]: round(float(r["cost_usd"])) for r in rows}


def fetch_cli2_stats():
    """Cos Beta (CLI2 + Desktop) aggregate stats from PROD."""
    rows = hogql(PROD_PROJECT, f"""
        SELECT
          count(DISTINCT person_id) as unique_users,
          countIf(event = 'cli2_session_start' OR event = 'desktop_session_start') as sessions,
          countIf(event = 'cli2_tool_call' OR event = 'desktop_tool_call') as tool_calls,
          countIf(event = 'cli2_run_completed' OR event = 'desktop_run_completed') as runs,
          countIf(event = 'cli2_confirmation' OR event = 'desktop_confirmation') as confirmations
        FROM events
        WHERE (event LIKE 'cli2_%' OR event LIKE 'desktop_%')
          AND person.properties.email IS NOT NULL
          {INTERNAL_FILTER}
    """)
    return rows[0] if rows else {}


# ── Build helpers ──

def js_array(values):
    return "[" + ",".join(str(v) for v in values) + "]"


def js_str_array(values):
    return "[" + ",".join(f'"{v}"' for v in values) + "]"


def month_label(m):
    """Convert '2025-04-01' to "Apr '25" style.

    Adds year suffix when the month is in the first quarter of a year
    or when the year is not 2025 (to disambiguate across year boundaries).
    Apr–Dec 2025 get bare month names since they're unambiguous.
    """
    dt = datetime.strptime(str(m)[:10], "%Y-%m-%d")
    months = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"]
    label = months[dt.month - 1]
    if dt.year != 2025 or dt.month <= 3:
        return f"{label} '{str(dt.year)[2:]}"
    return label


def day_label(d):
    dt = datetime.strptime(str(d)[:10], "%Y-%m-%d")
    months = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"]
    return f"{months[dt.month-1]} {dt.day}"


def build_daily_series(counts_by_day, days, end_date=None):
    """Build a zero-filled UTC day series ending on end_date."""
    final_day = end_date or datetime.now(timezone.utc).date()
    start_day = final_day - timedelta(days=days - 1)
    day_keys = []
    labels = []
    values = []
    for offset in range(days):
        current_day = start_day + timedelta(days=offset)
        day_key = current_day.isoformat()
        day_keys.append(day_key)
        labels.append(day_label(day_key))
        values.append(int(counts_by_day.get(day_key, 0)))
    return day_keys, labels, values


# ── Main ──

def main():
    print("Fetching data from PostHog...")

    print("  DAU...")
    dau = fetch_dau()
    print("  WAU...")
    wau = fetch_wau()
    print("  MAU...")
    mau = fetch_mau()
    print("  Activation rate...")
    activation = fetch_activation()
    print("  Stripe revenue (from CSV)...")
    stripe = fetch_stripe_revenue()
    print("  MRR (from CSV)...")
    mrr_data = fetch_mrr()
    # Total users is static (query over-counts anonymous/bot traffic)
    # total_users = fetch_total_users()  # disabled — over-counts
    print("  Leaderboard (30d)...")
    lb_30d = fetch_leaderboard_30d()
    print("  Leaderboard (all time)...")
    lb_alltime = fetch_leaderboard_alltime()
    print("  CLI2 DAU...")
    cli2_dau = fetch_cli2_dau()
    print("  CLI2 users...")
    cli2_users = fetch_cli2_users()
    print("  CLI2 new users this week...")
    cli2_new_users = fetch_cli2_new_users_this_week()
    print("  CLI2 stats...")
    cli2_stats = fetch_cli2_stats()
    print("  Sign-ups (daily counts)...")
    signups_daily_counts = fetch_signups_daily()
    print("  Sign-ups (list)...")
    signups_list = fetch_signups_list()
    print("  Inference costs for CLI2 users...")
    cli2_all_emails = [u["email"] for u in cli2_users] + [u["email"] for u in cli2_new_users]
    inference_costs = fetch_inference_costs_for_emails(cli2_all_emails)
    print("  North Star: WAP...")
    wap_data = fetch_wap()
    print("  North Star: 7-day activation...")
    activation_7d = fetch_activation_7d()
    print("  North Star: NRR (from Stripe CSV)...")
    nrr_data = compute_nrr()

    today_utc = datetime.now(timezone.utc).date()

    # Process DAU
    dau_days = sorted(dau.keys())
    dau_labels = [day_label(d) for d in dau_days]
    dau_values = [dau[d] for d in dau_days]
    dau_avg = round(sum(dau_values) / max(len(dau_values), 1))

    # Process WAU
    wau_weeks = sorted(wau.keys())
    wau_labels = []
    for w in wau_weeks:
        dt = datetime.strptime(str(w)[:10], "%Y-%m-%d")
        wau_labels.append(f"{dt.strftime('%b %d')}")
    wau_values = [wau[w] for w in wau_weeks]
    # Use last complete week for the KPI (current week is partial and misleading)
    today_weekday = datetime.now(timezone.utc).weekday()  # 0=Mon
    if today_weekday < 6 and len(wau_values) >= 2:
        # Current week is incomplete — use the previous complete week
        wau_current = wau_values[-2]
        wau_week_start = datetime.strptime(str(wau_weeks[-2])[:10], "%Y-%m-%d")
    else:
        wau_current = wau_values[-1] if wau_values else 0
        wau_week_start = datetime.strptime(str(wau_weeks[-1])[:10], "%Y-%m-%d") if wau_weeks else None
    wau_week_end = wau_week_start + timedelta(days=6) if wau_week_start else None
    wau_sub = f"{wau_week_start.strftime('%b %-d')}–{wau_week_end.strftime('%-d')}" if wau_week_start else ""

    # Process MAU
    mau_months = sorted(mau.keys())
    mau_labels = [month_label(m) for m in mau_months]
    mau_values = [mau[m] for m in mau_months]
    mau_current = mau_values[-1] if mau_values else 0
    mau_peak = max(mau_values) if mau_values else 0
    mau_peak_month = mau_labels[mau_values.index(mau_peak)] if mau_values else "N/A"
    # Current month is always partial — use full month name for subtitle
    if mau_months:
        mau_dt = datetime.strptime(str(mau_months[-1])[:10], "%Y-%m-%d")
        mau_sub = f"{mau_dt.strftime('%B %Y')} (so far)"
    else:
        mau_sub = "N/A"

    # Process Activation — use last complete month for the headline KPI
    # (current month is always partial and misleading).
    act_labels = [month_label(r["cohort_month"]) for r in activation]
    act_rates = [float(r["rate"]) for r in activation]
    act_new = [int(r["total_new"]) for r in activation]
    act_activated = [int(r["activated"]) for r in activation]
    # If we have 2+ months and the last one is the current (partial) month,
    # use the second-to-last as the headline.
    current_month_str = today_utc.replace(day=1).isoformat()
    if len(activation) >= 2 and str(activation[-1]["cohort_month"])[:10] == current_month_str:
        latest_act_rate = act_rates[-2]
        latest_act_label = act_labels[-2]
        prev_act_rate = act_rates[-3] if len(act_rates) >= 3 else 0
        prev_act_label = act_labels[-3] if len(act_labels) >= 3 else "N/A"
    else:
        latest_act_rate = act_rates[-1] if act_rates else 0
        latest_act_label = act_labels[-1] if act_labels else "N/A"
        prev_act_rate = act_rates[-2] if len(act_rates) >= 2 else 0
        prev_act_label = act_labels[-2] if len(act_labels) >= 2 else "N/A"

    # Process Stripe
    rev_labels = [month_label(r["month"]) for r in stripe]
    rev_values = [int(r["revenue"]) for r in stripe]
    pc_values = [int(r["customers"]) for r in stripe]
    current_paying = pc_values[-1] if pc_values else 0

    # Process MRR
    mrr_labels = [month_label(r["month"]) for r in mrr_data]
    mrr_values = [int(r["mrr"]) for r in mrr_data]
    current_mrr = mrr_values[-1] if mrr_values else 0

    # Process CLI2
    cli2_days = sorted(cli2_dau.keys())
    cli2_labels = [day_label(d) for d in cli2_days]
    cli2_values = [cli2_dau[d] for d in cli2_days]
    cli2_peak_dau = max(cli2_values) if cli2_values else 0
    cli2_peak_dau_idx = cli2_values.index(cli2_peak_dau) if cli2_values else -1
    cli2_peak_dau_date = cli2_days[cli2_peak_dau_idx] if cli2_peak_dau_idx >= 0 else None
    cli2_total_users = int(cli2_stats.get("unique_users", 0))
    cli2_sessions = int(cli2_stats.get("sessions", 0))
    cli2_tool_calls = int(cli2_stats.get("tool_calls", 0))
    cli2_runs = int(cli2_stats.get("runs", 0))
    cli2_confirmations = int(cli2_stats.get("confirmations", 0))

    # Process Sign-ups — use the unlimited daily count query for KPI + chart,
    # and the capped list query for table + activation check.
    signup_days, signup_labels, signup_values = build_daily_series(
        signups_daily_counts,
        days=30,
        end_date=today_utc,
    )
    signups_30d = sum(signup_values)
    signups_today = signup_values[-1] if signup_values else 0
    print("  Sign-up activation check...")
    signup_emails = [row["email"] for row in signups_list]
    activated_emails = fetch_signup_activation(signup_emails)

    today = datetime.now(timezone.utc).strftime("%b %d, %Y")

    # Process WAP
    wap_weeks = sorted(wap_data.keys())
    wap_labels = [datetime.strptime(w[:10], "%Y-%m-%d").strftime("%b %d") for w in wap_weeks]
    wap_values = [wap_data[w] for w in wap_weeks]
    # Use last complete week for headline KPI
    today_weekday = datetime.now(timezone.utc).weekday()
    if today_weekday < 6 and len(wap_values) >= 2:
        wap_current = wap_values[-2]
        wap_week_label = wap_labels[-2]
    else:
        wap_current = wap_values[-1] if wap_values else 0
        wap_week_label = wap_labels[-1] if wap_labels else "N/A"

    # Process 7-day activation
    act7_labels = [month_label(r["cohort_month"]) for r in activation_7d]
    act7_rates = [float(r["rate"]) for r in activation_7d]
    act7_new = [int(r["total_new"]) for r in activation_7d]
    act7_activated = [int(r["activated"]) for r in activation_7d]
    # Last complete month for headline
    if len(activation_7d) >= 2 and str(activation_7d[-1]["cohort_month"])[:10] == today_utc.replace(day=1).isoformat():
        act7_headline_rate = act7_rates[-2]
        act7_headline_label = act7_labels[-2]
    else:
        act7_headline_rate = act7_rates[-1] if act7_rates else 0
        act7_headline_label = act7_labels[-1] if act7_labels else "N/A"

    # Process NRR
    nrr_labels = [month_label(r["month"]) for r in nrr_data]
    nrr_values = [float(r["nrr"]) for r in nrr_data]
    nrr_current = nrr_values[-1] if nrr_values else 0
    nrr_label_current = nrr_labels[-1] if nrr_labels else "N/A"

    print(f"\nResults:")
    print(f"  DAU avg: {dau_avg}  |  WAU current: {wau_current}  |  MAU current: {mau_current}")
    print(f"  WAP: {wap_current}  |  7d activation: {act7_headline_rate}%  |  NRR: {nrr_current}%")
    print(f"  CLI2 users: {cli2_total_users}  |  CLI2 peak DAU: {cli2_peak_dau}")
    print(f"  Sign-ups (30d): {signups_30d}  |  Sign-ups today: {signups_today}")
    print(f"  Paying customers (latest month): {current_paying}")
    print(f"  Revenue (latest month): ${rev_values[-1] if rev_values else 0}")
    # ── Read and patch index.html ──
    print(f"\nUpdating {DASHBOARD_PATH}...")
    with open(DASHBOARD_PATH, "r") as f:
        html = f.read()

    # Helper to replace JS array assignments
    def replace_js_var(html, var_name, new_value):
        pattern = rf"(const {var_name} = )\[.*?\]"
        return re.sub(pattern, rf"\1{new_value}", html, count=1)

    # Update date
    html = re.sub(r'(Internal users excluded \\?&middot; )[\w\s,]+(<)', lambda m: 'Internal users excluded &middot; ' + today + '<', html)

    # ── Web App KPI values ──
    # DAU
    html = re.sub(
        r'(<div class="kpi-label">DAU</div>\s*<div class="kpi-value"[^>]*>)\d+',
        rf'\g<1>{dau_avg}', html, count=1)
    # WAU
    html = re.sub(
        r'(<div class="kpi-label">WAU</div>\s*<div class="kpi-value"[^>]*>)\d+',
        rf'\g<1>{wau_current}', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">WAU</div>\s*<div class="kpi-value"[^>]*>\d+</div>\s*<div class="kpi-sub">)[^<]+',
        rf'\g<1>last week ({wau_sub})', html, count=1)
    # MAU
    html = re.sub(
        r'(<div class="kpi-label">MAU</div>\s*<div class="kpi-value"[^>]*>)\d+',
        rf'\g<1>{mau_current}', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">MAU</div>\s*<div class="kpi-value"[^>]*>\d+</div>\s*<div class="kpi-sub">)[^<]+',
        rf'\g<1>{mau_sub}', html, count=1)
    # MAU peak badge
    html = re.sub(
        r'(Peak: )[\d,]+ in [^<]+',
        rf'\g<1>{mau_peak:,} in {mau_peak_month}', html, count=1)

    # ── MRR KPI + plan summary card ──
    html = re.sub(
        r'(<div class="kpi-label">MRR</div>\s*<div class="kpi-value"[^>]*>)\$[\d,]+',
        rf'\g<1>${current_mrr:,}', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">MRR</div>\s*<div class="kpi-value"[^>]*>\$[\d,]+</div>\s*<div class="kpi-sub">)\d+ paying customers',
        rf'\g<1>{current_paying} paying customers', html, count=1)
    # Total MRR plan card
    html = re.sub(
        r'(<div class="plan-name">Total MRR</div>\s*<div class="plan-price"[^>]*>)\$[\d,]+',
        rf'\g<1>${current_mrr:,}', html, count=1)
    html = re.sub(
        r'(<div class="plan-name">Total MRR</div>.*?<div class="plan-customers"><strong>)\d+',
        rf'\g<1>{current_paying}', html, count=1, flags=re.DOTALL)
    html = re.sub(
        r'(<div class="plan-name">Total MRR</div>.*?<div class="plan-mrr">)\d+ sign-ups last 30d',
        rf'\g<1>{signups_30d} sign-ups last 30d', html, count=1, flags=re.DOTALL)

    # ── CLI2 KPI values ──
    html = re.sub(
        r'(<div class="kpi-label">External Users</div>\s*<div class="kpi-value"[^>]*>)\d+',
        rf'\g<1>{cli2_total_users}', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">Peak DAU</div>\s*<div class="kpi-value"[^>]*>)\d+',
        rf'\g<1>{cli2_peak_dau}', html, count=1)
    # Peak DAU date
    if cli2_peak_dau_date:
        peak_dt = datetime.strptime(str(cli2_peak_dau_date)[:10], "%Y-%m-%d")
        peak_date_str = peak_dt.strftime("%b %-d, %Y")
        html = re.sub(
            r'(<div class="kpi-label">Peak DAU</div>\s*<div class="kpi-value"[^>]*>\d+</div>\s*<div class="kpi-sub">)[^<]+',
            rf'\g<1>{peak_date_str}', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">Sessions</div>\s*<div class="kpi-value"[^>]*>)[\d,]+',
        rf'\g<1>{cli2_sessions:,}', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">Tool Calls</div>\s*<div class="kpi-value"[^>]*>)[\d,]+',
        rf'\g<1>{cli2_tool_calls:,}', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">Runs Completed</div>\s*<div class="kpi-value"[^>]*>)[\d,]+',
        rf'\g<1>{cli2_runs:,}', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">Confirmations</div>\s*<div class="kpi-value"[^>]*>)[\d,]+',
        rf'\g<1>{cli2_confirmations:,}', html, count=1)

    # ── Activation KPI ──
    html = re.sub(
        r'(<div class="kpi-label">Activation Rate</div>\s*<div class="kpi-value"[^>]*>)[\d.]+%',
        rf'\g<1>{latest_act_rate}%', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">Activation Rate</div>\s*<div class="kpi-value"[^>]*>[\d.]+%</div>\s*<div class="kpi-sub">)[^<]+',
        rf'\g<1>{latest_act_label} cohort', html, count=1)
    if prev_act_rate:
        direction = "&#9650; Up" if latest_act_rate >= prev_act_rate else "&#9660; Down"
        html = re.sub(
            r'(<div class="kpi-label">Activation Rate</div>.*?<div class="kpi-badge[^"]*">)[^<]+',
            rf'\g<1>{direction} from {prev_act_rate}% in {prev_act_label}',
            html, count=1, flags=re.DOTALL)

    # ── Total Users KPI ──
    # html = re.sub(
        # r'(<div class="kpi-label">Total Users</div>\s*<div class="kpi-value"[^>]*>)[\d,]+',
        # rf'\g<1>{total_users:,}', html, count=1)

    # ── Chart data ──
    # Activation chart
    html = replace_js_var(html, "actLabels", js_str_array(act_labels))
    html = replace_js_var(html, "actRates", js_array(act_rates))
    html = replace_js_var(html, "actNew", js_array(act_new))
    html = replace_js_var(html, "actActivated", js_array(act_activated))

    # Web app charts
    html = replace_js_var(html, "dauLabels", js_str_array(dau_labels))
    html = replace_js_var(html, "dauData", js_array(dau_values))
    html = replace_js_var(html, "wauLabels", js_str_array(wau_labels))
    html = replace_js_var(html, "wauData", js_array(wau_values))
    html = replace_js_var(html, "mauLabels", js_str_array(mau_labels))
    html = replace_js_var(html, "mauData", js_array(mau_values))
    html = replace_js_var(html, "revLabels", js_str_array(rev_labels))
    html = replace_js_var(html, "revData", js_array(rev_values))
    html = replace_js_var(html, "pcLabels", js_str_array(rev_labels))
    html = replace_js_var(html, "pcData", js_array(pc_values))

    # MRR chart (distinct from revenue)
    html = replace_js_var(html, "mrrLabels", js_str_array(mrr_labels))
    html = replace_js_var(html, "mrrData", js_array(mrr_values))

    # CLI2 charts
    html = replace_js_var(html, "cli2Labels", js_str_array(cli2_labels))
    html = replace_js_var(html, "cli2Data", js_array(cli2_values))

    # CLI2 DAU chart subtitle
    if cli2_labels:
        cli2_range = f"{cli2_labels[0]}&ndash;{cli2_labels[-1]}" if len(cli2_labels) > 1 else cli2_labels[0]
        cli2_chart_sub = f"{cli2_range} &middot; {cli2_total_users} unique external users"
        html = re.sub(
            r'(<div id="page-cosbeta".*?<h3>Cos Beta DAU \(External Only\)</h3>\s*<div class="chart-sub">)[^<]+',
            rf'\g<1>{cli2_chart_sub}', html, count=1, flags=re.DOTALL)

    # Web app leaderboard — 30 days
    lb30_js = "[\n" + ",\n".join(
        f'  {{email:"{u["email"]}",inference:{int(u["inference"])},days:{int(u["days"])}}}'
        for u in lb_30d
    ) + "\n]"
    html = re.sub(
        r'const users30d = \[.*?\];',
        f'const users30d = {lb30_js};',
        html, count=1, flags=re.DOTALL)

    # Web app leaderboard — all time
    lball_js = "[\n" + ",\n".join(
        f'  {{email:"{u["email"]}",inference:{int(u["inference"])},days:{int(u["days"])}}}'
        for u in lb_alltime
    ) + "\n]"
    html = re.sub(
        r'const usersAllTime = \[.*?\];',
        f'const usersAllTime = {lball_js};',
        html, count=1, flags=re.DOTALL)

    # CLI2 leaderboard — all time (with inference cost)
    cli2_user_js = "[\n" + ",\n".join(
        f'  {{email:"{u["email"]}",events:{int(u["events"])},cost:{inference_costs.get(u["email"], 0)},since:"{str(u["first_seen"])[:10]}"}}'
        for u in cli2_users
    ) + "\n]"
    html = re.sub(
        r'const cli2Users = \[.*?\];',
        f'const cli2Users = {cli2_user_js};',
        html, count=1, flags=re.DOTALL)

    # CLI2 leaderboard — new this week (with inference cost)
    cli2_new_js = "[\n" + ",\n".join(
        f'  {{email:"{u["email"]}",events:{int(u["events"])},cost:{inference_costs.get(u["email"], 0)},since:"{str(u["first_seen"])[:10]}"}}'
        for u in cli2_new_users
    ) + "\n]"
    html = re.sub(
        r'const cli2NewUsers = \[.*?\];',
        f'const cli2NewUsers = {cli2_new_js};',
        html, count=1, flags=re.DOTALL)

    # ── Sign-ups data ──
    # KPIs
    html = re.sub(
        r'(<div class="kpi-label">Sign-ups \(30d\)</div>\s*<div class="kpi-value"[^>]*>)[\d,]+',
        rf'\g<1>{signups_30d:,}', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">Sign-ups Today</div>\s*<div class="kpi-value"[^>]*>)[\d,]+',
        rf'\g<1>{signups_today}', html, count=1)

    # Sign-ups chart data
    html = replace_js_var(html, "signupsLabels", js_str_array(signup_labels))
    html = replace_js_var(html, "signupsData", js_array(signup_values))

    # Sign-ups list
    def escape_js(s):
        return str(s or "").replace("\\", "\\\\").replace('"', '\\"').replace("\n", "")
    signups_js = "[\n" + ",\n".join(
        f'  {{name:"{escape_js(u.get("name",""))}",email:"{escape_js(u["email"])}",date:"{str(u["signup_date"])[:10]}",activated:{str(u["email"] in activated_emails).lower()}}}'
        for u in signups_list
    ) + "\n]"
    html = re.sub(
        r'const signupsList = \[.*?\];',
        f'const signupsList = {signups_js};',
        html, count=1, flags=re.DOTALL)

    # ── North Star: WAP ──
    html = re.sub(
        r'(<div class="kpi-label">WAP</div>\s*<div class="kpi-value"[^>]*>)\d+',
        rf'\g<1>{wap_current}', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">WAP</div>\s*<div class="kpi-value"[^>]*>\d+</div>\s*<div class="kpi-sub">)[^<]+',
        rf'\g<1>week of {wap_week_label}', html, count=1)
    html = replace_js_var(html, "wapLabels", js_str_array(wap_labels))
    html = replace_js_var(html, "wapData", js_array(wap_values))

    # ── North Star: 7-day activation ──
    html = re.sub(
        r'(<div class="kpi-label">7-Day Activation</div>\s*<div class="kpi-value"[^>]*>)[\d.]+%',
        rf'\g<1>{act7_headline_rate}%', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">7-Day Activation</div>\s*<div class="kpi-value"[^>]*>[\d.]+%</div>\s*<div class="kpi-sub">)[^<]+',
        rf'\g<1>{act7_headline_label} cohort', html, count=1)
    html = replace_js_var(html, "act7Labels", js_str_array(act7_labels))
    html = replace_js_var(html, "act7Rates", js_array(act7_rates))
    html = replace_js_var(html, "act7New", js_array(act7_new))
    html = replace_js_var(html, "act7Activated", js_array(act7_activated))

    # ── North Star: NRR ──
    html = re.sub(
        r'(<div class="kpi-label">NRR</div>\s*<div class="kpi-value"[^>]*>)[\d.]+%',
        rf'\g<1>{nrr_current}%', html, count=1)
    html = re.sub(
        r'(<div class="kpi-label">NRR</div>\s*<div class="kpi-value"[^>]*>[\d.]+%</div>\s*<div class="kpi-sub">)[^<]+',
        rf'\g<1>revenue retention {nrr_label_current}', html, count=1)
    html = replace_js_var(html, "nrrLabels", js_str_array(nrr_labels))
    html = replace_js_var(html, "nrrData", js_array(nrr_values))

    with open(DASHBOARD_PATH, "w") as f:
        f.write(html)

    print(f"Done. Dashboard updated at {DASHBOARD_PATH}")


if __name__ == "__main__":
    main()
