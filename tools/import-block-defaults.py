"""Build the offline vanilla defaults table from pinned local mcmeta summary checkouts.

Pass a directory of <commit>/version.json and <commit>/blocks/data.json, then the output TSV.
The generated header identifies every source revision needed to reproduce the table.
"""
import argparse
import json
import re
from collections import defaultdict
from pathlib import Path


def build(checkouts):
    facts = defaultdict(list)
    sources = {}
    for directory in sorted(checkouts.iterdir()):
        if not re.fullmatch(r"[0-9a-f]{40}", directory.name):
            raise ValueError(f"Expected a pinned commit directory: {directory}")
        meta = json.loads((directory / "version.json").read_text(encoding="utf-8"))
        version = meta["data_version"]
        if type(version) is not int or version < 0 or version in sources:
            raise ValueError(f"Invalid or duplicate DataVersion for {directory}")
        sources[version] = (meta["id"], directory.name)
        report = json.loads((directory / "blocks/data.json").read_text(encoding="utf-8"))
        if not report:
            raise ValueError(f"Empty block report for {directory}")
        for block, (domain, defaults) in report.items():
            if not re.fullmatch(r"[a-z0-9_./-]+", block) or defaults.keys() != domain.keys():
                raise ValueError(f"Invalid block definition: {block}")
            for key, value in defaults.items():
                if not re.fullmatch(r"[a-z0-9_]+", key) or not re.fullmatch(r"[a-z0-9_-]+", value) or value not in domain[key]:
                    raise ValueError(f"Invalid default for {block}.{key}")
            signature = ",".join(f"{key}={defaults[key]}" for key in sorted(defaults))
            facts[block, signature].append(version)
    if not sources:
        raise ValueError("No reports supplied")
    groups = defaultdict(list)
    for (block, signature), versions in facts.items():
        groups[tuple(sorted(versions)), signature].append(block)
    lines = ["# Source: https://github.com/misode/mcmeta (summary/blocks), derived from vanilla generator reports."]
    lines.extend(f"# {v} {name} {commit}" for v, (name, commit) in sorted(sources.items()))
    for (versions, signature), blocks in sorted(groups.items()):
        lines.append("\t".join([",".join(map(str, versions)), signature, ",".join(sorted(blocks))]))
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkouts", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    data = build(args.checkouts)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(data.encode("utf-8"))
