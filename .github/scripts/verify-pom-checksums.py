#!/usr/bin/env python3
"""Checks that every component with a Gradle module file (.module) in a verification
metadata file also records the checksum of its .pom, and optionally adds the missing ones.

A fresh local run can resolve only the .module of a component while a CI runner also
resolves its .pom, so metadata generated locally may lack POM entries that fail CI.

Usage:
  verify-pom-checksums.py FILE...          fail (exit 1) if a .pom checksum is missing
  verify-pom-checksums.py --fix FILE...    download the missing .pom files from the repository
                                           and insert their SHA-256 checksums

Environment:
  AXIOM_POM_REPOSITORIES  space-separated repositories the POMs are taken from, in order
                          (default: Maven Central, then the Gradle plugin portal)

Downloads answered with HTTP 429 are retried a bounded number of times with a growing pause.
The file is only rewritten after every missing POM was downloaded.

Components covered by <trusted-artifacts> have no entries and are not affected.
"""
import hashlib
import os
import re
import sys
import time
import urllib.error
import urllib.request

REPOSITORIES = os.environ.get(
    "AXIOM_POM_REPOSITORIES",
    "https://repo1.maven.org/maven2 https://plugins.gradle.org/m2").split()
ATTEMPTS = 6
ORIGIN = "Added from the repository by verify-pom-checksums.py"
COMPONENT = re.compile(
    r'(?P<indent>[ \t]*)<component group="(?P<group>[^"]+)" name="(?P<name>[^"]+)" '
    r'version="(?P<version>[^"]+)">\n(?P<body>.*?)(?P=indent)</component>', re.S)
ARTIFACT = re.compile(r'<artifact name="([^"]+)"')


def missing_poms(text):
    """Yields (match, pom file name) for each component with a .module but no .pom entry."""
    for match in COMPONENT.finditer(text):
        names = ARTIFACT.findall(match["body"])
        pom = f"{match['name']}-{match['version']}.pom"
        if any(n.endswith(".module") for n in names) and pom not in names:
            yield match, pom


def fetch(url):
    for attempt in range(ATTEMPTS):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            if error.code not in (429, 500, 502, 503, 504) or attempt == ATTEMPTS - 1:
                raise
            time.sleep(2 ** (attempt + 1))


def pom_checksum(match, pom):
    path = f"{match['group'].replace('.', '/')}/{match['name']}/{match['version']}/{pom}"
    for repository in REPOSITORIES:
        content = fetch(f"{repository.rstrip('/')}/{path}")
        if content is not None:
            return hashlib.sha256(content).hexdigest()
    raise SystemExit(f"{pom} not found in {', '.join(REPOSITORIES)}")


def insert(text, match, pom, digest):
    indent = match["indent"]
    entry = (f'{indent}   <artifact name="{pom}">\n'
             f'{indent}      <sha256 value="{digest}" origin="{ORIGIN}"/>\n'
             f'{indent}   </artifact>\n')
    # Artifacts are sorted by name (.jar, .module, .pom), so the .pom goes last.
    body = match["body"]
    return text[:match.start("body")] + body + entry + text[match.end("body"):]


def main(argv):
    fix = "--fix" in argv
    files = [a for a in argv if a != "--fix"]
    if not files:
        print(__doc__, file=sys.stderr)
        return 2
    failed = False
    for path in files:
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        missing = list(missing_poms(text))
        for match, pom in missing:
            print(f"{path}: {match['group']}:{match['name']}:{match['version']} has a .module but no {pom}")
        if missing and fix:
            # Insert from the end so earlier match offsets stay valid.
            for match, pom in reversed(missing):
                text = insert(text, match, pom, pom_checksum(match, pom))
            with open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(text)
            print(f"{path}: added {len(missing)} .pom checksum(s)")
        elif missing:
            failed = True
    if failed:
        print("Run `.github/scripts/verify-pom-checksums.py --fix <file>` and commit the result.",
              file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
