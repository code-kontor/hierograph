#!/usr/bin/env python3
"""Turn a raw dependency edge list into the JSON the page template expects.

Input (JSON, via argv[1]) - ids may be any hashable scalar:

  {
    "title":    "Hierograph module dependencies",   # optional, becomes the <h1>
    "project":  "Hierograph 0.2.0-SNAPSHOT",        # optional meta line fragments
    "scanned":  "2026-11-07",
    "source":   "Hierograph Maven plugin",
    "unit":     "modules",                          # what a node is called in the UI
    "nodes":    {"23815330338705": "hierarchicalgraph.core.model", "5968189658062703": "javaspec", ...},
    "edges":    [[7802159431405318, 23815330338705, 385], ...],  # [from, to, weight]
    "test_patterns": ["itest", "-test"]             # substring match -> dashed outline
  }

Output: graphdata.json next to the input (or argv[2]).

Levels come from the longest dependency chain beneath a node, which is what puts
core code at the bottom. That is only well defined on a DAG, so cycles are
condensed first: every node in a strongly connected component shares one level
and is flagged, rather than the longest-path walk silently recursing forever.
"""
import json, sys, os
from collections import defaultdict

def sccs(ids, out):
    """Tarjan, iterative - recursion depth is a real risk on large graphs.

    Returns {node: (component_id, component_size)}. Component ids come from a
    counter rather than id() of the temporary list: CPython recycles addresses
    of freed objects, so id() silently merges unrelated components, and a merged
    component turns the condensation into a cyclic graph that never terminates.
    """
    index, low, on, stack, comp = {}, {}, set(), [], {}
    counter = [0]
    next_gid = [0]
    for root in ids:
        if root in index:
            continue
        work = [(root, iter(out[root]))]
        index[root] = low[root] = counter[0]; counter[0] += 1
        stack.append(root); on.add(root)
        while work:
            node, it = work[-1]
            advanced = False
            for nxt in it:
                if nxt not in index:
                    index[nxt] = low[nxt] = counter[0]; counter[0] += 1
                    stack.append(nxt); on.add(nxt)
                    work.append((nxt, iter(out[nxt])))
                    advanced = True
                    break
                if nxt in on:
                    low[node] = min(low[node], index[nxt])
            if advanced:
                continue
            work.pop()
            if work:
                low[work[-1][0]] = min(low[work[-1][0]], low[node])
            if low[node] == index[node]:
                group = []
                while True:
                    m = stack.pop(); on.discard(m); group.append(m)
                    if m == node:
                        break
                gid = next_gid[0]; next_gid[0] += 1
                for m in group:
                    comp[m] = (gid, len(group))
    return comp

