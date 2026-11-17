#!/usr/bin/env python3
"""Inline graphdata.json into the template and emit one standalone HTML file.

  build_page.py <graphdata.json> <output.html> [template.html]

The template is a fragment: it opens with <title> and <link> lines and has no
<html>/<head>/<body> of its own, because it doubles as an artifact body. For a
file the user opens from disk those wrappers have to be added, and the charset
meta matters - without it the arrows and middots in the page render as mojibake.

Before it writes anything, it checks the layered graph's data: every node has
a level in range, every edge has two endpoints among the nodes and a positive
weight, and every group index names a group. A page built from broken data draws
nothing or the wrong thing without an error, so a failed check stops the build.
For the rendered page itself, see check_page.py.
"""
import io, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_TPL = os.path.join(HERE, "..", "assets", "template.html")

def check_data(data):
    """The problems in a graphdata.json, as messages; empty when there are none."""
    problems = []
    nodes, edges, groups = data.get("nodes"), data.get("edges"), data.get("groups", [])
    if not isinstance(nodes, list) or not nodes:
        return ["no nodes"]
    if not isinstance(edges, list) or not edges:
        return ["no edges - the page needs at least one dependency to draw"]
    max_level = data.get("maxLevel", 0)
    for i, n in enumerate(nodes):
        name = n.get("n")
        if not isinstance(name, str) or not name:
            problems.append(f"node {i} has no name")
        lvl = n.get("l")
        if not isinstance(lvl, int) or not 0 <= lvl <= max_level:
            problems.append(f"node {i} ({name}) has level {lvl!r}, outside 0..{max_level}")
        g = n.get("g", 0)
        if not isinstance(g, int) or not 0 <= g <= len(groups):
            problems.append(f"node {i} ({name}) is in group {g!r}, but there are {len(groups)} group(s)")
    for j, e in enumerate(edges):
        if not isinstance(e, list) or len(e) < 3:
            problems.append(f"edge {j} is not [from, to, weight]: {e!r}")
            continue
        a, b, w = e[:3]
        for end in (a, b):
            if not isinstance(end, int) or not 0 <= end < len(nodes):
                problems.append(f"edge {j} has endpoint {end!r}, not a node index")
        if not isinstance(w, (int, float)) or w <= 0:
            problems.append(f"edge {j} has weight {w!r}")
    # Groups without a member draw nothing, which is harmless; a name nobody can see is still worth a warning.
    used = {n.get("g", 0) for n in nodes}
    for gi, name in enumerate(groups, start=1):
        if gi not in used:
            print(f"  note: group '{name}' has no member")
    return problems

def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    data_path, out_path = sys.argv[1], sys.argv[2]
    tpl_path = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_TPL

    tpl = io.open(tpl_path, encoding="utf-8").read()
    data = io.open(data_path, encoding="utf-8").read()
    parsed = json.loads(data)
    # The layered graph's data; a levelized map (lsm-template.html) has a shape of its own.
    problems = check_data(parsed) if "maxLevel" in parsed else []
    if problems:
        for p in problems[:20]:
            print("  " + p)
        if len(problems) > 20:
            print(f"  ... and {len(problems) - 20} more")
        sys.exit(f"{data_path}: {len(problems)} problem(s) in the data; no page written. "
                 f"Rerun make_data.py rather than editing the file.")
    if "__DATA__" not in tpl:
        sys.exit("template has no __DATA__ placeholder")
    body = tpl.replace("__DATA__", data)

    lines = body.split("\n")
    head, rest = [], lines
    for i, l in enumerate(lines):
        if l.startswith("<title>") or l.startswith("<link "):
            head.append(l)
        else:
            rest = lines[i:]
            break

    doc = (
        '<!doctype html>\n<html lang="en">\n<head>\n'
        '<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
        + "\n".join(head) + "\n"
        "<style>\n"
        "  :root { color-scheme: light dark; }\n"
        "  html, body { margin: 0; }\n"
        "  img { max-width: 100%; }\n"
        "  [hidden] { display: none !important; }\n"
        "</style>\n</head>\n<body>\n"
        + "\n".join(rest) +
        "\n</body>\n</html>\n"
    )
    io.open(out_path, "w", encoding="utf-8").write(doc)
    print("wrote", out_path, len(doc), "bytes")

if __name__ == "__main__":
    main()
