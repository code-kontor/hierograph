# Hierograph feature feedback

Prioritized improvement ideas, grounded in the friction encountered while planning the
`adapter-config-core` extraction out of `adapter-common`. See also [hierograph-review.md](hierograph-review.md)
for the usefulness assessment of the *current* feature set.

---

## The one feature to build first: a "module cut" / extraction primitive

The single highest-leverage gap. The entire planning workflow was: *take an arbitrary set of types →
run `aggregated_dependencies` in both directions → manually subtract intra-set edges → reason about
whether a clean cut exists → walk `outgoing_dependencies` per class to find the closure.* That is a lot
of manual graph bookkeeping for a question with one right answer.

A single operation — **`propose_module(node_set)`** — that returns:

1. **Violating back-edges** — edges from the set into its old home that would break acyclicity (the cycle).
2. **The minimal closure** — the additional nodes that must be pulled along to make the cut acyclic
   (it computed `KeyAndCertificateUtil`, the `Secret` serializers, `AbstractYamlBasedConfigurationServiceImpl`,
   etc. — all derived by hand this session).
3. **The resulting external dependency set** — what the new module would depend on after the cut.

`pairwise_dependencies` already does DSM / SCC / cycle detection *among given subtrees* — but not "here is
an arbitrary set of types, what is the clean way to carve it out." That is the missing primitive, and it
is exactly the part where the graph genuinely beats grep.

### Handling closure explosion: pull vs. break vs. sink

This session was the easy case — the pull-closure happened to be finite (~14 types), so "move the whole
closure" terminated. That is **not** general. The moment a back-edge lands on a *foundational* type, naive
"pull the closure" swallows the system. So the primitive cannot just compute a transitive closure and move
it; it needs a per-edge strategy.

**The insight that bounds it.** Pulling a type `T` down is cycle-safe with respect to everything that
*uses* `T` (those become allowed `old → new` edges). The only thing that re-creates a cycle is `T`'s own
outgoing edges *back into* the old module. So the closure that matters is not "everyone who touches `T`":

```
pull_closure(T) = T ∪ { T's dependencies that land back in (OldModule − MovedSet) }, recursively
```

A *foundational* type is exactly one whose `pull_closure` is huge; a *leaf value type* (e.g. `Secret`) has
an empty back-closure and is free to pull. That is the signal the algorithm keys on.

**Three moves per back-edge `S → T`** (not one):

1. **Pull `T`** — only if `pull_closure(T)` is small / self-contained.
2. **Leave `T`, break the edge by refactor** — when `T` should stay and the edge is cheap to sever.
3. **Sink `T` into a *third*, shared base module** — when `T` genuinely belongs to neither side. Instead of
   choosing new-vs-old you go *down*: extract a lower kernel both depend on. The escape hatch when (1)
   explodes and (2) is expensive.

**Which move — the heuristic.** Greedy closure-growth under a budget, preferring to cut at the cheapest
seams:

- **Bounded closure / budget** — compute `pull_closure(T)`; if it exceeds a size threshold or crosses a
  flagged hub, forbid pull and force break/sink.
- **Edge kind → cheapest break:**

  | back-edge kind | cheapest break |
  |---|---|
  | `implements` / `extends` an interface `T` | **DIP** — move the interface down, leave the impl (`ISecretsService` moved, `LocalSecretsService` stayed) |
  | `new ConcreteT()`, trivial `T` | **inline** (`new LocalSecretsService()` → `s -> s`) |
  | `new ConcreteT()`, non-trivial | **extract interface + inject**, or **relocate** the construction to a staying layer (the `LocalFileService` ctor pushed into the subclasses) |
  | `T` used as field / param **data type** | usually **must pull** `T`, or **sink** to a shared base |
  | annotation `using = T.class` | **pull `T`** or rewire the registration |
  | static util call | **pull** if leaf, else **duplicate** the small function |

- **Hub / fan-out detection** — high out-degree back into the old module ⇒ foundational ⇒ a "stay" anchor:
  never pull, always break or sink.
- **Cohesion** — if `T` is in the same conceptual cluster as `S` (the PKI types), bias toward pull even at
  moderate cost; if cross-cutting (logging, IO), bias toward break/sink.

