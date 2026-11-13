#!/usr/bin/env python3
"""Turn a containment tree plus a dependency edge list into the data of a
levelized structure map (LSM).

  make_lsm.py <input.json> [lsmdata.json]

Input (JSON) - ids may be any hashable scalar:

  {
    "title":   "Hierograph server - levelized structure",   # optional
    "project": "Hierograph 0.3.0", "scanned": "2026-11-13", "source": "hierograph MCP",
    "expand_depth": 3,              # optional: containers deeper than this start collapsed
    "nodes": [                      # the containment tree, flat; order does not matter
      {"id": 7802, "parent": null, "name": "io.hierograph.server", "kind": "java.module"},
      {"id": 9001, "parent": 7802, "name": "io",     "kind": "java.package"},
      {"id": 9002, "parent": 9001, "name": "mcp",    "kind": "java.package"},
      {"id": "9002:types", "parent": 9002, "name": "(types)", "kind": "types",
       "ids": [9101, 9102]},        # a synthetic leaf standing for several hierograph ids
      {"id": 1, "parent": null, "name": "External Types", "kind": "external"}
    ],
    "edges": [[9101, 9002, 12], ...]  # [from id, to id, weight], from pairwise_dependencies
  }

A node whose `parent` is missing or not in the list is a root. Several roots are
put under one synthetic root named after `title`. A node with kind `external`, or
named "External Types", is drawn in the external style together with its subtree.

What the script computes, for every container separately:

  sibling edges   every input edge is charged to the one pair of siblings it
                  crosses: the children of the endpoints' lowest common ancestor
                  that contain them. That is what a container's levels are
                  about. Edges whose endpoints contain one another are dropped.
  levels          longest dependency chain beneath a sibling, on the graph with
                  every strongly connected component (a tangle) condensed. Row 0
                  is the bottom row; a sibling sits above everything it uses.
  tangles         a component of two or more siblings. Its members are ordered
                  with the Eades-Lin-Smyth heuristic (weighted), which keeps the
                  heaviest edges pointing down; the edges against that order are
                  the feedback edges and are the ones the map draws as arrows.
                  The tangle gets inner rows from the remaining downward edges.

Chains of single-child packages (io > hierograph > server) are folded into one
box named by the joined path, as a package explorer does.

Output: lsmdata.json, minified, consumed by assets/lsm-template.html.
"""
import json, os, sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_data import sccs  # noqa: E402  (one Tarjan for both renderings)


def is_external(n):
    return n.get("kind") == "external" or n.get("name") == "External Types" or n.get("style") == "external"


def eades_order(members, w):
    """Order a strongly connected set so that as much weight as possible points
    forward (from earlier to later). Greedy Eades-Lin-Smyth, weighted."""
    rest = set(members)
    out = defaultdict(dict); inn = defaultdict(dict)
    for (a, b), x in w.items():
        if a in rest and b in rest:
            out[a][b] = x; inn[b][a] = x
    head, tail = [], []
    while rest:
        changed = True
        while changed:
            changed = False
            for s in sorted(rest):
                if not any(t in rest for t in out[s]):          # sink
                    tail.insert(0, s); rest.discard(s); changed = True
            for s in sorted(rest):
                if not any(t in rest for t in inn[s]):          # source
                    head.append(s); rest.discard(s); changed = True
        if rest:
            def delta(s):
                return (sum(x for t, x in out[s].items() if t in rest)
                        - sum(x for t, x in inn[s].items() if t in rest))
            s = max(sorted(rest), key=delta)
            head.append(s); rest.discard(s)
    return head + tail


