#!/usr/bin/env python3
"""Regression test for golden prose corpus.

Runs the classifier on the golden transcript and asserts quality,
register, and differentiation thresholds are met.

Usage:
    python3 verify_golden.py                    # run from golden-prose dir
    python3 verify_golden.py --threshold 3.8    # lower threshold
    python3 verify_golden.py --verbose          # show per-character scores
"""

import argparse
import json
import os
import subprocess
import sys

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
CLASSIFIER = os.path.join(os.path.dirname(EVAL_DIR), "classify_emotions.py")
TRANSCRIPT = os.path.join(EVAL_DIR, "transcript.json")
EVAL_OUTPUT = os.path.join(EVAL_DIR, "full-eval.json")

DEFAULT_THRESHOLDS = {"quality": 4.0, "register": 3.9, "differentiation": 3.8}

BASELINE = {"quality": 4.20, "register": 4.14, "differentiation": 3.99}


def run_classifier():
    result = subprocess.run(
        [sys.executable, CLASSIFIER, EVAL_DIR, "--output", EVAL_OUTPUT],
        capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Classifier failed:\n{result.stderr}", file=sys.stderr)
        sys.exit(1)
    return result


def load_scores():
    with open(EVAL_OUTPUT) as f:
        data = json.load(f)

    per_char = {}
    all_q, all_r, all_d = [], [], []

    for cid, cdata in data["characters"].items():
        cq, cr, cd = [], [], []
        for did, ddata in cdata["drives"].items():
            if ddata["quality"]["n"] > 0:
                cq.append(ddata["quality"]["mean"])
                cr.append(ddata["register"]["mean"])
                cd.append(ddata["differentiation"]["mean"])

        if cq:
            per_char[cid] = {
                "quality": sum(cq) / len(cq),
                "register": sum(cr) / len(cr),
                "differentiation": sum(cd) / len(cd),
            }
            all_q.extend(cq)
            all_r.extend(cr)
            all_d.extend(cd)

    overall = {
        "quality": sum(all_q) / len(all_q),
        "register": sum(all_r) / len(all_r),
        "differentiation": sum(all_d) / len(all_d),
    }
    return overall, per_char


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--threshold", type=float, default=None,
                        help="Override all thresholds with a single value")
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--skip-classify", action="store_true",
                        help="Use existing full-eval.json instead of re-running")
    args = parser.parse_args()

    thresholds = dict(DEFAULT_THRESHOLDS)
    if args.threshold:
        thresholds = {k: args.threshold for k in thresholds}

    if not args.skip_classify:
        print("Running classifier...", file=sys.stderr)
        run_classifier()

    overall, per_char = load_scores()

    print(f"\nGolden corpus scores:")
    print(f"  Quality:         {overall['quality']:.2f}  (threshold: {thresholds['quality']:.1f}, baseline: {BASELINE['quality']:.2f})")
    print(f"  Register:        {overall['register']:.2f}  (threshold: {thresholds['register']:.1f}, baseline: {BASELINE['register']:.2f})")
    print(f"  Differentiation: {overall['differentiation']:.2f}  (threshold: {thresholds['differentiation']:.1f}, baseline: {BASELINE['differentiation']:.2f})")

    if args.verbose:
        print("\nPer-character:")
        for cid in sorted(per_char):
            s = per_char[cid]
            print(f"  {cid:20} Q={s['quality']:.2f}  R={s['register']:.2f}  D={s['differentiation']:.2f}")

    failures = []
    for dim, threshold in thresholds.items():
        if overall[dim] < threshold:
            failures.append(f"{dim}: {overall[dim]:.2f} < {threshold:.1f}")

    if failures:
        print(f"\nFAILED: {', '.join(failures)}")
        sys.exit(1)
    else:
        print("\nPASSED: all thresholds met")


if __name__ == "__main__":
    main()
