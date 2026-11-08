#!/usr/bin/env python3

"""
============================================================
LEDGERBASE — BENCHMARK DATASET GENERATOR
============================================================

Creates and extends isolated accounting benchmark datasets
through LedgerBase's authenticated REST APIs.

FEATURES:
- Dry-run by default.
- Explicit --execute required for writes.
- Creates a new benchmark business when no ID is supplied.
- Extends an existing benchmark with --business-id.
- Reuses local progress records and account IDs.
- Reconciles transaction headers and ledger entries.
- Validates deterministic transaction amounts and dates.
- Rejects duplicate, unexpected, or incomplete records.
- Stops on uncertain HTTP failures without retrying writes.
- Supports bounded batches to accommodate JWT expiration.
- Verifies Trial Balance totals after each completed batch.

LIMITATIONS:
- Requires exclusive use of the benchmark business.
- No server-enforced idempotency key is available.
- Never automatically retries an uncertain POST.
- Local progress files must be preserved.
- Initial capacity is capped at 1,000 transactions.
"""

import argparse
import json
import os
import random
import sys
import uuid

from collections import Counter
from datetime import date, datetime, timezone
from decimal import Decimal
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


# ============================================================
# CONFIGURATION
# ============================================================

BASE_URL = "http://127.0.0.1:3001"

FISCAL_YEAR = 2026
AS_OF_DATE = "2026-10-31"

ACCOUNT_PAIRS = 50
ACCOUNT_COUNT = ACCOUNT_PAIRS * 2

RANDOM_SEED = 5200
REQUEST_TIMEOUT = 20

MAX_TRANSACTIONS = 1000
RESULTS_DIR = Path("benchmarks/results")


# ============================================================
# COMMAND-LINE ARGUMENTS
# ============================================================

def parse_args():
    parser = argparse.ArgumentParser(
        description="Generate LedgerBase benchmark data."
    )

    parser.add_argument(
        "--business-id",
        type=int,
        help="Existing benchmark business to extend.",
    )

    parser.add_argument(
        "--transactions",
        type=int,
        default=5,
        help="Target total number of posted transactions.",
    )

    parser.add_argument(
        "--max-new",
        type=int,
        default=100,
        help="Maximum new transactions per execution.",
    )

    parser.add_argument(
        "--execute",
        action="store_true",
        help="Allow database writes.",
    )

    parser.add_argument(
        "--base-url",
        default=BASE_URL,
        help="Local LedgerBase application URL.",
    )

    return parser.parse_args()


# ============================================================
# HTTP CLIENT
# ============================================================

class LedgerBaseClient:

    def __init__(self, base_url, token):
        self.base_url = base_url.rstrip("/")
        self.token = token

    def request(self, method, path, payload=None):
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/json",
        }

        data = None

        if payload is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(payload).encode("utf-8")

        request = Request(
            self.base_url + path,
            data=data,
            headers=headers,
            method=method,
        )

        try:
            with urlopen(
                request,
                timeout=REQUEST_TIMEOUT,
            ) as response:
                body = response.read()

                return (
                    json.loads(body.decode("utf-8"))
                    if body else None
                )

        except HTTPError as error:
            detail = error.read().decode(
                "utf-8", errors="replace"
            )[:300]

            raise RuntimeError(
                f"{method} {path}: HTTP {error.code}: "
                f"{detail}"
            ) from None

        except (URLError, TimeoutError) as error:
            raise RuntimeError(
                f"{method} {path}: {error}"
            ) from None


# ============================================================
# LOCAL PROGRESS MANAGEMENT
# ============================================================

def save_state(path, state):
    """
    Persist non-secret progress information atomically.

    The API, not this file, remains the source of truth.
    """

    temporary = path.with_suffix(".tmp")

    with temporary.open("w", encoding="utf-8") as file:
        json.dump(state, file, indent=2)
        file.write("\n")

    os.replace(temporary, path)


def find_existing_state(business_id):
    """
    Locate the unique local generator record for a business.

    Fail if no record or multiple records are found.
    """

    matches = []

    for path in RESULTS_DIR.glob("dataset-*.json"):
        with path.open(encoding="utf-8") as file:
            state = json.load(file)

        if state.get("business_id") == business_id:
            matches.append((path, state))

    if len(matches) != 1:
        raise RuntimeError(
            f"Expected one progress record for business "
            f"{business_id}; found {len(matches)}."
        )

    return matches[0]


