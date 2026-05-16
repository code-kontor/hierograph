# Cartograph

Cartograph lets you explore and analyze the dependency structure of Java projects using an
agentic AI. It scans your codebase with jQAssistant, builds an in-memory hierarchical model,
and exposes it via MCP (Model Context Protocol) so that Claude or other AI assistants can
answer architectural questions about your code.

## How it works

```
Your Java project  ──>  jQAssistant (scan)  ──>  Neo4j  ──>  Cartograph (MCP server)  ──>  Claude
```

1. **jQAssistant** scans compiled bytecode and writes structural data into a Neo4j graph database
2. **Cartograph** loads the graph into memory and serves it as MCP tools and a REST API
3. **Claude** calls the tools to navigate, query, and reason about your project's architecture

## Project structure

```
slizaa-parent/              Parent POM and shared configuration
slizaa-mojos/               Custom Maven plugins
slizaa-core/                Core libraries (progress monitor, etc.)
slizaa-hierarchicalgraph/   Hierarchical graph model (EMF-based)
mcp-spike/
  core-app/                 Cartograph MCP server (Spring Boot)
  jqassistant-mapping/      jQAssistant-to-hierarchical-graph mapping
```

## Quick start

For detailed instructions, see the [Getting Started](docs/getting-started.md) guide.

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
- [EMF Features Used](docs/emf-features-used.md) -- which EMF features the hierarchical graph model uses
- [EMF Migration Assessment](docs/emf-migration-difficulty.md) -- difficulty assessment for migrating from EMF to Kotlin

## Requirements

- Java 21+
- Maven 3.9+
- Claude Code CLI (for MCP integration)
