#!/usr/bin/env python3
"""Replay R3.8 Parakeet decoded-window JSONL through both R3.9 stitch policies.

No model, Android SDK, or third-party packages required. Only reads a user
supplied local log; never uploads or modifies it. This intentionally mirrors
ParakeetOverlapStitcher (legacy) and ParakeetGuardedOverlapStitcher (R3.9).
Usage: python3 tools/replay_r38_stitch.py path/to/log.jsonl
"""
import argparse
import json
import re
import sys


def split_words(text):
    return [token for token in re.split(r"\s+", text.strip()) if token]


def normalize_old(token):
    return "".join(c for c in token.lower() if c.isalnum())


def normalize_guarded(token):
    clean = normalize_old(token)
    return {"dr": "doctor", "mr": "mister", "mrs": "missus"}.get(clean, clean)


class Legacy:
    def __init__(self):
        self.previous = []

    def append(self, text, index):
        words = split_words(text)
        drop = 0
        if words:
            for n in range(min(len(self.previous), len(words), 12), 0, -1):
                if all(normalize_old(a) == normalize_old(b)
                       for a, b in zip(self.previous[-n:], words[:n])):
                    drop = n
                    break
            self.previous = words
        return " ".join(words[drop:]), drop, "legacy-exact-suffix-prefix"


class Guarded:
    def __init__(self):
        self.previous = []
        self.previous_index = None

    def append(self, text, index):
        words = split_words(text)
        if not words:
            self.previous = []
            self.previous_index = None
            return "", 0, "blank-reset"
        connected = self.previous_index is not None and index == self.previous_index + 1
        drop = 0
        reason = "no-match" if connected else "disconnected-or-first-window"
        if connected:
            for n in range(min(len(self.previous), len(words), 12), 0, -1):
                if all(normalize_guarded(a) and normalize_guarded(a) == normalize_guarded(b)
                       for a, b in zip(self.previous[-n:], words[:n])):
                    drop = n
                    break
            if drop == len(words) and len(words) <= 2:
                drop = 0
                reason = "short-complete-match-preserved"
            elif drop:
                reason = f"adjacent-suffix-prefix-{drop}-words"
        self.previous = words
        self.previous_index = index
        return " ".join(words[drop:]), drop, reason


def replay(path):
    legacy, guarded = Legacy(), Guarded()
    total = matches = differences = 0
    legacy_words = guarded_words = 0
    print("index | legacy_removed -> guarded_removed | original | legacy | guarded | reason")
    with open(path, encoding="utf-8") as src:
        for line in src:
            row = json.loads(line)
            if row.get("stage") != "parakeet_window":
                continue
            m = row["metadata"]
            index, raw = int(m["index"]), row["text"]
            old, old_n, _ = legacy.append(raw, index)
            new, new_n, reason = guarded.append(raw, index)
            total += 1
            matches += int(old == m["stitchedText"])
            legacy_words += len(split_words(old))
            guarded_words += len(split_words(new))
            if old != new:
                differences += 1
                print(f"{index:>5} | {old_n}->{new_n} | {raw!r} | {old!r} | {new!r} | {reason}")
    print(f"total_windows={total} legacy_replay_matches={matches}/{total}"
          f" changed_windows={differences} legacy_output_words={legacy_words}"
          f" guarded_output_words={guarded_words}")
    if matches != total:
        print("WARNING: legacy replay did not reproduce all recorded windows", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jsonl", help="Locally exported R3.8 ASR JSONL file")
    args = parser.parse_args()
    sys.exit(replay(args.jsonl))