def longest_path(items, succ):
    memo = {}
    for root in items:
        stack = [(root, False)]
        while stack:
            cur, done = stack.pop()
            if done:
                memo[cur] = max((memo[c] + 1 for c in succ.get(cur, ())), default=0)
                continue
            if cur in memo:
                continue
            stack.append((cur, True))
            for c in succ.get(cur, ()):
                if c not in memo:
                    stack.append((c, False))
    return memo


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    src = sys.argv[1]
    spec = json.load(open(src, encoding="utf-8"))

    # ---------- tree ----------
    node = {}
    for raw in spec["nodes"]:
        k = str(raw["id"])
        if k in node:
            sys.exit(f"duplicate node id {k}")
        node[k] = {"key": k, "name": raw.get("name", k), "kind": raw.get("kind", ""),
                   "parent": None if raw.get("parent") is None else str(raw["parent"]),
                   "ids": [str(i) for i in raw.get("ids", [])], "ext": is_external(raw),
                   "kids": []}
    roots = [k for k, n in node.items() if n["parent"] not in node]
    if len(roots) == 1:
        root = roots[0]
        node[root]["parent"] = None
    else:
        root = "__root__"
        node[root] = {"key": root, "name": spec.get("project") or spec.get("title") or "system",
                      "kind": "root", "parent": None, "ids": [], "ext": False, "kids": []}
        for r in roots:
            node[r]["parent"] = root
    for k, n in node.items():
        if n["parent"] is not None:
            node[n["parent"]]["kids"].append(k)
    for n in node.values():
        if n["ext"]:
            stack = list(n["kids"])
            while stack:
                c = stack.pop(); node[c]["ext"] = True; stack.extend(node[c]["kids"])

    # Fold single-child package chains: io > hierograph > server -> io.hierograph.server.
    alias = {}
    def fold(k):
        n = node[k]
        while (len(n["kids"]) == 1 and not n["ids"] and n["kind"] == "java.package"
               and node[n["kids"][0]]["kind"] == "java.package" and node[n["kids"][0]]["kids"]):
            c = node.pop(n["kids"][0])
            n["name"] = f'{n["name"]}.{c["name"]}'
            n["ids"] += c["ids"]
            alias[c["key"]] = k
            for a, t in list(alias.items()):
                if t == c["key"]:
                    alias[a] = k
            n["kids"] = c["kids"]
            for g in n["kids"]:
                node[g]["parent"] = k
        for c in n["kids"]:
            fold(c)
    sys.setrecursionlimit(10000)
    fold(root)

    for n in node.values():
        n["kids"].sort(key=lambda c: (node[c]["ext"], node[c]["name"].lower()))

    owner = {}
    for k, n in node.items():
        owner[k] = k
        for i in n["ids"]:
            owner[i] = k
    owner.update(alias)

    depth = {root: 0}
    order = [root]
    for k in order:
        for c in node[k]["kids"]:
            depth[c] = depth[k] + 1; order.append(c)

    # ---------- edges ----------
    raw_edges = spec.get("edges", [])
    agg = defaultdict(int)
    unknown = 0
    for a, b, w in raw_edges:
        a, b = owner.get(str(a)), owner.get(str(b))
        if a is None or b is None:
            unknown += 1; continue
        if a != b:
            agg[(a, b)] += int(w)

    def path(k):
        p = []
        while k is not None:
            p.append(k); k = node[k]["parent"]
        return p[::-1]

    sib = defaultdict(lambda: defaultdict(int))    # container -> (childA, childB) -> weight
    pair_of = {}                                    # (a, b) -> (container, childA, childB)
    nested = 0
    for (a, b), w in agg.items():
        pa, pb = path(a), path(b)
        i = 0
        while i < len(pa) and i < len(pb) and pa[i] == pb[i]:
            i += 1
        if i == len(pa) or i == len(pb):            # one contains the other
            nested += 1; continue
        c = pa[i - 1]
        sib[c][(pa[i], pb[i])] += w
        pair_of[(a, b)] = (c, pa[i], pb[i])

    # ---------- per container: levels, tangles, feedback ----------
    layout, feedback, tangles_out = {}, set(), []
    level_of, in_tangle = {}, {}
    for c in order:
        kids = node[c]["kids"]
        if not kids:
            continue
        w = sib[c]
        succ = defaultdict(list)
        for (x, y) in w:
            succ[x].append(y)
        comp = sccs(kids, {k: sorted(succ.get(k, ())) for k in kids})
        members = defaultdict(list)
        for k, (gid, _) in comp.items():
            members[gid].append(k)
        csucc = defaultdict(set)
        for (x, y) in w:
            if comp[x][0] != comp[y][0]:
                csucc[comp[x][0]].add(comp[y][0])
        clevel = longest_path(list(members), csucc)

        rows = defaultdict(list)
        for gid, ms in members.items():
            lv = clevel[gid]
            if len(ms) == 1:
                level_of[ms[0]] = lv
                rows[lv].append(ms[0])
                continue
            seq = eades_order(ms, w)
            pos = {m: i for i, m in enumerate(seq)}
            down = defaultdict(list)
            back = []
            for (x, y), x_w in w.items():
                if x in pos and y in pos:
                    if pos[x] < pos[y]:
                        down[x].append(y)
                    else:
                        back.append((x, y, x_w)); feedback.add((c, x, y))
            inner = longest_path(ms, down)
            irows = defaultdict(list)
            for m in ms:
                irows[inner[m]].append(m)
                level_of[m] = lv; in_tangle[m] = len(tangles_out)
            tangles_out.append({"c": c, "members": ms, "back": back,
                                "rows": [sorted(irows[r], key=lambda m: node[m]["name"].lower())
                                         for r in range(max(irows), -1, -1)]})
            rows[lv].append(("T", len(tangles_out) - 1))
        def item_key(it):
            if isinstance(it, tuple):
                ms = tangles_out[it[1]]["members"]
                return (0, min(node[m]["name"].lower() for m in ms))
            return (1 if node[it]["ext"] else 0, node[it]["name"].lower())
        layout[c] = [sorted(rows[r], key=item_key) for r in range(max(rows), -1, -1)]

    # ---------- emit ----------
    idx = {k: i for i, k in enumerate(order)}
    def emit_item(it):
        if isinstance(it, tuple):
            t = tangles_out[it[1]]
            return {"t": [[idx[m] for m in r] for r in t["rows"]]}
        return idx[it]
    weight_in, weight_out = defaultdict(int), defaultdict(int)
    edges_out = []
    for (a, b), w in sorted(agg.items(), key=lambda e: -e[1]):
        if (a, b) not in pair_of:
            continue
        c, x, y = pair_of[(a, b)]
        # A feedback edge also carries the sibling pair it breaks: the arrow is
        # drawn there, between the boxes in the tangle, not between the leaves.
        v = (c, x, y) in feedback
        edges_out.append([idx[a], idx[b], w] + ([1, idx[x], idx[y]] if v else []))
        for k in path(a):
            weight_out[k] += w
        for k in path(b):
            weight_in[k] += w
    data = {
        "title": spec.get("title"), "project": spec.get("project"),
        "scanned": spec.get("scanned"), "source": spec.get("source"),
        "expand": spec.get("expand_depth"),
        "nodes": [{"n": node[k]["name"], "k": node[k]["kind"],
                   "p": idx[node[k]["parent"]] if node[k]["parent"] is not None else -1,
                   "x": 1 if node[k]["ext"] else 0,
                   "l": level_of.get(k, 0), "tg": 1 if k in in_tangle else 0,
                   "iw": weight_in[k], "ow": weight_out[k]} for k in order],
        "layout": {str(idx[c]): [[emit_item(it) for it in r] for r in rows] for c, rows in layout.items()},
        "edges": edges_out,
        "tangles": len(tangles_out),
        "violations": sum(1 for e in edges_out if len(e) > 3),
    }
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(src) or ".", "lsmdata.json")
    json.dump(data, open(dst, "w", encoding="utf-8"), separators=(",", ":"), ensure_ascii=False)

    # ---------- report ----------
    containers = [c for c in order if c in layout]
    print(f"boxes {len(order)}  containers {len(containers)}  edges {len(edges_out)}  "
          f"tangles {len(tangles_out)}  edges against the levels {data['violations']}")
    def label(it):
        if isinstance(it, tuple):
            t = tangles_out[it[1]]
            return "TANGLE{" + ", ".join(node[m]["name"] for m in sorted(t["members"])) + "}"
        return node[it]["name"]
    for c in containers[:60]:
        rows = layout[c]
        print(f"  {'  ' * depth[c]}{node[c]['name']}  ({len(rows)} rows)")
        for r, row in enumerate(rows):
            print(f"  {'  ' * depth[c]}  L{len(rows) - 1 - r}: " + ", ".join(label(it) for it in row)[:200])
    if len(containers) > 60:
        print(f"  ... {len(containers) - 60} more containers")
    for t in tangles_out:
        print(f"  tangle in {node[t['c']]['name']}: " + ", ".join(sorted(node[m]['name'] for m in t['members'])))
        for x, y, w in sorted(t["back"], key=lambda e: -e[2])[:6]:
            print(f"    against the levels: {node[x]['name']} -> {node[y]['name']}  w={w}")
    if unknown:
        print(f"dropped {unknown} edge(s) - endpoint id not in nodes")
    if nested:
        print(f"dropped {nested} edge(s) - one endpoint contains the other")
    print("wrote", dst)


if __name__ == "__main__":
    main()
