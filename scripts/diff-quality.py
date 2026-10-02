#!/usr/bin/env python3
"""Test-quality gates scoped to the lines a change touches.

    diff-quality.py --base REF targets LINES_FILE
        One line per project with changed main sources: the project directory, a space, and the
        PIT target-class globs for its changed files, comma-separated. Prints nothing when no main
        source changed. Also writes the changed lines to LINES_FILE in the form the CHANGED_LINES
        PIT filter reads (tools/pitest-filters), so that
        `./gradlew -p DIR pitest -PpitestTargets=GLOBS -PpitestChangedLines=LINES_FILE` mutates
        those lines only.

    diff-quality.py --base REF coverage --min PCT KOVER_XML
        Fails when fewer than PCT% of the changed executable lines are covered.

    diff-quality.py --base REF mutation --min PCT PIT_XML...
        Fails when the tests detect fewer than PCT% of the mutants PIT planted on covered changed
        lines (PIT's "test strength", restricted to the change).

"Changed" means added or modified in the main sources of any project, compared with the merge
base of REF and HEAD: what a pull request adds, not what main gained since it branched. Lines that
compile to nothing (comments, imports, blank lines) do not count either way.

Both gates are scoped to the change for the same reason: holding a whole class to the bar would
make the author of a one-line fix pay for every older gap in the class, and gaps nobody touches
are not what a pull request can be asked to close. The whole-project coverage floor (koverVerify in
build.gradle.kts) is what keeps the total from sliding.

KOVER_XML is Kover's JaCoCo-format report; PIT_XML is PIT's mutations.xml. Both identify a source
file by its package and file name, which is how changed paths are matched to them, so a Kotlin file
whose directory does not match its package (common under src/main/java here) still lines up.

When GITHUB_STEP_SUMMARY is set, a Markdown report is appended to it as well.
"""

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

# A path is a main source when it lives under some project's src/main. Test sources, build
# scripts and examples/ never count.
MAIN_SOURCE = re.compile(r"(^|/)src/main/.*\.(kt|java)$")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
# Top-level (column 0) type declarations: a nested type compiles to Outer$Nested, which the
# Outer$* glob below already covers.
KOTLIN_TYPE = re.compile(
    r"^(?:(?:public|internal|private|open|abstract|sealed|data|enum|annotation|value|fun|expect|actual)\s+)*"
    r"(?:class|interface|object)\s+(\w+)",
    re.MULTILINE,
)
JAVA_TYPE = re.compile(
    r"^(?:(?:public|final|abstract|sealed|non-sealed|static|strictfp)\s+)*(?:class|interface|enum|record|@interface)\s+(\w+)",
    re.MULTILINE,
)
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", re.MULTILINE)

# PIT statuses that count as the tests noticing the mutant. A mutant that makes the tests hang
# or crash the JVM was detected too: the build would not have passed with it.
DETECTED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"}


def changed_lines(base, head):
    """{path: set of added or modified line numbers} for the main sources head changed since base."""
    merge_base = subprocess.check_output(["git", "merge-base", base, head], text=True).strip()
    diff = subprocess.check_output(
        ["git", "diff", "--unified=0", "--diff-filter=AMR", "--no-color", merge_base, head],
        text=True,
    )
    changes = {}
    for chunk in re.split(r"^diff --git ", diff, flags=re.MULTILINE)[1:]:
        target = re.search(r"^\+\+\+ b/(.+)$", chunk, re.MULTILINE)
        if not target or not MAIN_SOURCE.search(target.group(1)):
            continue
        lines = set()
        for start, count in HUNK.findall(chunk):
            first, n = int(start), 1 if count == "" else int(count)
            lines.update(range(first, first + n))
        if lines:
            changes[target.group(1)] = lines
    return changes


def source_key(path):
    """(package as a/b/c, file name): how both reports name a source file."""
    with open(path, encoding="utf-8") as f:
        match = PACKAGE.search(f.read())
    package = match.group(1).replace(".", "/") if match else ""
    return package, os.path.basename(path)


def keyed(changes):
    return {source_key(path): (path, lines) for path, lines in changes.items()}


def pct(part, whole):
    return 100.0 * part / whole if whole else 100.0


def summarize(markdown):
    print(markdown)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(markdown + "\n")


