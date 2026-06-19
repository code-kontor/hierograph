# Getting Started with Hierograph

Hierograph is an MCP-based tool that lets you explore and analyze the dependency structure of your
Java project using an agentic AI. It uses jQAssistant to scan your codebase into a Neo4j graph
database, then exposes that graph via an MCP server that any MCP-compatible client can query.

This guide covers Maven-based projects. For a deeper look at how the pieces fit together, see the
[Architecture Overview](architecture-overview.md).

## Prerequisites

- Java 21+
- Maven 3.9+
- An MCP-compatible client (e.g. Claude Code, Claude Desktop, Cursor, or any other client that
  supports the Model Context Protocol over HTTP)
- The Hierograph jQAssistant rules artifact (`io.hierograph:io.hierograph.jqassistant.rules`)
  available in your local Maven repository. The `.jqassistant.yml` in [Step 2.2](#22-create-jqassistantyml)
  references this plugin, and the scan fails to resolve it otherwise. It is currently published only
  as `0.1.0-SNAPSHOT`, so build and install it from the Hierograph checkout first:
  ```bash
  git clone https://github.com/code-kontor/io.hierograph.git
  cd io.hierograph
  mvn install -DskipTests
  ```
  This places the rules plugin (and the other Hierograph artifacts) into `~/.m2`, where jQAssistant
  can find it.

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

Add the plugin to your parent POM (or the POM of the module you want to scan), inside a dedicated
profile so the scan is **opt-in** rather than part of every build:

```xml
<properties>
    <jqassistant.version>2.9.1</jqassistant.version>
</properties>

<profiles>
    <profile>
        <id>jqassistant</id>
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
    </profile>
</profiles>
```

> **Why a profile?** Scanning and analyzing add time to the build, and you typically only need to
> rescan when the code changes. Keeping jQAssistant in a dedicated profile means a normal
> `mvn clean install` stays fast, and you run the scan explicitly with `-Pjqassistant` (see Step 3).
> If you'd rather scan on every build, move the `<plugin>` block into the top-level `<build><plugins>`
> instead.

### 2.2 Create `.jqassistant.yml`

Create a `.jqassistant.yml` file in your project root:

```yaml
jqassistant:
  store:
    uri: file:mcp-example-db
  plugins:
    - group-id: com.buschmais.jqassistant.plugin
      artifact-id: java
      version: 2.9.1
    - group-id: com.buschmais.jqassistant.plugin
      artifact-id: common
      version: 2.9.1
    - group-id: io.hierograph
      artifact-id: io.hierograph.jqassistant.rules
      version: 0.1.0-SNAPSHOT
  analyze:
    groups:
      - hierograph:virtual-external
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
- **`io.hierograph.jqassistant.rules` plugin** + **`hierograph:virtual-external` group**: Hierograph's
  own rules plugin. The `hierograph:virtual-external` group materializes the virtual nodes and edges
  the MCP server expects (e.g. representing external dependencies), so the graph has the shape
  Hierograph's tools query. Both the plugin and the group are required — without them, `analyze`
  fails with *"Cannot find group"*.

> **Further reading:** For more details on configuring jQAssistant (additional plugins, custom
> rules, multi-module setups, Gradle support, etc.), see the
> [jQAssistant documentation](https://jqassistant.github.io/jqassistant/current/).

## Step 3: Run the build with the jQAssistant profile

```bash
mvn clean install -Pjqassistant
```

This will compile your project, scan all artifacts, and populate the Neo4j graph database at
`mcp-example-db/`. Activating the `jqassistant` profile is what triggers the `scan` and `analyze`
goals — a plain `mvn clean install` builds without scanning.

> **Tip:** After the first scan you only need to rerun this when your code changes. A normal build
> (without `-Pjqassistant`) leaves the existing graph in `mcp-example-db/` untouched.

## Step 4: Start the jQAssistant server

Start the embedded Neo4j server so the MCP server can connect to it:

```bash
mvn -N com.buschmais.jqassistant:jqassistant-maven-plugin:2.9.1:server
```

The `-N` (non-recursive) flag is required in multi-module projects. Without it, Maven runs the
`server` goal on every reactor module sequentially — the server starts and stops for each module
instead of staying open. `-N` restricts execution to the root module (where `.jqassistant.yml`
lives), so the server starts once and blocks as expected.

This starts a Neo4j Bolt endpoint at `bolt://localhost:7687`. Keep this terminal open.

You can also browse the raw graph data at `http://localhost:7474` using Neo4j's built-in browser UI.

## Step 5: Start the Hierograph MCP server

In a new terminal, start the Hierograph MCP server (Spring Boot application):

```bash
cd hierograph-mcp/io.hierograph.mcp.server
mvn spring-boot:run
```

The server starts with these defaults (configured in `application.properties`):

```properties
spring.ai.mcp.server.name=hierograph-graph
spring.ai.mcp.server.protocol=STREAMABLE
hierograph.bolt.uri=bolt://localhost:7687
```

## Step 6: Register the MCP server with your client

Point your MCP-compatible client at the Hierograph server. The transport is streamable HTTP and the
default endpoint is:

```
http://localhost:8080/mcp
```

How you register the server depends on the client — consult its documentation for the exact
command or configuration. Two common examples:

- **Claude Code (CLI):**
  ```bash
  claude mcp add hierograph --transport streamable-http http://localhost:8080/mcp
  ```
- **Claude Desktop / Cursor / other JSON-config clients:** add an entry like
  ```json
  {
    "mcpServers": {
      "hierograph": {
        "type": "streamable-http",
        "url": "http://localhost:8080/mcp"
      }
    }
  }
  ```

After registration, the client should list `hierograph` along with its tools.

---

## Example Queries

Once everything is running, start a session in your MCP client and try these queries:

### Get an overview of your project

> "Give me an overview of the project structure"

The client will use `graph_overview` and `list_children` to show you the top-level artifacts,
package distribution, and dependency statistics.

### Find a specific class

> "Find the class UserService and show me its dependencies"

The client will use `find_node` to locate the class, then `outgoing_dependencies` to show what it
depends on.

### Blast radius analysis

> "What is the blast radius of class PaymentProcessor?"

The client will use `incoming_dependencies` or `affected_by` to show you every module and class
that depends on `PaymentProcessor` -- helping you understand the impact of a change.

### Build a Dependency Structure Matrix (DSM)

> "Build a DSM for the top-level modules"

The client will use `pairwise_dependencies` to generate a matrix showing how all top-level
artifacts depend on each other -- a powerful way to spot circular dependencies and layering
violations.

### Trace a dependency path

> "Is there a dependency path from module A to module B?"

The client will use `find_dependency_path` to check transitive reachability between two nodes.

### Explore a package

> "Show me all classes in the org.example.service package and their relationships"

The client will use `list_descendants` with a kind filter to enumerate types, then
`pairwise_dependencies` to show their internal coupling.

### Identify top consumers

> "Who is the top consumer of the Repository interface?"

The client will use `incoming_dependencies` to rank all dependants by weight, showing you which
modules are most coupled to that interface.

### Drill into specific dependencies

> "Show me the core dependencies from module-web to module-api"

The client will use `outgoing_dependencies` to list the concrete class-to-class relationships
that constitute the aggregated dependency.

---

## Tips

> **Hint:** Sometimes the AI needs a nudge to use the graph tools. If it starts guessing or reading
> source files instead of querying the graph, steer it with **"use hierograph"** in your prompt.
> For example:
>
> *"Use hierograph to find all classes that depend on AuthService"*
>
> This ensures the assistant uses the MCP tools for structural analysis rather than doing
> text-based code search.
