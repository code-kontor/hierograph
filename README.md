# Hierograph

**Hierograph** is an MCP server that gives AI assistants a structural map of Java codebases, built on jQAssistant and Neo4j.

Your AI is great at reading code. It's terrible at understanding architecture — because architecture lives in the relationships between files, and no amount of grepping reveals it.

Hierograph gives your AI a structural map of your codebase. Six questions that go from *nearly impossible* to *one quick answer*:

### 1. "I'm new to this codebase — what's the overall structure?"

**Without Hierograph:** your AI lists folder names, summarizes README fragments, and produces an overview that mostly reflects how the code is *named*, not how it actually *works*.

**With Hierograph:** the real architecture — every module, how they depend on each other, which ones are central, which are peripheral. Not the file tree; the structure underneath it.

### 2. "If I change this class, what breaks?"

**Without Hierograph:** your AI greps, follows imports two levels deep, gives up, and confidently misses 60% of the impact.

**With Hierograph:** full transitive blast radius — every affected module, ranked by coupling, with concrete call sites. One tool call.

### 3. "Does the domain layer depend on infrastructure?"

**Without Hierograph:** effectively unanswerable. Hundreds of file reads to check imports, ending in "I didn't find obvious violations."

**With Hierograph:** yes or no, definitively. If yes — every offending call site, by line number.

### 4. "Can I move this class to another module without breaking anything?"

**Without Hierograph:** your AI reads the class, checks its imports, misses the 14 other classes that import *it*.

**With Hierograph:** every incoming and outgoing dependency, which modules they cross, and whether the move creates a new cycle. A yes/no answer with evidence.

### 5. "If I extract this package as a library, what comes with it?"

**Without Hierograph:** not tractable. The dependency graph is too big for context windows.

**With Hierograph:** the cohesive boundary, the cross-cutting dependencies, the density of internal coupling. Decisions architects used to make with a week and a whiteboard.

### 6. "Why does module A depend on module B?"

**Without Hierograph:** a plausible-sounding summary from one or two files.

**With Hierograph:** *"47 call sites across 3 classes — 31 from ClusterCoordinator for state replication, 11 from LeaderElector for vote propagation, 5 for diagnostics."* What the dependency is actually for.

---

## How it works

For detailed explanations, see the [Hierograph Architecture Overview](docs/hierograph-architecture-overview.md) guide.

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
Claude (AI assistant)
```

1. **jQAssistant** scans compiled bytecode and writes structural data into a Neo4j graph database
2. **Hierograph** loads the graph into memory and serves it as MCP tools (with a direct HTTP API for non-MCP clients)
3. **Claude** calls the tools to navigate, query, and reason about your project's architecture

## Quick start

For detailed instructions, see the [Getting Started](docs/getting-started.md) guide.

```bash
# 1. Build the project (includes jQAssistant scan)
mvn clean install

# 2. Start the Neo4j server (leave running)
mvn com.buschmais.jqassistant:jqassistant-maven-plugin:2.9.1:server

# 3. Start the Hierograph MCP server (in a new terminal)
cd mcp-spike/core-app
mvn spring-boot:run

# 4. Register with Claude Code
claude mcp add hierograph --transport streamable-http http://localhost:8080/mcp
```

Then ask Claude things like *"Give me an overview of the project structure"* or
*"What is the blast radius of class X?"*.

## Documentation

- [Getting Started](docs/getting-started.md) — step-by-step setup guide
- [Architecture Overview](docs/hierograph-architecture-overview.md) — how the pieces fit together
- [HTTP API](docs/rest-api.md) — direct HTTP endpoints for non-MCP clients

## Requirements

- Java 21+
- Maven 3.9+
- Claude Code CLI (for MCP integration)