def cmd_targets(args):
    changes = changed_lines(args.base, args.head)
    with open(args.lines_file, "w", encoding="utf-8") as f:
        for (package, name), (_, lines) in sorted(keyed(changes).items()):
            f.write(" ".join([package, name] + [str(n) for n in sorted(lines)]) + "\n")
    globs = defaultdict(list)
    for path in sorted(changes):
        with open(path, encoding="utf-8") as f:
            text = f.read()
        match = PACKAGE.search(text)
        prefix = match.group(1) + "." if match else ""
        stem = os.path.splitext(os.path.basename(path))[0]
        if path.endswith(".kt"):
            # Top-level functions and properties compile into <Stem>Kt.
            names = set(KOTLIN_TYPE.findall(text)) | {stem + "Kt"}
        else:
            names = set(JAVA_TYPE.findall(text)) or {stem}
        project = path.split("src/main/", 1)[0].rstrip("/") or "."
        for name in sorted(names):
            globs[project] += [prefix + name, prefix + name + "$*"]
    for project in sorted(globs):
        print(project, ",".join(globs[project]))
    return 0


def cmd_coverage(args):
    changes = keyed(changed_lines(args.base, args.head))
    covered, missed = defaultdict(list), defaultdict(list)
    for package in ET.parse(args.report).getroot().iter("package"):
        for source in package.iter("sourcefile"):
            entry = changes.get((package.get("name"), source.get("name")))
            if not entry:
                continue
            path, lines = entry
            for line in source.iter("line"):
                nr = int(line.get("nr"))
                if nr in lines:
                    # A line counts as covered when any of its instructions ran, as in JaCoCo.
                    (covered if int(line.get("ci")) > 0 else missed)[path].append(nr)

    total_covered = sum(map(len, covered.values()))
    total = total_covered + sum(map(len, missed.values()))
    score = pct(total_covered, total)
    ok = score >= args.min
    report = [
        f"### Coverage of changed lines: {score:.1f}% ({total_covered}/{total}, minimum {args.min:g}%)",
        "",
    ]
    if total == 0:
        report.append("No executable main-source lines changed.")
    for path in sorted(missed):
        report.append(f"- `{path}` uncovered: {compress(sorted(missed[path]))}")
    summarize("\n".join(report))
    return 0 if ok else 1


def cmd_mutation(args):
    changes = keyed(changed_lines(args.base, args.head))
    detected, survived = 0, defaultdict(list)
    uncovered = 0
    for report in args.reports:
        for mutation in ET.parse(report).getroot().iter("mutation"):
            cls = mutation.findtext("mutatedClass")
            package = cls.rsplit(".", 1)[0].replace(".", "/") if "." in cls else ""
            entry = changes.get((package, mutation.findtext("sourceFile")))
            line = int(mutation.findtext("lineNumber"))
            if not entry or line not in entry[1]:
                continue
            status = mutation.get("status")
            if status in DETECTED:
                detected += 1
            elif status == "SURVIVED":
                survived[entry[0]].append((line, mutation.findtext("description")))
            elif status == "NO_COVERAGE":
                # The coverage gate already reports these lines; counting them here too would make
                # one missing test fail both gates.
                uncovered += 1

    total_survived = sum(map(len, survived.values()))
    total = detected + total_survived
    score = pct(detected, total)
    ok = score >= args.min
    report = [
        f"### Mutants detected on changed lines: {score:.1f}% ({detected}/{total}, minimum {args.min:g}%)",
        "",
        "Each surviving mutant is a change to the code that no test noticed: an assertion is missing,"
        " or the code it mutated does not matter.",
        "",
    ]
    if total == 0:
        report.append("No mutants on covered changed lines.")
    if uncovered:
        report.append(f"{uncovered} mutants sit on uncovered lines and are left to the coverage gate.")
    for path in sorted(survived):
        report.append(f"- `{path}`")
        for line, description in sorted(survived[path]):
            report.append(f"  - line {line}: {description}")
    summarize("\n".join(report))
    return 0 if ok else 1


def compress(numbers):
    """[3, 4, 5, 9] -> '3-5, 9'."""
    ranges, start, prev = [], None, None
    for n in numbers + [None]:
        if start is not None and n is not None and n == prev + 1:
            prev = n
            continue
        if start is not None:
            ranges.append(str(start) if start == prev else f"{start}-{prev}")
        start = prev = n
    return ", ".join(ranges)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", required=True, help="ref the change is measured against, e.g. origin/main")
    parser.add_argument("--head", default="HEAD", help="the change's tip (default HEAD); the working tree must match it")
    sub = parser.add_subparsers(dest="command", required=True)
    targets = sub.add_parser("targets")
    targets.add_argument("lines_file")
    targets.set_defaults(run=cmd_targets)
    coverage = sub.add_parser("coverage")
    coverage.add_argument("--min", type=float, required=True)
    coverage.add_argument("report")
    coverage.set_defaults(run=cmd_coverage)
    mutation = sub.add_parser("mutation")
    mutation.add_argument("--min", type=float, required=True)
    mutation.add_argument("reports", nargs="+")
    mutation.set_defaults(run=cmd_mutation)
    args = parser.parse_args()
    sys.exit(args.run(args))


if __name__ == "__main__":
    main()
