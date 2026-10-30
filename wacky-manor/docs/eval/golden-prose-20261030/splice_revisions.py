#!/usr/bin/env python3
"""Splice revised events from agent output files into transcript.json."""

import json
import os
import sys

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
TRANSCRIPT = os.path.join(EVAL_DIR, "transcript.json")

REVISION_FILES = [
    "/tmp/dd-revised-events.json",
    "/tmp/hc-revised-events.json",
    "/tmp/pp-revised-events.json",
]


def main():
    with open(TRANSCRIPT) as f:
        data = json.load(f)

    events = data["events"]
    total_changed = 0

    for rev_path in REVISION_FILES:
        if not os.path.exists(rev_path):
            print(f"SKIP: {rev_path} not found", file=sys.stderr)
            continue

        with open(rev_path) as f:
            rev_data = json.load(f)

        for rev_event in rev_data["events"]:
            idx = rev_event["index"]
            if idx < 0 or idx >= len(events):
                print(f"SKIP: index {idx} out of range", file=sys.stderr)
                continue

            old = events[idx]
            desc_changed = old["desc"] != rev_event["desc"]
            think_changed = old["thinking"] != rev_event["thinking"]

            if desc_changed or think_changed:
                events[idx]["desc"] = rev_event["desc"]
                events[idx]["thinking"] = rev_event["thinking"]
                changes = []
                if desc_changed:
                    changes.append("desc")
                if think_changed:
                    changes.append("thinking")
                print(f"  event {idx} ({old['character']}): {', '.join(changes)} updated")
                total_changed += 1
            else:
                print(f"  event {idx} ({old['character']}): no change")

    with open(TRANSCRIPT, "w") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    print(f"\n{total_changed} events updated in {TRANSCRIPT}")


if __name__ == "__main__":
    main()
