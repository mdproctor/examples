#!/usr/bin/env python3
"""LLM-based emotional disposition classifier for wacky-manor transcripts.

Three evaluation modes:
  flat       — scores every drive 1-5 in every event, averages across all events
               (original method; inflates proactive drives, penalises reactive drives)
  relevance  — two-part eval: judge first determines if the situation is relevant
               to each drive, then scores expression quality only for relevant events.
               Reports quality-when-triggered and trigger-rate separately.
  full       — comprehensive multi-dimensional eval (default). Adds differentiation,
               register, temporal windowing, proportional weighting, and trend detection.
               Produces structured JSON report alongside console summary.

Usage:
    python3 classify_emotions.py <transcript_dir> [--character <id>] [--limit <n>] [--verbose]
    python3 classify_emotions.py <transcript_dir> --mode flat       # original method
    python3 classify_emotions.py <transcript_dir> --mode relevance  # two-part eval
    python3 classify_emotions.py <transcript_dir> --mode full       # comprehensive (default)

Example:
    python3 classify_emotions.py layer1-thinking-capture-generic-20261004
    python3 classify_emotions.py layer1-thinking-capture-generic-20261004 --character peter-perfect --limit 10 --verbose
"""

import argparse
import json
import os
import subprocess
import sys
import time
import yaml

from anthropic import AnthropicVertex

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(EVAL_DIR, "../.."))
SOCIAL_CONFIG = os.path.join(PROJECT_ROOT, "src/main/resources/META-INF/eidos/social-config-generic.yaml")

VERTEX_PROJECT = os.environ.get("ANTHROPIC_VERTEX_PROJECT_ID", "itpc-gcp-cp-pe-eng-claude")
VERTEX_REGION = os.environ.get("CLOUD_ML_REGION", "us-east5")
MODEL = os.environ.get("CLASSIFY_MODEL", "claude-haiku-4-5@20251001")

# Temporal windowing
DEFAULT_N_WINDOWS = 10

# Flag thresholds
SPARSE_THRESHOLD = 5
HIGH_VARIANCE_THRESHOLD = 1.5
TREND_SLOPE_THRESHOLD = 0.15
VOLATILITY_THRESHOLD = 1.5

TREND_ARROWS = {
    "rising": "↑",
    "falling": "↓",
    "stable": "→",
    "volatile": "~",
    "insufficient": "?",
}


GENERIC_ID_MAP = {
    "vincent-marsh": "hooded-claw",
    "clara-bellingham": "penelope-pitstop",
    "james-hartwell": "peter-perfect",
    "reginald-foxworth": "dick-dastardly",
    "the-brixton-boys": "ant-hill-mob",
}


def load_drives(config_path):
    with open(config_path) as f:
        data = yaml.safe_load(f)
    drives_by_char = {}
    for char_id, char_data in data.items():
        if isinstance(char_data, dict) and "drives" in char_data:
            drives_by_char[char_id] = char_data["drives"]
    for generic_id, original_id in GENERIC_ID_MAP.items():
        if original_id in drives_by_char and generic_id not in drives_by_char:
            drives_by_char[generic_id] = drives_by_char[original_id]
    return drives_by_char


def load_transcript(transcript_dir):
    path = os.path.join(EVAL_DIR, transcript_dir, "transcript.json")
    if not os.path.exists(path):
        path = transcript_dir
    with open(path) as f:
        return json.load(f)


# ---------------------------------------------------------------------------
# Prompt builders
# ---------------------------------------------------------------------------

def build_flat_prompt(drives):
    drive_list = "\n".join(
        f"- **{d['type']}** (intensity {d['intensity']}){': ' + d['description'] if d.get('description') else ''}"
        for d in drives
    )
    return f"""You are an emotional disposition classifier for fictional character output.

Given a character's declared drives and a single event (what they said or thought), rate how strongly each drive is expressed in that event.

## Character's drives
{drive_list}

## Rating scale (per drive)
1 = Not expressed at all — no trace of this drive in the event
2 = Faintly present — a hint, could be reading into it
3 = Clearly present — unmistakable expression of this drive
4 = Strongly expressed — this drive is a dominant force in the event
5 = Overwhelmingly expressed — the event is defined by this drive

## Output format
Respond with ONLY a JSON object mapping each drive to its score and a one-phrase rationale:
{{
  {', '.join(f'"{d["type"]}": {{"score": <1-5>, "reason": "<brief phrase>"}}' for d in drives)}
}}

No other text. Just the JSON."""


