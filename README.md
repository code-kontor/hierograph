# Cartograph

Cartograph lets you explore and analyze the dependency structure of Java projects using an
agentic AI. It scans your codebase with jQAssistant, builds an in-memory hierarchical model,
and exposes it via MCP (Model Context Protocol) so that Claude or other AI assistants can
answer architectural questions about your code.

## Five Questions Cartograph Answers That Your AI Can't Answer Without It

Modern AI assistants are remarkably good at reading code. They're remarkably bad at *understanding architecture* — because the architecture of a large codebase doesn't live in any one file. It lives in the relationships between thousands of files, and no amount of grepping reveals it.

Cartograph gives your AI assistant a structural map of your codebase. Here are five questions that go from *nearly impossible* to *one quick answer* the moment you plug Cartograph in.

### 1. "If I change this class, what breaks?"

*The blast-radius question. Every refactoring, every breaking API change, every "should we even touch this?" debate.*

**Without Cartograph:** Your AI greps for the class name, finds the imports, reads each importing file, recursively follows their usages, gives up at depth two, and produces an answer that's confident, plausible, and missing 60% of the actual impact. The classes that depend on this one *transitively* are invisible. The 47 places it's referenced inside annotations or generic type parameters are invisible. You walk away with false confidence.

**With Cartograph:** One tool call returns the full transitive blast radius — every module affected, grouped by depth, ranked by coupling strength, with the specific call sites for the heaviest dependencies. Your AI can tell you: *"312 nodes affected across 4 modules. The transport layer is most impacted (47 call sites). Here are the three files you'll definitely need to update."*

### 2. "Are there any layering violations in this codebase?"

*The architectural-integrity question. Does the codebase actually follow the architecture the team thinks it follows?*

**Without Cartograph:** Effectively unanswerable. To check whether the `domain` layer is contaminated by `infrastructure` references, your AI would need to read every file in `domain`, parse every import, check every type reference, and somehow reason about whether the imports cross the layer boundary. It's a hundred-tool-call quest that ends in "I checked some of the files and didn't find obvious violations."

**With Cartograph:** A single directional query. *"Does `domain` depend on `infrastructure`, directly or indirectly? Yes/no, definitively."* If yes, your AI drills in and shows you the exact violations — the specific classes, methods, and call sites that cross the boundary. The kind of question architects have been asking for decades, now answerable in one chat turn.

### 3. "Where in this codebase should this new feature live?"

*The onboarding question. The one every new contributor asks and every senior engineer answers from gut feel.*

**Without Cartograph:** Your AI guesses based on file naming conventions and folder structure. It might be right; it might suggest a location that violates the actual dependency conventions of the codebase. It has no way to ask "where do similar features currently live, and what does the dependency structure tell us about where new code naturally belongs?"

**With Cartograph:** Your AI surveys the codebase's actual structure — which modules contain logic similar in shape to what you're adding, what their dependency profiles look like, which subtrees are cohesive enough to accept new code without creating coupling problems. The answer comes with reasoning grounded in the codebase's real structure, not its directory layout.

### 4. "Which parts of this codebase are most coupled, and which are candidates for extraction?"

*The decomposition question. Should we extract a library? Where? What comes with it?*

**Without Cartograph:** Possible in theory if your AI reads every file in the codebase, builds a dependency graph in its head, and reasons about it. In practice: not possible. Context windows aren't big enough; the analysis isn't tractable on the LLM side.

**With Cartograph:** Pairwise coupling analysis across any set of modules, with density metrics, cycle detection, and topological ordering. Your AI can answer questions like *"If we extract the `cache` package as a library, what would need to come with it? What cross-boundary dependencies would remain?"* with quantitative grounding. The kind of analysis that used to require a senior architect with a week and a whiteboard.

### 5. "Why does this module depend on that one?"

*The forensics question. Someone added a dependency three years ago and nobody remembers why.*

**Without Cartograph:** Your AI tells you that module A imports module B, looks at one or two files, and produces a plausible-sounding explanation that may or may not reflect the actual usage. The 23 different reasons the dependency exists — different classes, different call sites, different intentions — get collapsed into a generic summary.

**With Cartograph:** Aggregated and broken down. *"`coordination` depends on `transport` via 47 call sites, primarily concentrated in three classes: 31 calls from `ClusterCoordinator` for state replication, 11 from `LeaderElector` for vote propagation, 5 scattered uses for diagnostics."* You see what the dependency is actually for, not what the imports suggest.


## How it works

For detailed instructions, see the [Getting Started](docs/getting-started.md) guide.

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
Cartograph (MCP server)
       │
       ▼
Claude (AI assistant)
```

1. **jQAssistant** scans compiled bytecode and writes structural data into a Neo4j graph database
2. **Cartograph** loads the graph into memory and serves it as MCP tools and a REST API
3. **Claude** calls the tools to navigate, query, and reason about your project's architecture

## Quick start

For detailed instructions, see the [Cartograph Architecture Overview](docs/cartograph-architecture-overview.md) guide.

```bash
# 1. Build the project (includes jQAssistant scan)
mvn clean install

# 2. Start the Neo4j server
mvn com.buschmais.jqassistant:jqassistant-maven-plugin:2.9.1:server

# 3. Start the Cartograph MCP server (in a new terminal)
cd mcp-spike/core-app
mvn spring-boot:run

# 4. Register with Claude Code
claude mcp add cartograph --transport streamable-http http://localhost:8080/mcp
```

Then ask Claude things like *"Give me an overview of the project structure"* or
*"What is the blast radius of class X?"*.

## Documentation

- [Getting Started](docs/getting-started.md) -- step-by-step setup guide
- [Architecture Overview](docs/cartograph-architecture-overview.md) -- how the pieces fit together
- [REST API](docs/rest-api.md) -- HTTP endpoints reference

## Requirements

- Java 21+
- Maven 3.9+
- Claude Code CLI (for MCP integration)