**What `propose_module` should therefore emit** (not a silent auto-decision):

1. the **forced** pull-set — edges with no reasonable break (abstract supertypes, value types);
2. the **contested** edges, each annotated with `pull_closure` size, edge kind, and the **suggested break
   technique + cost**;
3. a **default frontier** = the minimum-cost acyclic cut, with explosions flagged (*"pulling `T` drags in
   137 types → break instead"*).

The human picks; the tool does the closure math and proposes the seams. Formally this is constrained
cycle-breaking / weighted graph partitioning — NP-hard to optimize exactly, which is *why* it is a
heuristic + human-in-the-loop, not a solver. This session was simply small enough that the contested set
was two edges and the technique was obvious.

Closely related and almost as valuable: **treat an arbitrary node-set as a virtual unit.** Every tool
takes a single node or a flat list; the extraction "unit" is a set that is not a subtree, so the session
involved passing big ID lists everywhere and mentally filtering edges that point back into the moved set.
Letting a caller name a group ("proposed module = these N nodes") and run `affected_by` / `outgoing`
*relative to the group* (intra-group edges treated as internal) would remove most of that bookkeeping.

### Implementation note: virtual units without doubling the API

The obvious worry is that a node-set parameter would change the signature of every traversal tool (or
force a parallel `_group` copy of each). It does not have to — **the trick is to make the group look like
a node**, so it slots into the id slot every tool already has.

*Why it works.* The tools already accept a polymorphic id ("module, package, or type") and "expand
higher-level inputs internally" — so they already operate on a node-set; today the only nameable sets are
*subtrees*. A subtree is just a contiguous membership mask. A virtual unit generalizes that one concept —
**from "subtree membership" to "arbitrary membership"** — and everything downstream is unchanged.

*The mechanism.* Add exactly one small tool, `create_group(name, node_ids) → group_id`, that returns a
synthetic id from a **reserved range that cannot collide with real nodes** (negative integers are ideal:
real ids are large positives, the schema is already `int64`). The parameter type stays `integer`, so no
existing signature changes:

```
create_group("cc-candidate", [5631014, 5630403, 5630709, ...]) → -1001

affected_by(-1001, "incoming", 1)        # unchanged signature
outgoing_dependencies(-1001, 5625164)     # unchanged signature
aggregated_dependencies([-1001], [25, 7]) # group is just one element of the list
```

Server-side, resolving `-1001` expands to the member set instead of a subtree — the same code path,
a different membership source.

*The one semantic that makes it a "unit".* Suppress intra-group edges: when both endpoints ∈ the group the
edge is *internal* and is not reported (`!(from ∈ G && to ∈ G)`). This is the same masking the engine
already applies implicitly for a subtree — so, again, generalized behavior, not new behavior. This is
exactly the hand-filtering that was done manually on every result this session.

*Cost.* The only genuinely new concern is statefulness — groups are session-scoped handles, so you need
`create_group` / optional `drop_group` / `list_groups` plus a TTL. That is ~3 small tools, versus
group-aware variants of ~7 traversal tools.

*How it relates to `propose_module`.* The cut primitive becomes **sugar over this**: create a transient
group, run the standard back-edge + closure + external-dep queries against it, return the rolled-up answer.
`create_group` makes *every* existing tool group-capable; `propose_module` packages the common extraction
recipe — both for roughly the cost of one new concept.

Two alternatives were considered and rejected: **overloading the id param to `integer | array`** (avoids
new tools but changes every schema and collides semantically with tools where a list already means
"cross-product set", e.g. `aggregated_dependencies`); and an **ambient `set_focus()` scope** (zero
signature change but hidden global state that is easy to forget is set). The synthetic-group-id approach is
the only one that is zero-signature-change, unambiguous, and composes with the existing list-taking tools.

```
# today — manual filtering
aggregated_dependencies([id1..id15], [5625164])   # read result, mentally discard every
                                                  # edge whose source is also one of my 15
# with a group — intra-group edges already gone
create_group("cc-candidate", [id1..id15]) → -1001
outgoing_dependencies(-1001, 5625164)
```

---

## The rest, ranked by how often the pain showed up

### 2. A type→detail drill-through to the edge's location
The recurring gap was "an edge exists" vs. "here is the code." This is fundamentally a **detail-edge**
matter: a type-level edge is an *aggregate* — its `weight` may roll up several references across different
lines — so it cannot carry one canonical location; the source position only exists at method/field
granularity, on the detail edges.

To be clear about scope: **hierograph returns a *location* (`file:line`), not the source text** — and that
is the right boundary; it indexes the graph, not the source. Producing the actual construct is the
caller's job, but with an exact `file:line` that is a single targeted read, not a scroll/grep. So this
item is **not** "make hierograph return snippets." It is one ergonomic ask:

> **Let a caller drill from a type-level edge straight to its underlying detail edges (with their
> `file:line`), without re-deriving and re-entering both endpoint ids.**

The friction was being forced to choose between *fast-but-locationless* (type level) and a *separate,
manually reconstructed* detail query.

**The workflow today** — for the `TimeLimitedKeystore → SecretJsonSerializer` coupling:

```
1. outgoing_dependencies(TimeLimitedKeystore, adapter-common)      # type level (default)
     → edge to SecretJsonSerializer, weight 1      ← "something references it — but what?"
2. outgoing_dependencies(5630709, 5630588, detail_level="detail")  # must re-type BOTH ids by hand
     → field TimeLimitedKeystore.password → SecretJsonSerializer
       annotated_by(@JsonSerialize)  @ TimeLimitedKeystore.java:23
3. open TimeLimitedKeystore.java:23  → @JsonSerialize(using = SecretJsonSerializer.class)
```

Step 2 already returns the location; the friction is purely that you must re-specify `5630709` /
`5630588` even though you were just looking at that exact edge.

**The workflow it should be** — the detail expansion reachable straight from the edge object:

```
> expand_edge(from = TimeLimitedKeystore, to = SecretJsonSerializer)

field TimeLimitedKeystore.password → SecretJsonSerializer
  annotated_by(@JsonSerialize)   @ TimeLimitedKeystore.java:23
```

Same underlying detail query, same `file:line` — the only difference is it is reachable *from the type
edge* (no manual lookup of `5630709` / `5630588`). From there, one read of `TimeLimitedKeystore.java:23`
gets the construct; hierograph is not expected to produce it. (Renaming `annotated_by` to something that
actually names the `using = X.class` reference is the separate concern in
[item 3](#3-annotation-argument--class-literal-references-as-a-first-class-relationship).)

### 3. Annotation-argument & class-literal references as a first-class relationship
`is_annotated_by` means "annotated *by* an annotation type" — but the thing that made Refactor 2 tricky was
`@JsonSerialize(using = SecretJsonSerializer.class)`: a *class literal inside an annotation argument*. That
is a distinct, refactor-critical dependency kind currently collapsed into the generic `is_depends_on_other`
bucket. Surfacing `X.class` references (and `Class<?>` literals) explicitly would have flagged the
serializer coupling immediately instead of it being discovered by reading source.

**Example.** Three moved DTOs couple to the (potentially-staying) serializers purely through annotation
arguments:

```java
// TimeLimitedKeystore / TimeLimitedPrivateKey / TimeLimitedKeystoreCertificatePair
@JsonDeserialize(using = SecretJsonDeserializer.class)   // class literal in annotation arg
@JsonSerialize(using = SecretJsonSerializer.class)       // class literal in annotation arg
private Secret password;
```

Hierograph reported `is_annotated_by: false` for these edges (the field is not *annotated by*
`SecretJsonSerializer`; it is annotated by `@JsonSerialize`, which merely *references* it) — so the edge
landed in `is_depends_on_other` and looked like any other weak dependency. A dedicated
`annotation_value_class` relationship would have made "these three classes pin the serializers in place"
visible at a glance, which is precisely the fact that decided whether the serializers could stay behind.

### 4. Summary-only mode
The depth-1 `affected_by` calls returned 70–90 full node objects when only the `by_parent_module` counts
were wanted. An option to return just the grouped summaries (no node array) — or a default cap with opt-in
detail — would cut the payload by ~95%. This was the biggest cost driver of the session.

**Example.** `affected_by(participants pkg, incoming, depth 1)` returned **89** full node objects
(qualified names, kinds, member counts, `via` edges — hundreds of lines). The only part used to size the
refactor was the trailing summary:

```
by_parent_module: adapter-config 40, adapter-common 11, sftp-uenbbis-inbound 5,
                  sftp-uenbbis-outbound 5, itest 5, email-inbound 4, rest-inbound 4, ...
```

A `summary_only: true` flag returning just that block (≈8 lines instead of ~600) would have answered the
question — "which modules, how many files" — at a fraction of the payload.

### 5. Coarse external-dependency visibility
The graph is blind past the Example module boundary, so the POM / third-party question (IAIK vs. JDK crypto,
which Jackson artifacts) was pure grep. Even modeling external package roots as leaf nodes
(`com.fasterxml.jackson`, `iaik`, `org.slf4j`) would let it answer "what third-party deps does this set
need" — directly what the new module's POM is written from.

**Example.** Writing the `adapter-config-core` POM required knowing the moved set uses **IAIK** crypto, not
just the JDK. Hierograph showed no external edges at all; a `cat | grep '^import'` over the moved files
revealed:

```
iaik.security.provider.IAIK
iaik.x509.CertificateFactory
iaik.x509.X509Certificate
com.fasterxml.jackson.databind.ObjectMapper
com.fasterxml.jackson.dataformat.yaml.YAMLFactory
org.slf4j.Logger
```

If hierograph modeled external package roots as leaf nodes, `outgoing_dependencies(moved-set →
externals)` could have produced that list — and the IAIK vs. JDK distinction (which determines whether the
new POM needs the `at.tugraz.iaik.jce` dependencies) — without leaving the tool.

### 6. Method / overload-level `affected_by`
It was not possible to ask "who calls *this specific constructor overload*" (the `(Class, String, List)`
one) — type-level expansion cannot distinguish overloads, so it fell back to grep. A method-level
reverse-reachability would close this.

**Example.** `ExampleConfigurationServiceImpl` has three constructors; only one matters for Refactor 2 — the
one that instantiates the staying `LocalFileService`:

```java
ExampleConfigurationServiceImpl(Class<C> type, String basePath, List<...> i)          // ← the one to find
ExampleConfigurationServiceImpl(Class<C> type, IFileService fileService, List<...> i) // fine, injected
ExampleConfigurationServiceImpl(ISecretsService s, Class<C> t, IFileService f, List<...> i)
```

`affected_by(ExampleConfigurationServiceImpl, incoming)` lumps together everyone touching the *class*.
Identifying the 8 subclasses whose `super(type, basePath, i)` resolves to that *specific overload* required
grepping for `super(.*basePath.*)`. A `affected_by(<method-id of that constructor>)` would have returned
exactly those 8 call sites.

### 7. Reflective / string-FQN reference detection
Spring `@ComponentScan`, `spring.factories`, `@JsonSubTypes`, string-literal FQNs — invisible to a static
type graph, yet they are the *exact* risks a package rename introduces (the whole "Risks" section of the
plan). Flagging string-literal and annotation-config references to nodes would de-risk renames specifically.

**Example.** The plan renames `de.example.test.config` → `de.example.configcore`. The
compiler catches every `import`, but it is silent on references that live in *strings or annotation
metadata*, e.g.:

```java
@ComponentScan("de.example.test.config")          // string — compiles, breaks at runtime
```
```
# spring.factories / META-INF
org.x.Y=de.example.test.config.ExampleConfigurationServiceImpl   // breaks silently
```

None of these appear as graph edges, so neither hierograph nor `javac` flags them — they surface only at
runtime. A query like "all string-literal / annotation-config occurrences of node `X`'s FQN" would turn
that blind spot into a checklist before the rename.

---

## If only one ships

**#1 (the module-cut primitive).** It compresses the genuinely hard, graph-shaped part of this whole task
into a single call, and it is the use case where hierograph's reasoning is irreplaceable. **#2** (the
type→detail drill-through) and **#4** (summary-only mode) are the cheap ergonomics wins worth bundling
alongside it.