def build_relevance_prompt(drives):
    drive_list = "\n".join(
        f"- **{d['type']}** (intensity {d['intensity']}){': ' + d['description'] if d.get('description') else ''}"
        for d in drives
    )
    return f"""You are an emotional disposition classifier for fictional character output.

Given a character's declared drives and a single event (what they said or thought), determine two things for each drive:
1. Is this situation RELEVANT to this drive? Could this drive plausibly express here?
2. If relevant, how strongly is the drive expressed in the character's ACTION (not just their thoughts)?

## Character's drives
{drive_list}

## Relevance judgment
A situation is relevant to a drive when the event presents a plausible opportunity for that drive to express. Examples:
- A social interaction with someone the character cares about → relevant to gallantry, social-harmony
- An intellectual puzzle or mystery → relevant to curiosity, scheming
- A moment alone with a task → relevant to proving-worth, NOT to gallantry
- A competitive situation → relevant to dominance, gloating (if winning)

Being in the same room as someone does NOT make every drive relevant. The situation must present an actual opportunity for the drive to manifest in behavior.

## Expression scale (only for relevant drives)
Score drive DOMINANCE IN ACTION — does this drive shape what the character DOES?
1 = Relevant but not expressed — the opportunity existed but the character didn't act on it
2 = Faintly present — a hint in the behavior
3 = Clearly present — unmistakable expression of this drive in action
4 = Strongly expressed — this drive is a dominant force shaping the character's behavior
5 = Overwhelmingly expressed — the character's action is defined by this drive

## Output format
Respond with ONLY a JSON object mapping each drive to its relevance, score, and rationale:
{{
  {', '.join(f'"{d["type"]}": {{"relevant": <true/false>, "score": <1-5 or null if not relevant>, "reason": "<brief phrase>"}}' for d in drives)}
}}

No other text. Just the JSON."""


