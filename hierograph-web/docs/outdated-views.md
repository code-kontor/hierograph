# Outdated Views

These views were removed from `hierograph-web`. The focus going forward is on
DSM and the Cross-Reference Explorer. Their history is still available via
`git log`, searchable by the commit title cited below; the code can be
restored from git history if needed.

## Cross References (`/cross-references`)

A subject-centric view. Left: full hierarchy tree (`HierarchyTree`); the
selected/focused nodes form the "subject". Right: two trees — "Used by"
(incoming: who depends on the subject) and "Uses" (outgoing: what the subject
depends on), each with an aggregated dependency weight per node. A partner
node can be promoted to the new subject via "→ as subject"; clicking opens
the dependency pair in the bottom `DependencyDetailsPane`. Multi-subject sets
were supported.

## Cross-Reference (Prototype) (`/xref`)

An experimental three-column view (Left · references → Center · referenced
by → Right). The center tree was freely explorable; its selection filtered
Left/Right to the referencing/referenced nodes; conversely a Left/Right
selection marked the affected center nodes. An "Inspect" button showed the
(source, target) pair in the `DependencyDetailsPane`. An exploratory
precursor, superseded by the Cross-Reference Explorer.

## Removal commit

Title: `Remove Cross References view and xref prototype (#97)`
Date: 2026-07-09
