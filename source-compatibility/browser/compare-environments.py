"""Compare owned-fixture reports; different hardware fingerprints are not failures."""

import argparse
import json
from pathlib import Path


def read_report(path):
    rows = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    metadata, samples = rows[0], rows[1:]
    if metadata["schema"] != 1 or sorted((s["foreground"], s["iteration"]) for s in samples) != [
        (False, 0), (False, 1), (True, 0), (True, 1)
    ]:
        raise ValueError(f"{path}: expected schema 1 and four complete observations")
    return metadata, samples


def unique(values):
    return "; ".join(sorted({json.dumps(v, ensure_ascii=False, sort_keys=True, separators=(",", ":")) for v in values}))


def realms(sample):
    cross = sample["cross"]
    return {"main": cross["top"], "frame": cross["frame"]["environment"], "worker": cross["worker"]}


def summarize(samples):
    result = {}
    for mode in (False, True):
        prefix = "foreground" if mode else "background"
        selected = [sample for sample in samples if sample["foreground"] == mode]
        result[f"{prefix}/top-cookie-names"] = unique(s["top"]["sentNames"] for s in selected)
        result[f"{prefix}/frame-cookie-names"] = unique(s["cross"]["frame"]["sentNames"] for s in selected)
        result[f"{prefix}/fetch-cookie-names"] = unique(s["cross"]["fetchNames"] for s in selected)
        result[f"{prefix}/network-ua-matches"] = unique(
            s["cross"]["networkUa"] == s["cross"]["top"]["userAgent"] == s["cross"]["frame"]["networkUa"]
            for s in selected
        )
        for name in ("main", "frame", "worker"):
            values = [realms(s)[name] for s in selected]
            for field in ("userAgent", "webdriverPresent", "fetchNative", "secureContext"):
                result[f"{prefix}/{name}/{field}"] = unique(v[field] for v in values)
            result[f"{prefix}/{name}/webgl"] = unique(
                {key: v["webgl"].get(key) for key in ("available", "vendor", "renderer", "version")}
                for v in values
            )
            for surface in sorted(set().union(*(v["canvasBackends"].keys() for v in values))):
                result[f"{prefix}/{name}/{surface}"] = unique(
                    {"blends": [r["blend"] for r in v["canvasBackends"][surface].get("reads", [])],
                     "error": v["canvasBackends"][surface].get("error")}
                    for v in values
                )
    return result


def compare(left, right):
    left_meta, left_samples = read_report(left)
    right_meta, right_samples = read_report(right)
    lines = ["# Native environment comparison", "", f"Left: {Path(left).name}; right: {Path(right).name}", ""]
    for label, metadata in (("Left", left_meta), ("Right", right_meta)):
        lines.append(f"{label}: `{json.dumps(metadata, ensure_ascii=False, sort_keys=True)}`")
    same_probe = left_meta["probeSha256"] == right_meta["probeSha256"]
    lines += ["", f"Same probe: {same_probe}. Differences below are observations, not CF scores or hardware authenticity checks.", ""]
    if not same_probe:
        lines += ["Probe code differs; repeat with matching probes before attributing changes to the environment.", ""]
    left_values, right_values = summarize(left_samples), summarize(right_samples)
    differences = [key for key in sorted(left_values.keys() | right_values.keys()) if left_values.get(key) != right_values.get(key)]
    lines += [f"Compared {len(left_values.keys() | right_values.keys())} fields; {len(differences)} differ.", ""]
    if differences:
        lines += ["| Field | Left | Right |", "|---|---|---|"]
        for key in differences:
            escaped = [str(value).replace("|", "\\|") for value in (key, left_values.get(key), right_values.get(key))]
            lines.append("| " + " | ".join(escaped) + " |")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("left")
    parser.add_argument("right")
    parser.add_argument("--output", help="Write Markdown as UTF-8 instead of stdout")
    args = parser.parse_args()
    output = compare(args.left, args.right)
    if args.output:
        Path(args.output).write_text(output, encoding="utf-8")
    else:
        print(output, end="")
