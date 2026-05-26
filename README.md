<p align="center">
  <img src="hierograph-logo.svg" alt="Hierograph" width="750px"/>
</p>

---

### Your AI assistant can read every file in your codebase. So why does it still feel lost?

Watch what your AI does on an unfamiliar codebase. It opens a file. Reads it. Greps for a class name. Opens another file. Greps again. It's a junior developer on day one, hoping that enough fragments add up to understanding.

But your codebase isn't a stack of files. It's an *architecture* — a structure of dependencies, layers, and seams that lives in the relationships *between* files. No amount of reading individual files reveals it.

Your AI is great at reading code. It's terrible at understanding architecture. Hierograph closes that gap.

### The architecture is already there. Your AI just can't see it.

Hieroglyphs were carved into temple walls for thousands of years before anyone could read them. The structure was always there; what changed was the Rosetta Stone.

Your codebase is the same. The architecture is real and encoded in every import, every type reference, every method call. It's just unreadable to anything that reads one file at a time.

Hierograph builds a hierarchical map of your codebase's structure and exposes it to your AI through MCP, in shapes the AI can actually reason about. Suddenly your AI doesn't grep for callers. It asks: *which modules depend on this, ranked by coupling?* — and gets the answer in one tool call. It doesn't read fifty files to check a layering rule. It asks: *does the domain layer depend on infrastructure?* — and gets a definitive yes or no, with every offending line.

---

### Six questions your AI couldn't answer yesterday

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

### How it works

HieroGraph builds on [**jQAssistant**](https://jqassistant.org) — a mature open-source tool that scans Java bytecode and produces a flat structural graph in Neo4j. From that flat graph, HieroGraph derives a hierarchical model in memory, with dependency aggregations computable for any pair of nodes at any level (modules, packages, types, or arbitrary subtrees). It then serves this model through MCP tools designed for AI reasoning.

The whole graph fits in memory. Aggregation runs in microseconds. Everything stays local — your code never leaves your machine.

---

### Five minutes to try it

Install Hierograph. Point it at a Java codebase you know well. Register it with Claude Code. Then ask Claude *"what's the most fragile coupling in this codebase?"* — or any architectural question you've always wished it could answer.

The first answer tells you whether this is the missing piece.

Hierograph works with any graph-based structural model. Today, jQAssistant provides that for Java; additional scanners (Python, TypeScript, others) can plug into the same hierarchical model with adapted MCP tools.

Open source. Self-hosted.

[**Get started →**](docs/getting-started.md)
