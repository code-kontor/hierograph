# Hierograph: Architecture Overview

This document describes the four building blocks that make up a working Hierograph setup and how data flows between them.

## The picture

```
┌──────────────────────────────────────────────────────────────┐
│  Your Java project (source code, pom.xml, build artifacts)   │
└────────────────────────────┬─────────────────────────────────┘
                             │ is scanned by
                             ▼
                  ┌───────────────────────┐
                  │   jQAssistant         │
                  │   (Maven plugin)      │
                  └──────────┬────────────┘
                             │ populates
                             ▼
                  ┌───────────────────────┐
                  │   Neo4j               │
                  │   (graph database,    │
                  │    launched by        │
                  │    jQAssistant)       │
                  └──────────┬────────────┘
                             │ loaded by
                             ▼
                  ┌───────────────────────┐
                  │   Hierograph          │
                  │   (Spring Boot MCP    │
                  │    server, in-memory  │
                  │    hierarchical view) │
                  └──────────┬────────────┘
                             │ MCP / HTTP
                             ▼
                  ┌───────────────────────┐
                  │   Claude              │
                  │   (Claude Code or     │
                  │    other MCP client)  │
                  └───────────────────────┘
```

Four components, three clear handoffs: source code → graph data → hierarchical model → AI reasoning.

## 1. jQAssistant: scan the project

jQAssistant is a Maven (or Gradle) plugin that walks the project's compiled bytecode and metadata, extracts structural information, and writes it into a Neo4j database as a typed graph.

It captures classes, interfaces, methods, fields, packages, modules, the relationships between them (calls, extends, implements, references), Maven module structure, JUnit results, annotations, and much more depending on which jQAssistant plugins are active.

To use it, the project adds the jQAssistant Maven plugin to its `pom.xml` with `scan` and `analyze` goals bound to the build lifecycle, and runs:

```bash
mvn clean install
```

The build compiles the project and then jQAssistant scans the compiled classes from `target/classes` (and tests from `target/test-classes`) and populates Neo4j with nodes and relationships representing the project's structure.

**Output**: a populated Neo4j database containing every class, method, package, module, and dependency edge in the project.

**When to run**: once per significant code change. The graph reflects the state of the code at scan time; later edits aren't visible until rescanned.

## 2. Neo4j: the graph database

Neo4j stores the structural data jQAssistant produces. It's a general-purpose graph database with the Cypher query language.

For Hierograph use, the key thing is that Neo4j becomes the single source of structural truth. Anything Hierograph or any other tool needs to know about the project's structure lives here.

**How it's launched**: jQAssistant can manage a local Neo4j instance for you — `mvn com.buschmais.jqassistant:jqassistant-maven-plugin:2.9.1:server` starts it embedded, listening on the standard Neo4j ports (Bolt protocol on 7687, browser UI on 7474). You can also run a standalone Neo4j (e.g., via Docker) if preferred.

**Inspection**: while everything's running, the Neo4j browser at `http://localhost:7474` lets you write ad-hoc Cypher queries to explore the raw graph — useful for understanding what jQAssistant captured.

**Output**: a running Neo4j instance with structural data ready to be read.

## 3. Hierograph: the MCP server

Hierograph is a Spring Boot application that:

1. Connects to Neo4j at startup
2. Loads the structural data (full containment tree, all leaf-level dependencies, node IDs) into memory
3. Exposes a Model Context Protocol (MCP) server over HTTP
4. Computes hierarchical aggregations on demand

The in-memory model is what makes Hierograph fast. Once the data is loaded, all aggregation queries (which subtrees depend on which, blast radius, structural metrics) are computed against in-memory data structures rather than via repeated Cypher queries against Neo4j. That's why questions that would take seconds via raw Cypher answer in milliseconds via Hierograph.

The MCP server exposes a set of tools (currently fourteen) for navigating the hierarchical model: finding nodes by name, listing children, computing aggregated dependencies between subtrees, finding transitive blast radius, etc. Each tool is designed to be consumed by an LLM rather than a human UI — responses are structured, summary-rich, and self-describing.

In addition to MCP, Hierograph exposes the same tools as a REST API under `/api` (see [rest-api.md](rest-api.md) for details).

**How it's launched**:

```bash
cd tools-spike/core-app
mvn spring-boot:run
```

The connection to Neo4j is configured via `application.properties`:

```properties
slizaa.bolt.uri=bolt://localhost:7687
```

Hierograph reads Neo4j once at startup, populates its in-memory model, and then serves MCP requests at `http://localhost:8080/mcp` (default).

**Output**: an MCP-compatible HTTP endpoint that any compatible client can connect to.

## 4. Claude: the AI consumer

Claude — via Claude Code, Claude Desktop, or another MCP-compatible client — connects to the Hierograph MCP server and uses its tools to answer the user's architectural questions.

The user registers Hierograph once:

```bash
claude tools add hierograph --transport streamable-http http://localhost:8080/mcp
```

After that, any Claude session in any project where this is registered has access to Hierograph's tools. When the user asks an architectural question, Claude decides which tools to call, in what order, with what arguments. The structural answers come from Hierograph; Claude synthesizes them, combines them with its own reasoning, and produces the final response.

Crucially, Claude continues to have access to its other tools — file reading, web search, the full agent toolkit. Hierograph doesn't replace those; it adds a structural layer that the other tools can't provide. The combination — structural overview from Hierograph plus targeted code reading via file tools — is more powerful than either alone.

**Output**: high-quality answers to architectural questions about codebases too large to fit in an LLM's context window.

## How the pieces fit together

A typical user session, end to end:

1. **One-time setup per project**: add jQAssistant to `pom.xml`, run `mvn clean install`. The build compiles the code and jQAssistant populates the Neo4j database.

2. **Once per work session**: start Neo4j (via `mvn com.buschmais.jqassistant:jqassistant-maven-plugin:2.9.1:server` or Docker), start the Hierograph MCP server. Both stay running while the user works.

3. **In the Claude session**: the user asks an architectural question. Claude calls Hierograph tools, gets structural answers, possibly reads specific source files via its own file-reading tools for additional context, and synthesizes a response.

4. **When the code changes**: rerun `mvn clean install` and restart the Hierograph server (or use a reload mechanism, when one exists). The new data is then available to Claude.

## Separation of concerns

Each component owns a clear responsibility:

- **jQAssistant** owns *scanning* — turning source code into structural data
- **Neo4j** owns *storage* — persisting that data and providing query access
- **Hierograph** owns *aggregation and exposure* — transforming raw graph data into a hierarchical model accessible via MCP
- **Claude** owns *reasoning* — taking structural facts and producing useful answers

This separation matters. It means each component can be replaced or extended independently. A different scanner (for Python or TypeScript) could populate Neo4j with equivalent data, and Hierograph would serve it through the same MCP tools without modification. A different MCP client (Cursor, Codex, whatever comes next) could consume Hierograph's tools without Hierograph caring.

The architecture is deliberately modular along these lines because the future of each component is uncertain — scanning ecosystems evolve, AI assistants proliferate, storage backends change — and keeping the boundaries clean means each part can adapt to its own changes without breaking the others.
