#!/usr/bin/env python3
"""Load a layered dependency graph page headless and report what a reader would see wrong.

  check_page.py <page.html> [--size WIDTHxHEIGHT]

Renders the page with Playwright (Chromium) at 1440x900, or the given size, waits
for the initial fit, and checks:

  - every node of the data is drawn,
  - the graph lies inside the canvas after the initial fit,
  - every label fits inside its node,
  - every group box contains all its members,
  - the page logs no errors.

It prints one line per problem, then a verdict. Exit code: 0 when the page passes,
1 when it doesn't, and 2 when it couldn't be checked: Playwright for Python or its
Chromium isn't installed. A 2 is not a pass; say that the page wasn't checked.

  pip install playwright && python3 -m playwright install chromium

Only the layered graph (template.html) is checked; a levelized structure map is
not.
"""
import argparse, os, sys

# Runs in the page after the initial fit. It reads the template's own globals
# (DATA, N, svg, root, view, layout), so it measures what the page drew rather than
# recomputing the layout.
PROBE = r"""
() => {
  const r = {};
  if (typeof DATA === 'undefined' || typeof layout === 'undefined') return { notLayered: true };
  r.dataNodes = N.length;
  r.visibleNodes = visible.length;
  const nodeEls = Array.from(document.querySelectorAll('#g .node'));
  r.renderedNodes = nodeEls.length;

  const c = svg.getBoundingClientRect(), g = root.getBoundingClientRect();
  r.canvas = [c.left, c.top, c.right, c.bottom].map(Math.round);
  r.graph = [g.left, g.top, g.right, g.bottom].map(Math.round);
  r.scale = +view.k.toFixed(3);
  r.labelsShownAtFit = svg.classList.contains('near');

  // Labels are hidden below a zoom threshold; show them to measure, then restore.
  const near = svg.classList.contains('near');
  svg.classList.add('near');
  r.labels = [];
  for (const el of nodeEls) {
    const box = el.querySelector('rect').getBBox(), t = el.querySelector('text').getBBox();
    if (t.x < box.x - 0.5 || t.x + t.width > box.x + box.width + 0.5)
      r.labels.push([N[+el.dataset.i].n, Math.round(t.width), Math.round(box.width)]);
  }
  svg.classList.toggle('near', near);

  // Group boxes are drawn in layout.groups order.
  const boxes = Array.from(document.querySelectorAll('#g .groupbox'));
  r.groups = [];
  layout.groups.forEach((grp, i) => {
    const b = boxes[i] && boxes[i].getBBox();
    const name = (DATA.groups || [])[grp.g - 1] || ('group ' + grp.g);
    if (!b) { r.groups.push([name, 'no box drawn', []]); return; }
    const outside = [];
    for (const el of nodeEls) {
      const n = N[+el.dataset.i];
      if (n.g !== grp.g) continue;
      const m = el.querySelector('rect').getBBox();
      if (m.x < b.x - 0.5 || m.y < b.y - 0.5 || m.x + m.width > b.x + b.width + 0.5 || m.y + m.height > b.y + b.height + 0.5)
        outside.push(n.n);
    }
    if (outside.length) r.groups.push([name, 'members outside the box', outside]);
  });
  return r;
}
"""

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("page")
    ap.add_argument("--size", default="1440x900")
    args = ap.parse_args()
    width, height = (int(v) for v in args.size.lower().split("x"))
    if not os.path.isfile(args.page):
        sys.exit(f"no such file: {args.page}")

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("SKIPPED: Playwright for Python is not installed, so the page was NOT checked.\n"
              "  pip install playwright && python3 -m playwright install chromium")
        sys.exit(2)

    errors = []
    with sync_playwright() as pw:
        try:
            browser = pw.chromium.launch()
        except Exception as e:
            print(f"SKIPPED: Chromium for Playwright couldn't start, so the page was NOT checked.\n"
                  f"  python3 -m playwright install chromium\n  ({str(e).splitlines()[0]})")
            sys.exit(2)
        page = browser.new_page(viewport={"width": width, "height": height})
        page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto("file://" + os.path.abspath(args.page))
        page.wait_for_load_state("load")
        # The page fits on the next animation frame and again after a resize settles (60 ms).
        page.wait_for_timeout(400)
        r = page.evaluate(PROBE) if not errors else None
        browser.close()

    problems = []
    if errors:
        problems += [f"console error: {e}" for e in errors]
    elif r.get("notLayered"):
        sys.exit(f"{args.page} is not a layered dependency graph page")
    else:
        if r["renderedNodes"] != r["visibleNodes"] or r["visibleNodes"] != r["dataNodes"]:
            problems.append(f"{r['renderedNodes']} nodes drawn, {r['dataNodes']} in the data")
        cl, ct, cr, cb = r["canvas"]
        gl, gt, gr, gb = r["graph"]
        if gl < cl - 1 or gt < ct - 1 or gr > cr + 1 or gb > cb + 1:
            problems.append(f"after the initial fit the graph ({gl},{gt})-({gr},{gb}) "
                            f"overflows the canvas ({cl},{ct})-({cr},{cb})")
        if gr - gl < 1 or gb - gt < 1:
            problems.append("the graph has no size after the initial fit")
        for name, tw, bw in r["labels"]:
            problems.append(f"label '{name}' is wider than its node ({tw} > {bw})")
        for name, what, members in r["groups"]:
            shown = ", ".join(members[:5]) + (f" and {len(members) - 5} more" if len(members) > 5 else "")
            problems.append(f"group '{name}': {what}" + (f": {shown}" if members else ""))

    for p in problems:
        print("  " + p)
    if r and not r.get("notLayered"):
        print(f"  {r['renderedNodes']} nodes drawn at scale {r['scale']}; labels "
              + ("shown" if r["labelsShownAtFit"] else "hidden until the reader zooms in") + " at the initial fit")
    print(("FAIL: " if problems else "PASS: ") + f"{len(problems)} problem(s) in {args.page}")
    sys.exit(1 if problems else 0)

if __name__ == "__main__":
    main()
