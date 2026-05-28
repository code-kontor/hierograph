<p align="center">
  <img src="hierograph-logo.svg" alt="Hierograph" width="750px"/>
</p>

---

## Your AI can trace what calls what. It still can't tell you how your architecture fits together.

A capable AI assistant, given enough patience, can chase a call chain through your codebase. Tools that map function calls and imports help it do that faster — they answer *what breaks if I change this function?*

But that's not the question an architect asks. The architect asks: *how coupled are these two modules, and is it deep or broad? Does the domain layer leak into infrastructure? If I extract this package, what comes with it?* These aren't function-level questions. They live at the level of packages, modules, and subsystems — and they require aggregating thousands of individual dependencies into a picture you can actually reason about.

A flat graph of every function and call can't answer them cleanly. You need a model that understands the *hierarchy* — and can roll dependencies up to any level you ask about.

Hierograph is that model.

---

## The architecture is already there. It just isn't legible.

Hieroglyphs were carved into temple walls for thousands of years before anyone could read them. The structure was always there; what changed was the Rosetta Stone.

Your codebase is the same. The architecture is real — encoded in every import, every resolved type, every method call. But it's scattered across thousands of individual relationships, unreadable to anything that examines them one at a time.

Hierograph deciphers it. It builds a *hierarchical* model of your codebase — module, package, type, method, field — and lets your AI ask about dependencies at any altitude. Not just "what does this function call," but "what is the coupling between these two packages, and what kind of coupling is it."

Think of the flat-graph tools as a street map: every road and intersection, complete and detailed. Hierograph is a zoomable atlas — continents, countries, cities, streets — where you can ask "how connected are these two regions?" at any level of zoom, and get an answer that aggregates everything beneath.

---

## Six questions your AI couldn't answer yesterday

### 1. "I'm new to this codebase — what's the overall structure?"

***Without Hierograph:*** a tour of folder names.

***With:*** the real architecture, every module ranked by centrality.

### 2. "If I change this class, what breaks?"

***Without:*** confident guesses that miss 60% of the impact.

***With:*** full transitive blast radius, every call site, every cycle.

### 3. "Does the domain layer depend on infrastructure?"

***Without:*** hundreds of file reads ending in "I didn't find obvious violations."

***With:*** yes or no, definitively.

### 4. "Can I move this class without breaking anything?"

***Without:*** an answer that misses the 14 classes that import it.

***With:*** every dependency, every boundary, every new cycle.

### 5. "If I extract this package as a library, what comes with it?"

***Without:*** not tractable — the graph is too big for context.

***With:*** the cohesive boundary, the cross-cutting dependencies.

### 6. "Why does module A depend on module B?"

***Without:*** a plausible summary from one file.

***With:*** *"47 call sites across 3 classes — 31 for state replication, 11 for vote propagation, 5 for diagnostics."*

---

## How it works

```
Your Java project
       │
       ▼
jQAssistant (scan)
       │
       ▼
Neo4j (graph database)
       │
       ▼
Hierograph (MCP server)
       │
       ▼
Agentic AI (Claude, Cursor, …)
```

Hierograph builds on **[jQAssistant](https://jqassistant.org)** — a mature open-source tool that scans Java *bytecode* and produces a structural graph in Neo4j. Bytecode matters: where syntactic parsers see `foo.bar()` and record a call to *some* `bar`, jQAssistant knows *which* `bar`, on which resolved type, reached through which inheritance path — including across library dependencies and generics. The dependencies are resolved facts, not textual guesses.

From that graph, **Hierograph** derives a hierarchical model in memory, with dependency aggregations computable for any pair of nodes at any level — modules, packages, types, or arbitrary subtrees. It then serves this model through MCP tools designed for AI reasoning.

An **agentic AI** — Claude (via Claude Code or Claude Desktop), Cursor, or any other MCP-compatible agent — calls the tools to navigate, query, and reason about your project's architecture.

The whole model fits in memory: on a Spring Framework-sized codebase, the full hierarchy plus 115,906 type-level dependency edges load in 38 milliseconds and occupy 64 MB. Aggregation, blast-radius, and reachability queries run in microseconds — which is exactly what makes free-form, any-level aggregation practical rather than a slow database round-trip. Everything stays local; your code never leaves your machine.

For detailed explanations, see the [Hierograph Architecture Overview](docs/architecture-overview.md).

---

## Built for JVM codebases that have earned their complexity

Hierograph is not a generalist. It goes deep on the JVM rather than wide across twenty languages, and it's aimed at the engineer who needs that depth: someone working a large, long-lived Java system — Spring, Elasticsearch, a sprawling enterprise monolith — who needs to reason about structure, coupling, and architectural integrity, not just trace a single function.

If that's you, the things a generalist treats as edge cases — resolved generic types, deep inheritance hierarchies, annotation-driven wiring — are exactly the things Hierograph gets right, because jQAssistant gets them right.

---

## Five minutes to try it

Install Hierograph. Point it at a Java codebase you know well. Register it with your agentic AI of choice. Then ask it *"what's the most fragile coupling in this codebase?"* — or any architectural question you've always wished it could answer.

The first answer tells you whether this is the missing piece.

Hierograph's core is scanner-agnostic: it derives its hierarchical model from any structural graph, and jQAssistant provides that for the JVM today. Other languages could plug into the same model through their own scanners — but Hierograph's reason to exist is depth on the JVM, not breadth across everything.

For a detailed step-by-step description, see the [Get started](docs/getting-started.md) guide.

Open source. Self-hosted.

---

## License

Hierograph is released under the [Apache License, Version 2.0](LICENSE). Copyright 2026 Gerd Wuetherich.
