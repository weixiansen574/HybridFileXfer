#!/usr/bin/env python3
"""Explicit local synthetic benchmark; never included in the regular test run."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def tool(name):
    home = os.environ.get("JAVA_HOME")
    if home:
        path = Path(home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if path.is_file():
            return str(path)
    found = shutil.which(name)
    if not found:
        raise SystemExit("An installed JDK is required")
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size-mib", type=int, default=64)
    parser.add_argument("--small-files", type=int, default=200)
    parser.add_argument("--small-kib", type=int, default=16)
    parser.add_argument("--channels", default="1,2,4")
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--sha256", action="store_true", help="Enable optional post-transfer SHA-256")
    parser.add_argument("--classes", type=Path, default=ROOT / ".build/classes")
    parser.add_argument("--output", type=Path, default=ROOT / ".build/benchmarks/transfer.csv")
    args = parser.parse_args()
    if not (args.classes / "top/weixiansen574/hybridfilexfer/core/ReliableTransfer.class").exists():
        raise SystemExit("First build the current source: python tools/build_desktop.py --test")
    classes = ROOT / ".build/benchmark-classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run([tool("javac"), "--release", "8", "-encoding", "UTF-8", "-cp", str(args.classes),
                    "-d", str(classes), str(ROOT / "tools/java/TransferBenchmark.java")],
                   cwd=ROOT, check=True, timeout=120)
    classpath = os.pathsep.join([str(args.classes), str(classes)])
    subprocess.run([tool("java"), "-cp", classpath, "TransferBenchmark", str(args.size_mib),
                    str(args.small_files), str(args.small_kib), args.channels,
                    str(args.repetitions), str(args.output), str(args.sha256).lower()], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
