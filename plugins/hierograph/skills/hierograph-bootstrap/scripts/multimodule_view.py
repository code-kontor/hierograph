#!/usr/bin/env python3
"""Derive a hierograph "multi-modules" view document from a Maven reactor.

Usage:
  python3 -I multimodule_view.py <build root> [--name multi-modules] [--profile id ...] [--main-only]

Walks the root pom.xml's <modules> (and those of the profiles named with
--profile), recursively. Every aggregator below the root - a POM that lists
<modules> - becomes a group named by its artifactId; its non-aggregator modules
become members, selected by GAV (`groupId:artifactId:**`, which also takes the
module's test artifact; `:*` with --main-only). Nested aggregators become nested
groups. Modules listed directly by the root stay ungrouped: the root is the whole
reactor, and a box around everything says nothing. An aggregator with no members
and no subgroups is left out.

Prints the view document as JSON on stdout and a short summary on stderr. It only
reads the POMs; it never writes into the project.
"""
import argparse, json, os, sys
import xml.etree.ElementTree as ET

NS = "{http://maven.apache.org/POM/4.0.0}"


def child(el, tag):
    """The child `tag` of `el`, with or without the POM namespace."""
    if el is None:
        return None
    found = el.find(NS + tag)
    return found if found is not None else el.find(tag)


def text(el, tag):
    c = child(el, tag)
    return c.text.strip() if c is not None and c.text else None


def children(el, tag):
    """The children of the child `tag` of `el`; none when it is missing."""
    c = child(el, tag)
    return list(c) if c is not None else []


def module_dirs(project, profiles):
    """The <module> entries of the project and of the named profiles, in order."""
    out = [m.text.strip() for m in children(project, "modules") if m.text]
    for p in children(project, "profiles"):
        if text(p, "id") in profiles:
            out += [m.text.strip() for m in children(p, "modules") if m.text]
    return out


def read(pom_dir, parent_group, profiles, main_only, warnings):
    """(artifactId, groupId, group-or-None-for-a-leaf) for the module in `pom_dir`."""
    path = os.path.join(pom_dir, "pom.xml") if os.path.isdir(pom_dir) else pom_dir
    try:
        project = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as e:
        warnings.append(f"skipped {path}: {e}")
        return None
    artifact = text(project, "artifactId")
    group = text(project, "groupId") or text(child(project, "parent"), "groupId") or parent_group
    modules = module_dirs(project, profiles)
    if not modules:
        return artifact, group, None
    members, groups = [], []
    base = os.path.dirname(path)
    for m in modules:
        sub = read(os.path.join(base, m), group, profiles, main_only, warnings)
        if sub is None:
            continue
        sub_artifact, sub_group, sub_def = sub
        if sub_def is None:
            members.append(f"{sub_group}:{sub_artifact}:{'*' if main_only else '**'}")
        elif sub_def.get("members") or sub_def.get("groups"):
            groups.append(sub_def)
    definition = {"name": artifact}
    if members:
        definition["members"] = members
    if groups:
        definition["groups"] = groups
    return artifact, group, definition


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("build_root")
    ap.add_argument("--name", default="multi-modules")
    ap.add_argument("--profile", action="append", default=[], help="also take the <modules> of this profile")
    ap.add_argument("--main-only", action="store_true", help="select main artifacts only, not test artifacts")
    args = ap.parse_args()

    warnings = []
    root = read(args.build_root, None, set(args.profile), args.main_only, warnings)
    if root is None:
        sys.exit("no readable pom.xml in " + args.build_root)
    _, _, definition = root
    groups = (definition or {}).get("groups", [])
    document = {
        "name": args.name,
        "description": "The Maven aggregators of the reactor, derived from the POMs",
        "groups": groups,
    }
    for w in warnings:
        print("warning: " + w, file=sys.stderr)

    def count(gs):
        return sum(1 + count(g.get("groups", [])) for g in gs)

    print(f"{count(groups)} group(s); modules listed by the root stay ungrouped", file=sys.stderr)
    json.dump(document, sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