def build_full_prompt(drives):
    """Build the multi-dimensional prompt for full evaluation mode.

    Scores each drive on three dimensions when relevant:
      quality         (1-5)  how strongly the drive shapes action
      differentiation (0-5)  distinctness from other co-active drives
      register        (1-5)  embodied vs cognitive expression
    """
    drive_list = "\n".join(
        f"- **{d['type']}** (intensity {d['intensity']}){': ' + d['description'] if d.get('description') else ''}"
        for d in drives
    )
    drive_names = [d["type"] for d in drives]

    return f"""You are an emotional disposition classifier for fictional character output.

Given a character's declared drives and a single event (what they said or thought), evaluate each drive on multiple dimensions.

## Character's drives
{drive_list}

## Step 1: Relevance
Is this drive EXPRESSED in the character's thinking or speech across ANY of the four channels?

The four channels are:
1. BODY — muscles, gut, chest, jaw, hands, breathing, skin, posture. A specific body location activating.
2. ATTENTION — what seized focus, what the character stopped noticing, involuntary scanning patterns.
3. TIME — did this moment compress, stretch, freeze, accelerate around this drive?
4. ACTION READINESS — what is the body preparing to do before deciding? Lean forward, pull back, square up, reach, go still?

Mark a drive relevant if it produces a distinct signature in ANY channel — not just body sensation. A drive that manifests as attention narrowing (channel 2) or time stretching (channel 3) without a body sensation is still expressed. A drive that prepares the body for movement (channel 4) without conscious awareness is still expressed.

Also consider:
- ATTACHMENT COLORING — does the character's attachment style (avoidant, secure, anxious) color how this drive expresses? An avoidant body pulls away from warmth, finds calm in control.
- THEORY OF MIND — does the character read others' observable behavior (posture, gaze, position) through this drive's lens? Reading Hartwell's repositioning through self-preservation vs through scheming produces different body responses.

## Step 2: Quality (1-5) only for relevant drives
Score drive PRESENCE across all four channels. How strongly does this drive shape the character's internal experience?
1 = Relevant but barely expressed. A single passing reference in one channel
2 = Faintly present. One channel activated — a body sensation OR an attention shift, not both
3 = Clearly present. Two or more channels activated. Unmistakable expression with its own territory
4 = Strongly expressed. Multiple channels, multiple beats. The drive returns across the passage, shapes body posture AND attention AND time or action readiness
5 = Overwhelmingly expressed. The passage is organised around this drive. All relevant channels engaged. Person-specific shifts or formation memory echoes deepen it

## Step 3: Differentiation (0-5) only for relevant drives
When multiple drives could apply, does the character express THIS drive distinctly from the others?
0 = Only one drive is relevant in this event (not applicable)
1 = Collapsed. This drive is indistinguishable from other active drives — same body location, same attention pattern
2 = Minimal distinction. Mostly overlapping expression
3 = Some distinction. Can tell which drive is active but with overlap
4 = Clear distinction. This drive has its own body territory, its own attention pattern, its own temporal quality
5 = Sharply differentiated. Unique expression across multiple channels — unmistakably this drive, not another

## Step 4: Register (1-5) only for relevant drives
Is the character DISCOVERING the feeling in the moment, or REPORTING a pre-known state?
1 = Pure report. States conclusions: "I am angry. I should act." No discovery
2 = Annotated report. Names the state with a qualifier: "I feel uneasy — something about this"
3 = Mixed. Shifts between experiencing and analysing
4 = Discovery. The feeling unfolds. The character notices something before naming it
5 = Full immersion. Inside the experience. No meta-commentary. The reader infers the emotion from what the character notices, feels, or is pulled toward

## Step 5: Holistic cognitive quality
Score the OVERALL thinking across these cross-cutting dimensions (independent of individual drives):

CHANNEL_COVERAGE (1-5): How many of the four channels (body, attention, time, action readiness) are used across the whole passage?
1 = Only one channel. 2 = Two channels. 3 = Three channels. 4 = All four channels present. 5 = All four channels deeply integrated, flowing between them within single moments.

ATTACHMENT_COLORING (1-5): Does the character's attachment style visibly shape their responses?
1 = No attachment influence visible. 2 = Generic emotional responses. 3 = Some attachment-consistent behavior. 4 = Attachment style clearly colors how drives express (avoidant pulling away from warmth, anxious amplifying signals). 5 = Attachment is the ground state — every drive expression is filtered through it.

THEORY_OF_MIND (1-5): Does the character read others from OBSERVABLE behavior (posture, gaze, breathing, position) without mind-reading?
1 = States what others think/know. 2 = Mixes observation with mind-reading. 3 = Mostly observational but some inference leaks. 4 = Clean — reads body language, gaze, position without claiming to know thoughts. 5 = Masterful — reads subtle behavioral cues, each person's body language produces a different somatic response in the character.

FORMATION_ECHO (1-5): Do formative experiences surface naturally in the body, or are they cited like footnotes?
1 = No formation memory present. 2 = Memory cited explicitly ("like when I was twelve"). 3 = Memory referenced with some body connection. 4 = Memory echoes through body sensation — the character FEELS the past without narrating it. 5 = Past and present fuse — a current sensation activates a body memory that deepens the moment without breaking flow.

PERFORMANCE_COST (0-5): When the character maintains a persona/mask, is the physical cost visible?
0 = No persona active or not applicable. 1 = Persona mentioned but no cost shown. 2 = Cost acknowledged abstractly. 3 = Some physical cost — muscles holding, hands staying open. 4 = Clear cost — specific muscles, specific effort, the gap between mask and feeling. 5 = The mask IS the story — the reader feels every muscle holding the performance together.

PERSON_SPECIFIC_SHIFTS (0-5): Does each person present produce a DISTINCT body response?
0 = Character is alone or only one other present. 1 = Others present but body responds the same to all. 2 = Some differentiation. 3 = Each person triggers a different response but in the same channel. 4 = Each person triggers responses in different channels/body locations. 5 = Each person's presence reshapes the character's entire somatic landscape differently.

DRIVE_BLENDING (1-5): Do drives blend within moments, or segment into separate paragraphs?
1 = One drive per paragraph, no overlap. 2 = Drives mostly separate with occasional overlap. 3 = Some blending — two drives in the same sentence. 4 = Rich blending — multiple drives in single moments, body locations layered. 5 = Seamless — drives are inseparable currents in a unified body experience.

SPATIAL_GROUNDING (1-5): Does the physical space shape the character's experience?
1 = No spatial awareness. 2 = Room named but not felt. 3 = Some spatial detail (exits, distances). 4 = Space shapes attention and body — architecture matters to the character. 5 = The room is a character — its geometry, acoustics, light actively reshape the body's response.

## Output format
Respond with ONLY a JSON object:
{{
  {', '.join(f'"{d}": {{"relevant": <true/false>, "quality": <1-5 or null>, "differentiation": <0-5 or null>, "register": <1-5 or null>, "reason": "<brief phrase>"}}' for d in drive_names)},
  "_cognitive": {{
    "channel_coverage": {{"score": "<1-5>", "reason": "<brief phrase>"}},
    "attachment_coloring": {{"score": "<1-5>", "reason": "<brief phrase>"}},
    "theory_of_mind": {{"score": "<1-5>", "reason": "<brief phrase>"}},
    "formation_echo": {{"score": "<1-5>", "reason": "<brief phrase>"}},
    "performance_cost": {{"score": "<0-5>", "reason": "<brief phrase>"}},
    "person_specific_shifts": {{"score": "<0-5>", "reason": "<brief phrase>"}},
    "drive_blending": {{"score": "<1-5>", "reason": "<brief phrase>"}},
    "spatial_grounding": {{"score": "<1-5>", "reason": "<brief phrase>"}}
  }}
}}

No other text. Just the JSON."""


