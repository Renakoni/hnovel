"""Validate this checkout's compliance fixtures with the pinned EPUBCheck release.

Generate fixtures first with :epub:test and :app:testDebugUnitTest
--tests '*EpubXhtmlWorkerTest'. Uses only Python's standard library.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--epubcheck", required=True, type=Path, help="EPUBCheck 5.3.0 JAR")
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    version = subprocess.run(
        [args.java, "-jar", str(args.epubcheck.resolve()), "--version"],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    if version != "EPUBCheck v5.3.0":
        raise SystemExit(f"Expected EPUBCheck v5.3.0, got {version!r}")
    fixtures = [root / "epub/build/epub-compliance", root / "app/build/epub-compliance/worker"]
    samples = []
    for directory in fixtures:
        files = sorted(directory.glob("*.epub"))
        if not files:
            raise SystemExit(f"Missing generated fixtures: {directory}")
        samples.extend(files)
    output = root / "build/p72-verification/epubcheck"
    output.mkdir(parents=True, exist_ok=True)
    results = []
    for index, sample in enumerate(samples):
        report = output / f"{index + 1}.json"
        run = subprocess.run(
            [args.java, "-jar", str(args.epubcheck.resolve()), str(sample), "--json", str(report)],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
        report.with_suffix(".log").write_text(run.stdout + run.stderr, encoding="utf-8")
        data = json.loads(report.read_text(encoding="utf-8"))
        diagnostics = [message for message in data["messages"]
                       if message["severity"] in ("FATAL", "ERROR", "WARNING")]
        passed = run.returncode == 0 and not diagnostics
        result = {"file": sample.relative_to(root).as_posix(),
                  "sha256": hashlib.sha256(sample.read_bytes()).hexdigest(),
                  "passed": passed, "exit_code": run.returncode,
                  "diagnostics": diagnostics, "report": report.relative_to(root).as_posix()}
        results.append(result)
        print(f"{'PASS' if passed else 'FAIL'} {result['file']}", flush=True)
    (output / "results.json").write_text(
        json.dumps({"version": version, "results": results}, indent=2) + "\n", encoding="utf-8")
    if not all(result["passed"] for result in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
