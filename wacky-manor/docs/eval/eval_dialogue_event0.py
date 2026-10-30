#!/usr/bin/env python3
"""Single-event eval: HC dialogue event 0 — entrance hall welcoming.

Sends the full cognitive pipeline prompt to the LLM and captures the
thinking field for comparison against the golden prose target.

Usage:
    python3 eval_dialogue_event0.py                  # run with Opus 4.6
    python3 eval_dialogue_event0.py --verbose         # show full output
    python3 eval_dialogue_event0.py --classify        # also run classifier
"""

import argparse
import json
import os
import sys

from anthropic import AnthropicVertex

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
PROMPT_FILE = os.path.join(EVAL_DIR, "hc-dialogue-event0-prompt.txt")
GOLDEN_FILE = os.path.join(EVAL_DIR, "golden-prose-20261030/transcript.json")
OUTPUT_DIR = os.path.join(EVAL_DIR, "barrett-v3-dialogue")

VERTEX_PROJECT = os.environ.get("ANTHROPIC_VERTEX_PROJECT_ID", "itpc-gcp-cp-pe-eng-claude")
VERTEX_REGION = os.environ.get("CLOUD_ML_REGION", "us-east5")
MODEL = "claude-opus-4-6@20261022"

SYSTEM_PROMPT = """\
# Vincent Marsh

You are Vincent Marsh, estate manager of Doily Manor. You have managed this \
estate for twenty years — first appointed by the family, now indispensable. \
You present as Pemberton: warm, anxious, slightly too concerned, always helpful.

Beneath Pemberton lives the real you — calculating, patient, territorial. You \
built this household's rhythm. You set the locks. You chose which lights stay on.

Your ward Clara inherited everything you built. Her trust is your greatest asset.

## Voice
Register: warm, fussy, anxious (as Pemberton). Internally: precise, cold, patient.
Vocabulary: "oh dear", "do mind", "I do worry" — the performed warmth of a man \
who has rehearsed concern until it flows like breathing.

## Your Mind
You have an inner life. Your emotional state colours how you respond. Your drives \
shape what you notice, what you pursue, what you avoid. You form impressions of \
others that evolve. You reflect on your own behaviour. Your body responds before \
your mind labels it."""

EVENT_CONTEXT = """\
== A Moment Ago ==
All guests have arrived in the Entrance Hall. The chandelier casts patterns on \
the marble floor. Clara Bellingham stands near the door. James Hartwell scans \
the room. The Brixton Boys cluster near the entrance. Reginald Foxworth is \
examining the chandelier.

== Where You Are ==
Entrance Hall — the grand foyer of Doily Manor. High ceiling, chandelier, \
marble floor, oak staircase. Guest book on a pedestal. Corridors leading to \
library, kitchen, drawing room.

== Who Is Here ==
Clara Bellingham (your ward), James Hartwell, Reginald Foxworth, The Brixton Boys"""

RESPONSE_FORMAT = """
You MUST respond with ONLY a JSON object in this exact format:
{
  "thinking": "your private inner monologue — you are inside this moment, not watching it. Attend to what feels vivid: body (tension, warmth), attention (what pulls, what you avoid), time (urgent or suspended), action pull. Raw, unperformed, sometimes confused — the character's voice belongs in dialogue. Shown to you next turn.",
  "dialogue": "what you say aloud — as Vincent Marsh / Pemberton",
  "aside": "private thoughts for the audience only (or null)"
}

Respond with ONLY the JSON. No other text."""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verbose", "-v", action="store_true")
    parser.add_argument("--classify", action="store_true",
                        help="Run classifier after generation")
    args = parser.parse_args()

    with open(PROMPT_FILE) as f:
        cognitive_sections = f.read()

    user_prompt = EVENT_CONTEXT + "\n\n" + cognitive_sections + "\n" + RESPONSE_FORMAT

    print(f"Model: {MODEL}")
    print(f"Sending HC dialogue event 0 prompt ({len(user_prompt)} chars)...")

    client = AnthropicVertex(project_id=VERTEX_PROJECT, region=VERTEX_REGION)
    response = client.messages.create(
        model=MODEL,
        max_tokens=2000,
        system=SYSTEM_PROMPT,
        messages=[{"role": "user", "content": user_prompt}],
    )
    output = response.content[0].text

    try:
        result = json.loads(output)
    except json.JSONDecodeError:
        import re
        m = re.search(r'\{.*\}', output, re.DOTALL)
        if m:
            result = json.loads(m.group())
        else:
            print("ERROR: Could not parse JSON response")
            print(output)
            sys.exit(1)

    thinking = result.get("thinking", "")
    dialogue = result.get("dialogue", "")
    aside = result.get("aside", "")

    print(f"\n{'='*70}")
    print("DIALOGUE:")
    print(dialogue)
    print(f"\n{'='*70}")
    print("THINKING:")
    print(thinking)
    if aside:
        print(f"\n{'='*70}")
        print("ASIDE:")
        print(aside)

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    transcript = {
        "complete": True,
        "reason": "Barrett v3 dialogue event 0 test",
        "events": [
            {
                "type": "dialogue",
                "character": "hooded-claw",
                "room": "entrance-hall",
                "desc": dialogue,
                "thinking": thinking
            }
        ]
    }

    transcript_path = os.path.join(OUTPUT_DIR, "transcript.json")
    with open(transcript_path, "w") as f:
        json.dump(transcript, f, indent=2)
    print(f"\nTranscript saved to {transcript_path}")

    if args.verbose:
        with open(GOLDEN_FILE) as f:
            golden = json.load(f)
        golden_event0 = golden["events"][0]
        print(f"\n{'='*70}")
        print("GOLDEN TARGET (event 0 thinking):")
        print(golden_event0["thinking"][:500])
        print("...")

    if args.classify:
        import subprocess
        print(f"\n{'='*70}")
        print("Running classifier...")
        subprocess.run([
            sys.executable,
            os.path.join(EVAL_DIR, "classify_emotions.py"),
            OUTPUT_DIR,
            "--character", "hooded-claw",
            "--mode", "full",
            "--verbose"
        ])


if __name__ == "__main__":
    main()