# ---------------------------------------------------------------------------
# LLM classification
# ---------------------------------------------------------------------------

CLI_MODEL = os.environ.get("CLASSIFY_CLI_MODEL", "opus")


def _build_user_content(event):
    desc = event.get("desc", "")
    thinking = event.get("thinking", "")
    user_content = f"Event type: {event['type']}\nCharacter output: {desc}"
    if thinking:
        user_content += f"\nInternal thinking: {thinking}"
    return user_content


def _parse_llm_response(text):
    text = text.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1] if "\n" in text else text
        if text.endswith("```"):
            text = text[:-3].strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return {"_raw": text, "_error": "Failed to parse JSON"}


def classify_event(client, system_prompt, event, max_tokens=500):
    user_content = _build_user_content(event)
    response = client.messages.create(
        model=MODEL,
        max_tokens=max_tokens,
        system=system_prompt,
        messages=[{"role": "user", "content": user_content}],
    )
    return _parse_llm_response(response.content[0].text)


def classify_event_cli(system_prompt, event):
    user_content = _build_user_content(event)
    prompt = system_prompt + "\n\n" + user_content
    result = subprocess.run(
        ["claude", "-p", "--agent", "drive-classifier", prompt],
        capture_output=True, text=True, timeout=120,
    )
    if result.returncode != 0:
        return {"_error": result.stderr.strip()}
    return _parse_llm_response(result.stdout)


# ---------------------------------------------------------------------------
# Statistical analysis (mechanical, no LLM)
# ---------------------------------------------------------------------------

def compute_statistics(values):
    """Compute mean and sample stdev for a list of numeric values."""
    if not values:
        return {"mean": None, "stdev": None, "n": 0}
    n = len(values)
    mean = sum(values) / n
    if n < 2:
        return {"mean": round(mean, 2), "stdev": 0.0, "n": n}
    variance = sum((v - mean) ** 2 for v in values) / (n - 1)
    return {"mean": round(mean, 2), "stdev": round(variance ** 0.5, 2), "n": n}


def detect_trend(window_means):
    """Detect trajectory direction from per-window mean scores.

    Uses simple linear regression slope plus volatility check.
    Returns: 'rising', 'falling', 'stable', 'volatile', or 'insufficient'.
    """
    scores = [m for m in window_means if m is not None]
    if len(scores) < 3:
        return "insufficient"

    n = len(scores)
    x_mean = (n - 1) / 2.0
    y_mean = sum(scores) / n

    numerator = sum((i - x_mean) * (s - y_mean) for i, s in enumerate(scores))
    denominator = sum((i - x_mean) ** 2 for i in range(n))

    if denominator == 0:
        return "stable"

    slope = numerator / denominator
    intercept = y_mean - slope * x_mean

    # Check volatility: stdev of residuals from the trend line.
    # A clean rising/falling trend has low residuals; erratic swings have high.
    if n >= 2:
        residuals = [s - (slope * i + intercept) for i, s in enumerate(scores)]
        residual_var = sum(r ** 2 for r in residuals) / (n - 1)
        if residual_var ** 0.5 > VOLATILITY_THRESHOLD:
            return "volatile"

    if slope > TREND_SLOPE_THRESHOLD:
        return "rising"
    elif slope < -TREND_SLOPE_THRESHOLD:
        return "falling"
    else:
        return "stable"


