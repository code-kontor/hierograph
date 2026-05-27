# Hierograph review — `adapter-config-core` extraction

A candid assessment of how useful the hierograph dependency graph was while planning the
extraction of `adapter-config-core` out of `adapter-common`.

**Verdict: substantially helpful — but only for the architectural questions, not the mechanics.**

---

## Where it was genuinely the right tool (hard to do otherwise)

- **Finding the cycle.** The make-or-break insight — that the target set and the rest of
  `adapter-common` depend on *each other* — came straight out of `aggregated_dependencies` run in
  both directions, with weights and `is_extends` / `is_implements` flags. That is transitive graph
  reasoning that grep cannot give.
- **Proving the closure was bounded.** Walking `outgoing_dependencies` per class showed the
  drag-along set is finite and pinned down exactly which types are forced along
  (`KeyAndCertificateUtil`, the `Secret` serializers, `AbstractYamlBasedConfigurationServiceImpl`,
  etc.). Hand-tracing imports for this would have been slow and easy to get wrong.
- **Module-level direction + external footprint.** Confirming the moved set touches only
  `common-utils` among Example modules, and which way the edges point, gave confidence the split would
  be acyclic.
- **The `extends` / `implements` attributes** distinguished *structural* back-edges (must move) from
  incidental ones — this directly shaped the plan.

## Where it did not help and I fell back to other tools

- **Refactor mechanics.** Hierograph reported that an edge to `LocalSecretsService` existed and its
  weight, but not that it was a `new LocalSecretsService()` fallback vs. a
  `@JsonSerialize(using = …)` annotation vs. a field type. Reading the source was required for all of
  that.
- **Constructor-overload questions** (e.g. who calls the `(Class, String, List)` convenience
  constructor) — the graph is type / method granular, not overload-level, so this was grep + reading
  source.
- **POM / external libraries.** It only models Example modules, so Jackson / IAIK / SLF4J were invisible.
  Deciding the new module's dependencies (e.g. IAIK crypto vs. plain JDK) is something hierograph
  fundamentally cannot answer — that needed `cat | grep` over the moved files.

## The cost

The depth-1 `affected_by` calls returned very large payloads (89 / 76 / 71 dependent nodes). The
useful part was the `by_parent_module` summary; pulling the full node lists was mostly noise. Capping
result size and leaning on the summaries would have been cheaper — that is where much of the session's
volume went.

## Did it ever mislead me?

No. Every edge it reported was later confirmed when I read the actual source — the `LocalFileService`
fallback, the `Secret` serializer annotations, and the `KeyAndCertificateUtil` usage all matched.
Accuracy was good; it simply stops at the Example-module boundary and at *"an edge exists"* rather than
*"here is the code."*

## Net

Hierograph turned the genuinely hard question — *"is this extraction feasible, and what is the true
blast radius?"* — into a handful of accurate queries. It does not replace opening the files for the
*how*, and it is blind to third-party dependencies, but for the architectural shape of the refactor it
did the heavy lifting.
