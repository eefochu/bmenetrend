#!/usr/bin/env python3
"""Measure form submission to first visible itinerary through an owned Device session.

Start on the completed planner form with no result cards visible. Supply endpoint
labels to reject a changed scenario. Other apps' notifications are excluded from
the captured labels. This measures accessibility visibility, including driver cost,
not an isolated render or network benchmark.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--agent-device", required=True)
parser.add_argument("--origin", required=True)
parser.add_argument("--destination", required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--timeout", type=float, default=45)
args = parser.parse_args()

def snapshot():
    text = subprocess.check_output([args.agent_device, "snapshot", "--raw"], text=True)
    nodes = {}
    for line in text.splitlines():
        if line.startswith("{"):
            node = json.loads(line)
            if node.get("bundleId") == "hu.app.bmenetrend":
                nodes[node["index"]] = node
    return nodes

def labels(nodes):
    return [n["label"] for n in nodes.values() if n.get("visibleToUser") and n.get("label")]

def has_itinerary(values):
    return sum(bool(re.fullmatch(r"\d{2}:\d{2}", value)) for value in values) >= 2

before = snapshot()
values = labels(before)
if not all(any(endpoint in value for value in values) for endpoint in (args.origin, args.destination)):
    raise RuntimeError("Expected endpoints are not visible; refusing a different scenario")
if has_itinerary(values):
    raise RuntimeError("Result cards already visible; return to the planner form first")
target = next(n for n in before.values() if n.get("label") == "Mutasd az útvonalakat" and n.get("visibleToUser"))
start = time.perf_counter()
subprocess.run([args.agent_device, "press", 'text="Mutasd az útvonalakat"'], check=True)
result = {"origin": args.origin, "destination": args.destination, "first_itinerary_seconds": None,
          "measurement": "Accessibility visibility after Device-panel submission; includes automation overhead"}
while time.perf_counter() - start < args.timeout:
    current = labels(snapshot())
    elapsed = time.perf_counter() - start
    if has_itinerary(current):
        result.update(first_itinerary_seconds=round(elapsed, 3), status="itinerary")
        break
    errors = [label for label in current if "átmenetileg nem érhető el" in label or "nem találtunk megfelelő útvonalat" in label]
    if errors:
        result.update(status="error", error=errors, elapsed_seconds=round(elapsed, 3))
        break
    time.sleep(0.1)
else:
    result.update(status="timeout", elapsed_seconds=round(time.perf_counter() - start, 3))
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(result, ensure_ascii=False), flush=True)
if result["status"] != "itinerary":
    raise SystemExit(1)