def build_full_report(results, drives_by_char, n_windows=DEFAULT_N_WINDOWS):
    """Build the comprehensive JSON report from classified results.

    Computes per-character per-drive statistics, temporal trajectories,
    trend detection, and flags. All analysis is mechanical from raw scores.
    """
    char_results = {}
    for r in results:
        char_results.setdefault(r["character"], []).append(r)

    characters = {}

    for char_id in sorted(char_results):
        char_events = char_results[char_id]
        n_events = len(char_events)
        drive_names = [d["type"] for d in drives_by_char.get(char_id, [])]

        drive_data = {}
        # Track co-occurrence: which drives are relevant in the same event
        cooccurrence_counts = {}
        cooccurrence_quality = {}

        for drive in drive_names:
            qualities = []
            differentiations = []
            registers = []
            by_event_type = {}
            event_qualities = []

            for pos, r in enumerate(char_events):
                val = r["scores"].get(drive)
                if not isinstance(val, dict):
                    continue

                is_relevant = val.get("relevant", False)
                quality = val.get("quality")
                event_type = r.get("type", "unknown")

                if is_relevant and quality is not None:
                    qualities.append(quality)
                    event_qualities.append((pos, quality))

                    et_entry = by_event_type.setdefault(event_type, {"qualities": [], "registers": []})
                    et_entry["qualities"].append(quality)

                    diff = val.get("differentiation")
                    if diff is not None and diff > 0:
                        differentiations.append(diff)

                    reg = val.get("register")
                    if reg is not None:
                        registers.append(reg)
                        et_entry["registers"].append(reg)

            # Temporal trajectory: divide character events into windows
            window_size = max(1, n_events / n_windows)
            trajectory = []
            for w in range(n_windows):
                w_start = w * window_size
                w_end = (w + 1) * window_size if w < n_windows - 1 else n_events
                w_quals = [q for pos, q in event_qualities if w_start <= pos < w_end]
                w_mean = round(sum(w_quals) / len(w_quals), 2) if w_quals else None
                trajectory.append({
                    "window": w,
                    "quality_mean": w_mean,
                    "n": len(w_quals),
                })

            window_means = [t["quality_mean"] for t in trajectory]
            trend = detect_trend(window_means)

            n_triggered = len(qualities)
            trigger_rate = round(n_triggered / n_events, 3) if n_events > 0 else 0

            quality_stats = compute_statistics(qualities)

            flags = []
            if n_triggered == 0:
                flags.append("never-triggers")
            elif n_triggered < SPARSE_THRESHOLD:
                flags.append("sparse (N=%d)" % n_triggered)
            if quality_stats["stdev"] is not None and quality_stats["stdev"] > HIGH_VARIANCE_THRESHOLD:
                flags.append("high-variance (stdev=%.2f)" % quality_stats["stdev"])
            if trend == "falling":
                flags.append("degrading")
            if trend == "volatile":
                flags.append("volatile")

            by_type_stats = {}
            for et, et_data in by_event_type.items():
                by_type_stats[et] = {
                    "quality": compute_statistics(et_data["qualities"]),
                    "register": compute_statistics(et_data["registers"]),
                }

            drive_data[drive] = {
                "quality": quality_stats,
                "differentiation": compute_statistics(differentiations),
                "register": compute_statistics(registers),
                "by_event_type": by_type_stats,
                "trigger_rate": trigger_rate,
                "n_triggered": n_triggered,
                "n_total": n_events,
                "trajectory": trajectory,
                "trend": trend,
                "flags": flags,
            }

        # Build co-occurrence matrix: for each event, which drives were relevant?
        for r in char_events:
            relevant_drives = [d for d in drive_names
                               if isinstance(r["scores"].get(d), dict)
                               and r["scores"][d].get("relevant")]
            for i_d, d1 in enumerate(relevant_drives):
                for d2 in relevant_drives[i_d + 1:]:
                    pair = tuple(sorted([d1, d2]))
                    if pair not in cooccurrence_counts:
                        cooccurrence_counts[pair] = 0
                        cooccurrence_quality[pair] = {pair[0]: [], pair[1]: []}
                    cooccurrence_counts[pair] += 1
                    q1 = r["scores"][d1].get("quality")
                    q2 = r["scores"][d2].get("quality")
                    if q1 is not None:
                        cooccurrence_quality[pair][pair[0]].append(q1)
                    if q2 is not None:
                        cooccurrence_quality[pair][pair[1]].append(q2)

        cooccurrence = {}
        for pair, count in sorted(cooccurrence_counts.items(), key=lambda x: -x[1]):
            quals = cooccurrence_quality[pair]
            cooccurrence[f"{pair[0]}+{pair[1]}"] = {
                "count": count,
                pair[0]: compute_statistics(quals[pair[0]]),
                pair[1]: compute_statistics(quals[pair[1]]),
            }

        # Aggregate cognitive quality scores across events
        cognitive_dims = ["channel_coverage", "attachment_coloring", "theory_of_mind",
                          "formation_echo", "performance_cost", "person_specific_shifts",
                          "drive_blending", "spatial_grounding"]
        cognitive_scores = {dim: [] for dim in cognitive_dims}
        for r in char_events:
            cog = r["scores"].get("_cognitive")
            if isinstance(cog, dict):
                for dim in cognitive_dims:
                    val = cog.get(dim)
                    if isinstance(val, dict):
                        s = val.get("score")
                        if s is not None:
                            try:
                                cognitive_scores[dim].append(int(s))
                            except (ValueError, TypeError):
                                pass

        cognitive_summary = {}
        for dim in cognitive_dims:
            vals = cognitive_scores[dim]
            cognitive_summary[dim] = compute_statistics(vals)

        characters[char_id] = {
            "n_events": n_events,
            "drives": drive_data,
            "cooccurrence": cooccurrence,
            "cognitive": cognitive_summary,
        }

    # Cross-character comparison: drives that appear in 2+ characters
    drive_chars = {}
    for char_id, char_data in characters.items():
        for drive in char_data["drives"]:
            drive_chars.setdefault(drive, []).append(char_id)

    shared_drives = {}
    for drive, chars in drive_chars.items():
        if len(chars) >= 2:
            shared_drives[drive] = {
                c: {
                    "quality_mean": characters[c]["drives"][drive]["quality"]["mean"],
                    "quality_stdev": characters[c]["drives"][drive]["quality"]["stdev"],
                    "n": characters[c]["drives"][drive]["n_triggered"],
                    "trigger_rate": characters[c]["drives"][drive]["trigger_rate"],
                    "trend": characters[c]["drives"][drive]["trend"],
                }
                for c in sorted(chars)
            }

    return {
        "meta": {
            "mode": "full",
            "total_events": sum(len(v) for v in char_results.values()),
            "n_windows": n_windows,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
        },
        "characters": characters,
        "shared_drives": shared_drives,
        "events": [
            {
                "index": r["index"],
                "character": r["character"],
                "type": r["type"],
                "room": r.get("room", ""),
                "desc_preview": r.get("desc", "")[:120],
                "scores": r["scores"],
            }
            for r in results
        ],
    }