def main():
    src = sys.argv[1]
    spec = json.load(open(src))
    names = {str(k): v for k, v in spec["nodes"].items()}
    ids = list(names.keys())
    raw = [(str(a), str(b), int(w)) for a, b, w in spec["edges"]]
    edges = [(a, b, w) for a, b, w in raw if a in names and b in names and a != b]
    dropped = len(raw) - len(edges)

    out = defaultdict(list)
    inn = defaultdict(list)
    for a, b, w in edges:
        out[a].append(b); inn[b].append(a)
    for i in ids:
        out.setdefault(i, []); inn.setdefault(i, [])

    # --- real cycles in the data, reported regardless of any pinning ---
    comp = sccs(ids, out)
    groups = defaultdict(list)
    for n, (gid, size) in comp.items():
        groups[gid].append(n)
    cyclic = {n for n, (gid, size) in comp.items() if size > 1}
    n_cycles = sum(1 for g in groups.values() if len(g) > 1)

    # --- pinned groups share one level ---
    # Contract each group to a single node before assigning levels. That is what
    # forces its members onto one band while keeping everything else ordered
    # around them as tightly as the edges allow. If the contraction introduces a
    # cycle, the SCC pass below absorbs it into one level rather than looping,
    # and the edges that now run the wrong way get flagged instead of hidden.
    pins = spec.get("pin_groups", [])
    def pin_of(nm):
        # First match wins, so list order is precedence. `names` is exact and is
        # the right tool when membership comes from something the name does not
        # encode - a source folder, an owning team - where a substring pattern
        # would quietly capture the wrong modules.
        for gi, g in enumerate(pins):
            if nm in set(g.get("names", ())):
                return gi
            if any(p in nm for p in g.get("patterns", [])):
                return gi
        return None
    key = {n: (f"P{pin_of(names[n])}" if pin_of(names[n]) is not None else f"N{n}") for n in ids}
    keys = sorted(set(key.values()))
    kout = defaultdict(set)
    for a, b, _ in edges:
        if key[a] != key[b]:
            kout[key[a]].add(key[b])

    kcomp = sccs(keys, {k: sorted(kout.get(k, ())) for k in keys})
    kgroups = defaultdict(list)
    for k, (gid, _) in kcomp.items():
        kgroups[gid].append(k)
    gid_of = {k: kcomp[k][0] for k in keys}
    gout = defaultdict(set)
    for k in keys:
        for j in kout.get(k, ()):
            if gid_of[k] != gid_of[j]:
                gout[gid_of[k]].add(gid_of[j])

    memo = {}
    for g in kgroups:
        stack = [(g, False)]
        while stack:
            cur, done = stack.pop()
            if done:
                memo[cur] = max((memo[c] + 1 for c in gout.get(cur, ())), default=0)
                continue
            if cur in memo:
                continue
            stack.append((cur, True))
            for c in gout.get(cur, ()):
                if c not in memo:
                    stack.append((c, False))
    level = {n: memo[gid_of[key[n]]] for n in ids}

    # An "exclusive" group gets a band of its own: everything that is not a
    # member and had landed on that level (or above) moves up one, opening a
    # gap. Members never move, so this cannot turn a downward edge into a
    # violation - a non-member that was below stays below, and one that was
    # above stays above. It can only expose an edge that was already flat.
    for gi, g in enumerate(pins):
        if not g.get("exclusive"):
            continue
        members = [n for n in ids if key[n] == f"P{gi}"]
        if not members:
            continue
        lvl = level[members[0]]
        moved = [n for n in ids if key[n] != f"P{gi}" and level[n] >= lvl]
        for n in moved:
            level[n] += 1
        shifted = sum(1 for n in moved if level[n] - 1 == lvl)
        print(f"  '{g.get('name', gi)}' is exclusive: moved {len(moved)} node(s) up "
              f"({shifted} were sharing its band)")

    # Explicit placement. `place` overrides the computed depth and stacks a group
    # above everything else, lowest `place` nearest the rest of the graph. Use it
    # when the band's position is a statement about the architecture rather than
    # something the edges imply - "tests sit on top of everything" is true by
    # intent, not because the longest path says so. Anything that then depends
    # upward into a placed group is reported as a violation, as usual.
    placed = [(gi, g) for gi, g in enumerate(pins) if g.get("place") is not None]
    if placed:
        pkeys = {f"P{gi}" for gi, _ in placed}
        rest = [level[n] for n in ids if key[n] not in pkeys]
        base = max(rest) if rest else 0
        for rank, (gi, g) in enumerate(sorted(placed, key=lambda p: p[1]["place"]), start=1):
            for n in ids:
                if key[n] == f"P{gi}":
                    level[n] = base + rank
            print(f"  '{g.get('name', gi)}' placed at level {base + rank}")

    # name the bands that a pinned group landed on
    level_names = {}
    for gi, g in enumerate(pins):
        members = [n for n in ids if key[n] == f"P{gi}"]
        if members:
            level_names[str(level[members[0]])] = g.get("name", f"group {gi}")

    pats = spec.get("test_patterns", [])
    is_test = lambda nm: any(p in nm for p in pats)

    iw, ow, ind, outd = defaultdict(int), defaultdict(int), defaultdict(int), defaultdict(int)
    for a, b, w in edges:
        ow[a] += w; iw[b] += w; outd[a] += 1; ind[b] += 1

    # order nodes by level so the page's first barycentre pass starts sanely
    ids.sort(key=lambda n: (-level[n], names[n]))
    idx = {n: i for i, n in enumerate(ids)}

    # An edge should run strictly downward: a depends on b, so b sits lower.
    # Two different things break that, and conflating them is misleading:
    #   1 upward   - b ended up ABOVE a. A real layering violation.
    #   2 flat     - same level, unrelated nodes. Also wrong.
    #   3 internal - same level, both inside the same pinned layer. Expected:
    #                collapsing a group onto one band makes its own edges
    #                horizontal by construction. Cohesion, not a defect.
    def classify(a, b):
        if level[a] > level[b]:
            return 0
        if level[a] < level[b]:
            return 1
        return 3 if (key[a] == key[b] and key[a].startswith("P")) else 2
    viol = [(a, b, w) for a, b, w in edges if classify(a, b) in (1, 2)]
    internal = [(a, b, w) for a, b, w in edges if classify(a, b) == 3]
    n_up = sum(1 for a, b, _ in viol if classify(a, b) == 1)
    n_side = len(viol) - n_up
    violation = classify

    data = {
        "title": spec.get("title"),
        "project": spec.get("project"), "scanned": spec.get("scanned"),
        "source": spec.get("source"), "unit": spec.get("unit", "modules"),
        "maxLevel": max(level.values()) if level else 0,
        "cycles": n_cycles,
        "violations": len(viol),
        "internal": len(internal),
        "levelNames": level_names,
        "nodes": [{
            "n": names[n], "l": level[n],
            "t": 1 if is_test(names[n]) else 0,
            "c": 1 if n in cyclic else 0,
            "g": 1 if key[n].startswith("P") else 0,
            "iw": iw[n], "ow": ow[n], "id": ind[n], "od": outd[n],
        } for n in ids],
        "edges": [[idx[a], idx[b], w] + ([violation(a, b)] if violation(a, b) else [])
                  for a, b, w in edges],
    }
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(src) or ".", "graphdata.json")
    json.dump(data, open(dst, "w"), separators=(",", ":"))
    print(f"nodes {len(ids)}  edges {len(edges)}  levels {data['maxLevel'] + 1}  cycles {n_cycles}")
    for lvl, nm in sorted(level_names.items(), key=lambda kv: int(kv[0])):
        print(f"  pinned group '{nm}' -> level {lvl} "
              f"({sum(1 for n in ids if level[n] == int(lvl) and key[n].startswith('P'))} members)")
    if internal:
        by_layer = defaultdict(int)
        for a, b, _ in internal:
            by_layer[level_names.get(str(level[a]), "?")] += 1
        inside = ", ".join(f"{k} {v}" for k, v in sorted(by_layer.items()))
        print(f"  {len(internal)} edge(s) internal to a pinned layer ({inside}) - expected, not violations")
    if viol:
        print(f"  {len(viol)} layering violation(s): {n_up} upward, {n_side} flat between layers")
        for a, b, w in sorted(viol, key=lambda e: -e[2])[:8]:
            arrow = "UP  " if classify(a, b) == 1 else "FLAT"
            print(f"    {arrow} {names[a]} (L{level[a]}) -> {names[b]} (L{level[b]})  w={w}")
    else:
        print("  no layering violations - every edge still points downward")
    if dropped:
        print(f"dropped {dropped} edge(s) - self-loop or endpoint not in nodes")
    if n_cycles:
        big = sorted((g for g in groups.values() if len(g) > 1), key=len, reverse=True)
        for g in big[:3]:
            print("  cycle:", ", ".join(sorted(names[m] for m in g))[:160])
    print("wrote", dst)

if __name__ == "__main__":
    main()