# ============================================================
# DETERMINISTIC TRANSACTIONS
# ============================================================

def expected_transactions(count, asset_ids, revenue_ids):
    """
    Generate the exact same sequence for every run.

    This preserves transactions 1–100 when extending
    an existing business to 1,000 transactions.
    """

    rng = random.Random(RANDOM_SEED)

    for index in range(count):
        account_index = index % ACCOUNT_PAIRS

        amount_cents = rng.randint(1000, 100000)

        amount = Decimal(amount_cents) / Decimal("100")
        amount_text = f"{amount:.2f}"

        transaction_number = index + 1

        yield {
            "number": transaction_number,
            "amount": amount,
            "description": (
                f"Benchmark transaction "
                f"{transaction_number:06d}"
            ),
            "transactionDate": date(
                2026,
                10,
                1 + index % 31,
            ).isoformat(),
            "entries": [
                {
                    "accountId": asset_ids[account_index],
                    "debit": amount_text,
                    "credit": "0.00",
                },
                {
                    "accountId": revenue_ids[account_index],
                    "debit": "0.00",
                    "credit": amount_text,
                },
            ],
        }


def request_body(expected):
    """
    Exclude internal verification fields from API input.
    """

    return {
        "description": expected["description"],
        "transactionDate": expected["transactionDate"],
        "entries": expected["entries"],
    }


# ============================================================
# DATASET CREATION
# ============================================================

def create_new_dataset(client):
    """
    Create a separate benchmark business and its accounts.

    Each write is performed only once. If a response is
    uncertain, stop and investigate before proceeding.
    """

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    run_id = uuid.uuid4().hex[:12]
    state_path = RESULTS_DIR / f"dataset-{run_id}.json"

    state = {
        "run_id": run_id,
        "created_at": datetime.now(
            timezone.utc
        ).isoformat(),
        "business_name": f"LedgerBase Benchmark {run_id}",
        "business_id": None,
        "asset_ids": [],
        "revenue_ids": [],
        "posted_transactions": 0,
        "status": "started",
    }

    with state_path.open("x", encoding="utf-8") as file:
        os.chmod(state_path, 0o600)
        json.dump(state, file, indent=2)

    try:
        business = client.request(
            "POST",
            "/api/v1/businesses",
            {
                "name": state["business_name"],
                "currency": "USD",
                "fiscalYearStartMonth": 1,
            },
        )

        business_id = business["id"]
        state["business_id"] = business_id
        save_state(state_path, state)

        print(f"Created benchmark business: {business_id}")

        client.request(
            "POST",
            f"/api/v1/businesses/{business_id}/fiscal-years",
            {"fiscalYearEndYear": FISCAL_YEAR},
        )

        print("Created FY2026 and monthly periods.")

        for index in range(ACCOUNT_PAIRS):
            asset = client.request(
                "POST",
                f"/api/v1/businesses/{business_id}/accounts",
                {
                    "code": str(10000 + index),
                    "name": f"Benchmark Asset {index + 1:03d}",
                    "accountType": "ASSET",
                    "postable": True,
                },
            )

            state["asset_ids"].append(asset["id"])
            save_state(state_path, state)

            revenue = client.request(
                "POST",
                f"/api/v1/businesses/{business_id}/accounts",
                {
                    "code": str(40000 + index),
                    "name": f"Benchmark Revenue {index + 1:03d}",
                    "accountType": "REVENUE",
                    "postable": True,
                },
            )

            state["revenue_ids"].append(revenue["id"])
            save_state(state_path, state)

        state["status"] = "ready"
        save_state(state_path, state)

        print(f"Created {ACCOUNT_COUNT} accounts.")

        return state_path, state

    except Exception:
        state["status"] = "needs_manual_review"
        save_state(state_path, state)
        raise


# ============================================================
# EXISTING DATA RECONCILIATION
# ============================================================

def verify_entry_details(actual_entries, expected_entries):
    """
    Compare entries independent of their JSON ordering.

    Decimal avoids floating-point accounting comparisons.
    """

    def normalized(entries):
        return Counter(
            (
                int(entry["accountId"]),
                Decimal(str(entry["debit"])),
                Decimal(str(entry["credit"])),
            )
            for entry in entries
        )

    if normalized(actual_entries) != normalized(expected_entries):
        raise RuntimeError(
            "Transaction ledger entries differ from "
            "the deterministic benchmark dataset."
        )