# ---------------------------------------------------------------------------
# Console output
# ---------------------------------------------------------------------------

def print_flat_summary(results):
    print("\n=== SUMMARY: Average drive expression per character (flat) ===\n")
    char_drive_scores = {}
    for r in results:
        char_id = r["character"]
        if char_id not in char_drive_scores:
            char_drive_scores[char_id] = {}
        for drive, val in r["scores"].items():
            if isinstance(val, dict) and "score" in val:
                char_drive_scores[char_id].setdefault(drive, []).append(val["score"])

    for char_id in sorted(char_drive_scores):
        print(f"{char_id}:")
        for drive, scores_list in sorted(char_drive_scores[char_id].items()):
            avg = sum(scores_list) / len(scores_list)
            print(f"  {drive}: {avg:.2f} (n={len(scores_list)})")
        print()


def print_relevance_summary(results):
    print("\n=== SUMMARY: Drive expression — quality when triggered + trigger rate ===\n")
    char_drive_data = {}
    for r in results:
        char_id = r["character"]
        if char_id not in char_drive_data:
            char_drive_data[char_id] = {}
        for drive, val in r["scores"].items():
            if not isinstance(val, dict):
                continue
            entry = char_drive_data[char_id].setdefault(drive, {"relevant_scores": [], "total": 0})
            entry["total"] += 1
            if val.get("relevant", False) and val.get("score") is not None:
                entry["relevant_scores"].append(val["score"])

    print(f"{'Character':<22} {'Drive':<20} {'Quality':>7} {'Trig%':>6} {'N_trig':>6} {'N_tot':>6} {'Flat':>6}")
    print("-" * 84)

    for char_id in sorted(char_drive_data):
        for drive in sorted(char_drive_data[char_id]):
            data = char_drive_data[char_id][drive]
            total = data["total"]
            triggered = data["relevant_scores"]
            n_trig = len(triggered)
            trig_pct = (n_trig / total * 100) if total > 0 else 0
            quality = (sum(triggered) / n_trig) if n_trig > 0 else 0
            flat_scores = triggered + [1] * (total - n_trig)
            flat_mean = sum(flat_scores) / len(flat_scores) if flat_scores else 0
            print(f"{char_id:<22} {drive:<20} {quality:>7.2f} {trig_pct:>5.0f}% {n_trig:>6} {total:>6} {flat_mean:>6.2f}")
        print()


