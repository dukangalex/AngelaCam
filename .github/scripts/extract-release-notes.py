#!/usr/bin/env python3
"""Extract one version's section from the project changelog for a GitHub release."""
import re
import sys
from pathlib import Path


def extract_release_notes(text: str, tag: str) -> str:
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError(f"invalid release tag: {tag!r}")
    lines = text.splitlines()
    heading = f"## {tag}"
    starts = [index for index, line in enumerate(lines) if line.strip() == heading]
    if len(starts) != 1:
        raise ValueError(f"expected exactly one {heading!r} heading, found {len(starts)}")
    start = starts[0]
    end = next((index for index in range(start + 1, len(lines)) if lines[index].startswith("## ")), len(lines))
    body = "\n".join(lines[start + 1:end]).strip()
    if not body:
        raise ValueError(f"{heading} has no release notes")
    return f"{heading}\n\n{body}\n"


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: extract-release-notes.py <tag> <changelog> <output>")
    tag, source, destination = sys.argv[1:]
    extracted = extract_release_notes(Path(source).read_text(encoding="utf-8"), tag)
    Path(destination).write_text(extracted, encoding="utf-8")


if __name__ == "__main__":
    main()
