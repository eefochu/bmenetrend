#!/usr/bin/env python3
"""Cold process launches via the Device panel session; read Android event timings.

Diagnostic TTID only (not a Macrobenchmark or a disk-cache-cold measurement).
Pass the exact agent-device command returned by device_open. Never claims a device.
"""
import argparse
import json
import re
import statistics
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--agent-device", required=True)
parser.add_argument("--adb", required=True)
parser.add_argument("--serial", required=True)
parser.add_argument("--package", default="hu.app.bmenetrend")
parser.add_argument("--iterations", type=int, default=10)
parser.add_argument("--compilation-state", default="default installed compilation state")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()

def events():
    return subprocess.check_output([args.adb, "-s", args.serial, "logcat", "-b", "events", "-d"], text=True)

def launches():
    return [line for line in events().splitlines()
            if "am_activity_launch_time:" in line and args.package + "/" in line]

samples = []
for iteration in range(args.iterations):
    previous = set(launches())
    subprocess.run([args.agent_device, "open", args.package, "--relaunch"], check=True)
    deadline = time.monotonic() + 20
    while True:
        fresh = [line for line in launches() if line not in previous]
        if fresh:
            millis = int(re.search(r",(\d+)\]$", fresh[-1]).group(1))
            samples.append(millis)
            print(f"Launch {iteration + 1}: {millis} ms", flush=True)
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("No new launch event; refusing to reuse an earlier timing")
        time.sleep(0.1)
result = {"package": args.package, "serial": args.serial,
          "metric": f"Android event TTID, process cold, OS caches retained, {args.compilation_state}",
          "samples_ms": samples, "median_ms": statistics.median(samples)}
args.output.write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result), flush=True)
