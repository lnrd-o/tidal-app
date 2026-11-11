#!/usr/bin/env python3
"""Fails when user-visible text is written in code instead of in res/values/strings.xml.

The check reads the Kotlin sources and the XML resources of the app. It looks for string
literals at the places where text reaches the screen, the speech output or a notification.
A line that has to keep a literal can carry the comment `// i18n-ignore` with a reason.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = ROOT / "app/src/main/kotlin"
RESOURCES = ROOT / "app/src/main/res"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"

LITERAL = r'"(?:[^"\\\n]|\\.)*"'
# Functions whose first argument is text for the user.
TEXT_FUNCTIONS = [
    "Text", "SectionTitle", "UpperLabel", "LinkPill", "FilterTab", "EmptyState", "ErrorCard", "NameDialog", "notify", "SectionHeading",
    "AssistChip", "SuggestionChip", "Tab", "DropdownMenuItem",
]
PATTERNS = [
    (re.compile(r"\b(?:%s)\(\s*(?:text\s*=\s*)?(%s)" % ("|".join(TEXT_FUNCTIONS), LITERAL)), "text argument"),
    (re.compile(r"\bSettingRow\([^,()]+,\s*(%s)" % LITERAL), "setting title"),
    (re.compile(r"\bcontentDescription\s*=\s*(%s)" % LITERAL), "content description"),
    (re.compile(r"\bIcon\([^\n]*?,\s*(%s)\s*[,)]" % LITERAL), "icon description"),
    (re.compile(r"\b(?:title|subtitle|text)\s*=\s*(%s)" % LITERAL), "text parameter"),
    (re.compile(r"\.(?:setContentTitle|setContentText|setSubText|setTicker)\(\s*(%s)" % LITERAL), "notification text"),
    (re.compile(r"Toast\.makeText\([^,]+,\s*(%s)" % LITERAL), "toast"),
    (re.compile(r"\berror\s*=\s*(%s)" % LITERAL), "error text"),
    (re.compile(r"\.setTitle\(\s*(%s)" % LITERAL), "media title"),
]
XML_ATTRIBUTES = re.compile(r'android:(?:text|contentDescription|label|description|title|hint|summary)="([^@?"][^"]*)"')


def has_words(literal: str) -> bool:
    body = literal[1:-1]
    body = re.sub(r"\$\{[^}]*\}", "", body)
    body = re.sub(r"\$[A-Za-z_][A-Za-z0-9_]*", "", body)
    return re.search(r"[A-Za-z]{2,}", body) is not None


def scan_kotlin(path: pathlib.Path):
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith(("//", "*", "/*")) or "i18n-ignore" in line:
            continue
        reported = set()
        for pattern, kind in PATTERNS:
            for match in pattern.finditer(line):
                if match.start(1) not in reported and has_words(match.group(1)):
                    reported.add(match.start(1))
                    yield number, kind, match.group(1)


def scan_xml(path: pathlib.Path):
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        for match in XML_ATTRIBUTES.finditer(line):
            yield number, "attribute", match.group(1)


def main() -> int:
    problems = []
    for path in sorted(SOURCES.rglob("*.kt")):
        problems += [(path, *found) for found in scan_kotlin(path)]
    for path in sorted(RESOURCES.rglob("*.xml")) + [MANIFEST]:
        if path.parent.name.startswith("values"):
            continue
        problems += [(path, *found) for found in scan_xml(path)]
    for path, number, kind, text in problems:
        print(f"{path.relative_to(ROOT)}:{number}: {kind} {text} is not a string resource")
    if problems:
        print(f"\n{len(problems)} text(s) outside res/values/strings.xml. Move them there.")
        return 1
    print("All user-visible text comes from string resources.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