def reconcile_transactions(client, business_id, state):
    """
    Verify all existing transactions before allowing writes.

    We reject foreign transactions, duplicate descriptions,
    unposted drafts, gaps, and incorrect journal entries.
    """

    if (
        len(state["asset_ids"]) != ACCOUNT_PAIRS
        or len(state["revenue_ids"]) != ACCOUNT_PAIRS
    ):
        raise RuntimeError(
            "Progress record has incomplete account IDs."
        )

    businesses = client.request(
        "GET", "/api/v1/businesses"
    )

    matching_businesses = [
        business
        for business in businesses
        if business.get("id") == business_id
    ]

    if len(matching_businesses) != 1:
        raise RuntimeError(
            "Benchmark business is not accessible."
        )

    business = matching_businesses[0]

    if (
        business.get("name") != state["business_name"]
        or not business["name"].startswith(
            "LedgerBase Benchmark "
        )
    ):
        raise RuntimeError(
            "Business identity does not match "
            "the saved benchmark record."
        )

    base_path = (
        f"/api/v1/businesses/{business_id}/transactions"
    )

    existing = client.request("GET", base_path)

    if not isinstance(existing, list):
        raise RuntimeError(
            "Transaction listing did not return an array."
        )

    # Build a map by deterministic description.
    by_description = {}

    for transaction in existing:
        description = transaction.get("description")

        if description in by_description:
            raise RuntimeError(
                f"Duplicate transaction: {description}"
            )

        by_description[description] = transaction

    expected = list(
        expected_transactions(
            len(existing),
            state["asset_ids"],
            state["revenue_ids"],
        )
    )

    expected_descriptions = {
        record["description"] for record in expected
    }

    if set(by_description) != expected_descriptions:
        raise RuntimeError(
            "Existing transaction descriptions are "
            "unexpected or contain sequence gaps."
        )

    # Verify both headers and detailed ledger entries.
    for record in expected:
        actual = by_description[record["description"]]

        if (
            actual.get("status") != "POSTED"
            or actual.get("transactionDate")
            != record["transactionDate"]
            or actual.get("businessId") != business_id
        ):
            raise RuntimeError(
                f"Invalid transaction header: "
                f"{record['description']}"
            )

        detail = client.request(
            "GET",
            f"{base_path}/{actual['id']}",
        )

        detail_header = detail["transaction"]

        if (
            detail_header.get("id") != actual["id"]
            or detail_header.get("status") != "POSTED"
            or detail_header.get("description")
            != record["description"]
            or detail_header.get("transactionDate")
            != record["transactionDate"]
            or detail_header.get("businessId") != business_id
        ):
            raise RuntimeError(
                f"Transaction detail mismatch: "
                f"{record['description']}"
            )

        verify_entry_details(
            detail["entries"],
            record["entries"],
        )

    print(
        f"Reconciled {len(existing)} existing "
        "POSTED transactions."
    )

    return len(existing)


# ============================================================
# APPEND MISSING TRANSACTIONS
# ============================================================

def append_transactions(
    client,
    business_id,
    state,
    state_path,
    current_count,
    target_count,
):
    """
    Append a bounded set of missing transactions.

    A failed request stops execution immediately.
    We do not blindly repeat a potentially successful POST.
    """

    base_path = (
        f"/api/v1/businesses/{business_id}/transactions"
    )

    all_expected = list(
        expected_transactions(
            target_count,
            state["asset_ids"],
            state["revenue_ids"],
        )
    )

    for record in all_expected[current_count:target_count]:
        draft = client.request(
            "POST",
            base_path,
            request_body(record),
        )

        transaction_id = draft["id"]

        state["last_created_transaction_id"] = (
            transaction_id
        )
        state["status"] = "posting"
        save_state(state_path, state)

        posted = client.request(
            "POST",
            f"{base_path}/{transaction_id}/post",
        )

        if posted.get("status") != "POSTED":
            raise RuntimeError(
                f"Transaction {transaction_id} did not post."
            )

        state["posted_transactions"] = record["number"]
        save_state(state_path, state)

        if record["number"] % 25 == 0:
            print(
                f"Posted {record['number']}/"
                f"{target_count} transactions."
            )


