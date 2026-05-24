# Getting Started with Hierograph

Hierograph is an MCP-based tool that lets you explore and analyze the dependency structure of your
Java project using an agentic AI. It uses jQAssistant to scan your codebase into a Neo4j graph
database, then exposes that graph via an MCP server that Claude can query.

This guide covers Maven-based projects. For a deeper look at how the pieces fit together, see the
[Architecture Overview](hierograph-architecture-overview.md).

## Prerequisites

- Java 21+
- Maven 3.9+
- Claude Code CLI installed

## Step 1: Checkout your Maven project

```bash
git clone <your-repo-url>
cd <your-project>
```

If you already have the project checked out, make sure you're on the branch you want to analyze.

> **Note:** Hierograph analyzes compiled bytecode, not source code. The project must build
> successfully (`mvn clean install`) before it can be scanned.

## Step 2: Add jQAssistant to the build

### 2.1 Add the jQAssistant Maven plugin

Add the following plugin to your parent POM (or the POM of the module you want to scan):

```xml
<properties>
    <jqassistant.version>2.9.1</jqassistant.version>
</properties>

<build>
    <plugins>
        <plugin>
            <groupId>com.buschmais.jqassistant</groupId>
            <artifactId>jqassistant-maven-plugin</artifactId>
            <version>${jqassistant.version}</version>
            <executions>
                <execution>
                    <goals>
                        <goal>scan</goal>
                        <goal>analyze</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### 2.2 Create `.jqassistant.yml`

Create a `.jqassistant.yml` file in your project root:

```yaml
jqassistant:
  store:
    uri: file:tools-example-db
  plugins:
    - group-id: com.buschmais.jqassistant.plugin
      artifact-id: java
      version: 2.9.1
    - group-id: com.buschmais.jqassistant.plugin
      artifact-id: common
      version: 2.9.1
  analyze:
    concepts:
      - java-classpath:Resolve
      - java:TypeAssignableFrom
      - java:MethodOverrides
      - java:MemberInheritedFrom
      - java:VirtualInvokes
  scan:
    reset: true
```

Key settings:
- **`store.uri`**: Where the Neo4j database is stored (relative to project root)
- **`scan.reset: true`**: Ensures a clean scan on each build
- **`java-classpath:Resolve`**: Resolves classpath dependencies for accurate analysis

> **Further reading:** For more details on configuring jQAssistant (additional plugins, custom
> rules, multi-module setups, Gradle support, etc.), see the
> [jQAssistant documentation](https://jqassistant.github.io/jqassistant/current/).

## Step 3: Run the build

```bash
mvn clean install
```

This will compile your project, scan all artifacts, and populate the Neo4j graph database at
`mcp-example-db/`.

## Step 4: Start the jQAssistant server

Start the embedded Neo4j server so the MCP server can connect to it:

```bash
mvn com.buschmais.jqassistant:jqassistant-maven-plugin:2.9.1:server
```

This starts a Neo4j Bolt endpoint at `bolt://localhost:7687`. Keep this terminal open.

You can also browse the raw graph data at `http://localhost:7474` using Neo4j's built-in browser UI.

## Step 5: Start the Hierograph MCP server

In a new terminal, start the Hierograph MCP server (Spring Boot application):

```bash
cd tools-spike/core-app
mvn spring-boot:run
```

The server starts with these defaults (configured in `application.properties`):

```properties
spring.ai.mcp.server.name=slizaa-graph
spring.ai.mcp.server.protocol=STREAMABLE
slizaa.bolt.uri=bolt://localhost:7687
```

## Step 6: Add the MCP server to Claude Code

Register the Hierograph MCP server with the Claude CLI:

```bash
claude tools add hierograph --transport streamable-http http://localhost:8080/mcp
```

Verify it's connected:

```bash
claude tools list
```

You should see `hierograph` listed with its tools.

---

## Example Queries

Once everything is running, start a Claude Code session and try these queries:

### Get an overview of your project

> "Give me an overview of the project structure"

Claude will use `describe_graph` and `list_children` to show you the top-level artifacts, package
distribution, and dependency statistics.

### Find a specific class

> "Find the class UserService and show me its dependencies"

Claude will use `find_node` to locate the class, then `aggregated_outgoing` to show what it
depends on.

### Blast radius analysis

> "What is the blast radius of class PaymentProcessor?"

Claude will use `aggregated_incoming` or `affected_by` to show you every module and class that
depends on `PaymentProcessor` -- helping you understand the impact of a change.

### Build a Dependency Structure Matrix (DSM)

> "Build a DSM for the top-level modules"

Claude will use `pairwise_dependencies` to generate a matrix showing how all top-level artifacts
depend on each other -- a powerful way to spot circular dependencies and layering violations.

### Trace a dependency path

> "Is there a dependency path from module A to module B?"

Claude will use `find_dependency_path` to check transitive reachability between two nodes.

### Explore a package

> "Show me all classes in the org.example.service package and their relationships"

Claude will use `list_descendants` with a kind filter to enumerate types, then
`pairwise_dependencies` to show their internal coupling.

### Identify top consumers

> "Who is the top consumer of the Repository interface?"

Claude will use `aggregated_incoming` to rank all dependants by weight, showing you which modules
are most coupled to that interface.

### Drill into specific dependencies

> "Show me the core dependencies from module-web to module-api"

Claude will use `outgoing_core_dependencies` to list the concrete class-to-class relationships
that constitute the aggregated dependency.

---

## Tips

> **Hint:** Sometimes the AI needs a nudge to use the graph tools. If Claude starts guessing or
> reading source files instead of querying the graph, steer it with **"use hierograph"** in your
> prompt. For example:
>
> *"Use hierograph to find all classes that depend on AuthService"*
>
> This ensures Claude uses the MCP tools for structural analysis rather than doing text-based
> code search.