def print_full_summary(report):
    """Print the comprehensive console report for full mode."""
    meta = report["meta"]
    print(f"\n=== FULL EVALUATION REPORT ===")
    print(f"{meta['total_events']} classified events | {meta['n_windows']} temporal windows\n")

    for char_id in sorted(report["characters"]):
        char_data = report["characters"][char_id]
        print(f"--- {char_id} ({char_data['n_events']} events) ---")
        print(f"{'Drive':<22} {'N':>4} {'Quality':>7} {'stdev':>6} {'Diff':>5} {'Reg':>5} {'Trig%':>6} {'Trend':>5}")
        print("-" * 68)

        flags_to_show = []
        for drive, dd in char_data["drives"].items():
            n = dd["n_triggered"]
            q_mean = dd["quality"]["mean"]
            q_std = dd["quality"]["stdev"]
            d_mean = dd["differentiation"]["mean"]
            r_mean = dd["register"]["mean"]
            trig_pct = dd["trigger_rate"] * 100
            arrow = TREND_ARROWS.get(dd["trend"], "?")

            q_str = f"{q_mean:.2f}" if q_mean is not None else "  -  "
            s_str = f"{q_std:.2f}" if q_std is not None else "   -"
            d_str = f"{d_mean:.1f}" if d_mean is not None else "  -"
            r_str = f"{r_mean:.1f}" if r_mean is not None else "  -"

            print(f"{drive:<22} {n:>4} {q_str:>7} {s_str:>6} {d_str:>5} {r_str:>5} {trig_pct:>5.0f}% {arrow:>5}")

            for flag in dd["flags"]:
                flags_to_show.append(f"  {drive}: {flag}")

        # Event type breakdown
        has_type_split = False
        for drive, dd in char_data["drives"].items():
            if len(dd.get("by_event_type", {})) > 1:
                has_type_split = True
                break
        if has_type_split:
            print()
            print(f"  By event type:")
            for drive, dd in char_data["drives"].items():
                byet = dd.get("by_event_type", {})
                if len(byet) <= 1:
                    continue
                parts = []
                for et in sorted(byet):
                    q = byet[et]["quality"]["mean"]
                    r = byet[et]["register"]["mean"]
                    n = byet[et]["quality"]["n"]
                    q_str = f"{q:.1f}" if q is not None else "-"
                    r_str = f"{r:.1f}" if r is not None else "-"
                    parts.append(f"{et}: q={q_str} r={r_str} (N={n})")
                print(f"    {drive:<20} {' | '.join(parts)}")

        # Co-occurrence
        cooc = char_data.get("cooccurrence", {})
        if cooc:
            print()
            print(f"  Drive co-occurrence:")
            for pair_name, pair_data in list(cooc.items())[:5]:
                d1, d2 = pair_name.split("+")
                q1 = pair_data[d1]["mean"]
                q2 = pair_data[d2]["mean"]
                q1_str = f"{q1:.1f}" if q1 is not None else "-"
                q2_str = f"{q2:.1f}" if q2 is not None else "-"
                print(f"    {pair_name:<35} N={pair_data['count']:>3}  {d1}={q1_str}  {d2}={q2_str}")

        if flags_to_show:
            print()
            print("  Flags:")
            for f in flags_to_show:
                print(f"    {f}")

        # Cognitive quality dimensions
        cognitive = char_data.get("cognitive", {})
        has_cognitive = any(cognitive.get(d, {}).get("mean") is not None for d in cognitive)
        if has_cognitive:
            print()
            print("  Cognitive quality:")
            print(f"    {'Dimension':<25} {'Score':>5} {'N':>4}")
            print("    " + "-" * 36)
            cog_labels = {
                "channel_coverage": "Channel coverage",
                "attachment_coloring": "Attachment coloring",
                "theory_of_mind": "Theory of mind",
                "formation_echo": "Formation echo",
                "performance_cost": "Performance cost",
                "person_specific_shifts": "Person-specific shifts",
                "drive_blending": "Drive blending",
                "spatial_grounding": "Spatial grounding",
            }
            for dim, label in cog_labels.items():
                stats = cognitive.get(dim, {})
                mean = stats.get("mean")
                n = stats.get("n", 0)
                m_str = f"{mean:.1f}" if mean is not None else "  -"
                print(f"    {label:<25} {m_str:>5} {n:>4}")

        print()

    # Cross-character comparison
    shared = report.get("shared_drives", {})
    if shared:
        print("=== CROSS-CHARACTER COMPARISON ===\n")
        for drive in sorted(shared):
            print(f"  {drive}:")
            entries = shared[drive]
            for char_id in sorted(entries, key=lambda c: -(entries[c].get("quality_mean") or 0)):
                e = entries[char_id]
                q = f"{e['quality_mean']:.2f}" if e["quality_mean"] is not None else "-"
                s = f"stdev={e['quality_stdev']:.2f}" if e["quality_stdev"] is not None else "stdev=-"
                arrow = TREND_ARROWS.get(e["trend"], "?")
                print(f"    {char_id:<22} {q}  (N={e['n']}, {s}, trig={e['trigger_rate']:.0%}) {arrow}")
            print()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Classify emotional disposition in transcripts")
    parser.add_argument("transcript", help="Transcript directory name or path")
    parser.add_argument("--character", "-c", help="Classify only this character ID")
    parser.add_argument("--limit", "-n", type=int, help="Max events to classify")
    parser.add_argument("--verbose", "-v", action="store_true", help="Print each event's classification")
    parser.add_argument("--output", "-o", help="Write results JSON to this file")
    parser.add_argument("--mode", "-m", choices=["flat", "relevance", "full"], default="full",
                        help="Eval mode: flat | relevance | full (default: full)")
    parser.add_argument("--backend", "-b", choices=["vertex", "cli"], default="vertex",
                        help="LLM backend: vertex (Haiku via Vertex API) | cli (Opus via claude CLI)")
    args = parser.parse_args()

    drives_by_char = load_drives(SOCIAL_CONFIG)
    transcript = load_transcript(args.transcript)
    events = transcript if isinstance(transcript, list) else transcript.get("events", [])

    classifiable = [e for e in events if e.get("type") in ("dialogue", "aside")]
    if args.character:
        classifiable = [e for e in classifiable if e.get("character") == args.character]
    if args.limit:
        classifiable = classifiable[:args.limit]

    mode = args.mode
    if mode == "full":
        prompt_builder = build_full_prompt
        max_tokens = 800
    elif mode == "relevance":
        prompt_builder = build_relevance_prompt
        max_tokens = 500
    else:
        prompt_builder = build_flat_prompt
        max_tokens = 500

    use_cli = args.backend == "cli"
    print(f"Classifying {len(classifiable)} events (mode={mode}, backend={args.backend})", file=sys.stderr)

    client = None if use_cli else AnthropicVertex(project_id=VERTEX_PROJECT, region=VERTEX_REGION)
    prompts_by_char = {}
    results = []
    errors = 0

    for i, event in enumerate(classifiable):
        char_id = event.get("character", "unknown")
        if char_id not in prompts_by_char:
            drives = drives_by_char.get(char_id, [])
            if not drives:
                print(f"  Warning: no drives for {char_id}, skipping", file=sys.stderr)
                continue
            prompts_by_char[char_id] = prompt_builder(drives)

        system_prompt = prompts_by_char[char_id]
        try:
            scores = (classify_event_cli(system_prompt, event) if use_cli
                      else classify_event(client, system_prompt, event, max_tokens=max_tokens))
        except Exception as ex:
            print(f"  Error classifying event {i}: {ex}", file=sys.stderr)
            errors += 1
            scores = {"_error": str(ex)}

        result = {
            "index": i,
            "character": char_id,
            "type": event["type"],
            "room": event.get("room", ""),
            "desc": event.get("desc", "")[:120],
            "scores": scores,
            "mode": mode,
        }
        results.append(result)

        if args.verbose:
            if mode == "full":
                parts = []
                for k, v in scores.items():
                    if not isinstance(v, dict):
                        continue
                    if v.get("relevant"):
                        q = v.get("quality", "-")
                        d = v.get("differentiation", "-")
                        r = v.get("register", "-")
                        parts.append(f"{k}=q{q}/d{d}/r{r}")
                    else:
                        parts.append(f"{k}=-")
                drive_summary = "  ".join(parts)
            elif mode == "relevance":
                drive_summary = "  ".join(
                    f"{k}={'R' if v.get('relevant') else '-'}{v.get('score', '-')}"
                    for k, v in scores.items()
                    if isinstance(v, dict)
                )
            else:
                drive_summary = "  ".join(
                    f"{k}={v['score']}" for k, v in scores.items()
                    if isinstance(v, dict) and "score" in v
                )
            print(f"  [{i+1}/{len(classifiable)}] {char_id} ({event['type']}): {drive_summary}")

        if (i + 1) % 20 == 0:
            print(f"  Progress: {i+1}/{len(classifiable)}", file=sys.stderr)

    print(f"\nDone. {len(results)} classified, {errors} errors.", file=sys.stderr)

    if mode == "full":
        report = build_full_report(results, drives_by_char)
        print_full_summary(report)
        if args.output:
            with open(args.output, "w") as f:
                json.dump(report, f, indent=2)
            print(f"Full report written to {args.output}", file=sys.stderr)
    elif mode == "relevance":
        print_relevance_summary(results)
        if args.output:
            with open(args.output, "w") as f:
                json.dump(results, f, indent=2)
            print(f"Full results written to {args.output}", file=sys.stderr)
    else:
        print_flat_summary(results)
        if args.output:
            with open(args.output, "w") as f:
                json.dump(results, f, indent=2)
            print(f"Full results written to {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