# ============================================================
# FINAL ACCOUNTING VALIDATION
# ============================================================

def verify_trial_balance(client, business_id, count, state):
    """
    Verify balanced totals against the deterministic
    expected transaction amounts.

    This also detects stale replica-backed reports.
    """

    report = client.request(
        "GET",
        f"/api/v1/businesses/{business_id}"
        f"/reports/trial-balance?asOfDate={AS_OF_DATE}",
    )

    expected_total = sum(
        (
            record["amount"]
            for record in expected_transactions(
                count,
                state["asset_ids"],
                state["revenue_ids"],
            )
        ),
        Decimal("0.00"),
    )

    debits = Decimal(str(report["totalDebits"]))
    credits = Decimal(str(report["totalCredits"]))

    if (
        report.get("businessId") != business_id
        or report.get("asOfDate") != AS_OF_DATE
        or report.get("balanced") is not True
        or debits != expected_total
        or credits != expected_total
        or len(report["rows"]) != ACCOUNT_COUNT
    ):
        raise RuntimeError(
            "Trial Balance does not match expected "
            "benchmark totals. Check replica lag, "
            "cache freshness, and ledger consistency."
        )

    print("Trial Balance verification passed.")
    print(f"Total debits:  {debits:.2f}")
    print(f"Total credits: {credits:.2f}")
    print(f"Report rows:   {len(report['rows'])}")


# ============================================================
# MAIN
# ============================================================

def main():
    args = parse_args()

    if not 1 <= args.transactions <= MAX_TRANSACTIONS:
        raise ValueError(
            f"--transactions must be 1–{MAX_TRANSACTIONS}."
        )

    if args.max_new < 1:
        raise ValueError("--max-new must be positive.")

    if args.base_url.rstrip("/") not in (
        "http://127.0.0.1:3001",
        "http://localhost:3001",
    ):
        raise ValueError(
            "Only the local LedgerBase deployment is allowed."
        )

    # New business creation remains dry-run without a token.
    if not args.execute and args.business_id is None:
        print("DRY RUN — no database writes")
        print(f"Target URL: {args.base_url}")
        print(f"Accounts: {ACCOUNT_COUNT}")
        print(f"Target transactions: {args.transactions}")
        print(f"Random seed: {RANDOM_SEED}")
        print("Use --execute to create the dataset.")
        return

    token = os.environ.get("ACCESS_TOKEN", "").strip()

    if not token:
        raise RuntimeError(
            "ACCESS_TOKEN environment variable is required."
        )

    client = LedgerBaseClient(args.base_url, token)

    if args.business_id is None:
        # Creating a new business is always a write operation.
        state_path, state = create_new_dataset(client)

    else:
        state_path, state = find_existing_state(
            args.business_id
        )

    business_id = state["business_id"]

    if business_id is None:
        raise RuntimeError(
            "Progress record has no business ID."
        )

    # Reconcile the live API records before any extension.
    current_count = reconcile_transactions(
        client, business_id, state
    )

    if current_count > args.transactions:
        raise RuntimeError(
            "Target is smaller than the existing dataset."
        )

    remaining = args.transactions - current_count

    to_create = min(remaining, args.max_new)

    print("")
    print(f"Business ID: {business_id}")
    print(f"Existing transactions: {current_count}")
    print(f"Target transactions: {args.transactions}")
    print(f"New transactions this run: {to_create}")

    if not args.execute:
        print("DRY RUN — no database writes")
        return

    try:
        if to_create > 0:
            append_transactions(
                client,
                business_id,
                state,
                state_path,
                current_count,
                current_count + to_create,
            )

        final_count = current_count + to_create

        verify_trial_balance(
            client, business_id, final_count, state
        )

        state["posted_transactions"] = final_count
        state["status"] = (
            "completed"
            if final_count == args.transactions
            else "ready_for_extension"
        )

        save_state(state_path, state)

        print("")
        print(f"Verified transactions: {final_count}")
        print(f"Progress record: {state_path}")

        if final_count < args.transactions:
            print(
                "Dataset can be extended with "
                "another authenticated execution."
            )

    except Exception:
        state["status"] = "needs_manual_review"
        save_state(state_path, state)

        print(
            "Stopped. Inspect API records and "
            "progress state before retrying.",
            file=sys.stderr,
        )

        raise


if __name__ == "__main__":
    main()
