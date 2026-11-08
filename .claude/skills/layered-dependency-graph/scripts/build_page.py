#!/usr/bin/env python3
"""Inline graphdata.json into the template and emit one standalone HTML file.

  build_page.py <graphdata.json> <output.html> [template.html]

The template is a fragment: it opens with <title> and <link> lines and has no
<html>/<head>/<body> of its own, because it doubles as an artifact body. For a
file the user opens from disk those wrappers have to be added, and the charset
meta matters - without it the arrows and middots in the page render as mojibake.
"""
import io, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_TPL = os.path.join(HERE, "..", "assets", "template.html")

def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    data_path, out_path = sys.argv[1], sys.argv[2]
    tpl_path = sys.argv[3] if len(sys.argv) > 3 else DEFAULT_TPL

    tpl = io.open(tpl_path, encoding="utf-8").read()
    data = io.open(data_path, encoding="utf-8").read()
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
