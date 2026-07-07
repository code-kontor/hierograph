# scan-maven — jQAssistant scan for a Maven project

Sub-skill of **hierograph-bootstrap**. Use when the project builds with Maven (`pom.xml`). Assumes
the orchestrator's Step 1 (preflight, rules plugin resolvable) is done. Parameters
(`HIEROGRAPH_VERSION`, `JQASSISTANT_VERSION`, `STORE_URI`, `BOLT_PORT`) come from the orchestrator.

Outcome: a **live jQAssistant Bolt server** on a populated store. Return to the orchestrator Step 3.

## 1. Add the jQAssistant scan profile to the build

Add an **opt-in profile** (so normal builds stay fast) to the parent/root POM — or the POM of the
single module the user wants to scan. Match the project's existing formatting; merge into existing
`<properties>` / `<profiles>` rather than duplicating.

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

## 2. Create `.jqassistant.yml` in the project root

The Maven plugin reads `.jqassistant.yml`. It declares the store, the plugins (including the
Hierograph rules plugin), and the analyze group/concepts from the orchestrator's scan invariant.

```yaml
jqassistant:
  store:
    uri: file:.jqassistant-store
  plugins:
    - group-id: com.buschmais.jqassistant.plugin
      artifact-id: java
      version: 2.9.1
    - group-id: com.buschmais.jqassistant.plugin
      artifact-id: common
      version: 2.9.1
    - group-id: io.hierograph
      artifact-id: io.hierograph.jqassistant.rules
      version: 0.1.0   # HIEROGRAPH_VERSION — latest release from Maven Central; never a -SNAPSHOT
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

Substitute `STORE_URI` and `JQASSISTANT_VERSION` for the values above. For the
`io.hierograph.jqassistant.rules` plugin, use the **latest release** `HIEROGRAPH_VERSION` — never a
`-SNAPSHOT`. If you didn't already resolve it in the orchestrator's Step 1, read it from Maven
Central's metadata:

```bash
curl -s https://repo1.maven.org/maven2/io/hierograph/io.hierograph.jqassistant.rules/maven-metadata.xml \
  | sed -n 's:.*<release>\(.*\)</release>.*:\1:p'
```

(`0.1.0` at time of writing.) Add the store directory to `.gitignore`. **Do not omit** the
`io.hierograph.jqassistant.rules` plugin or the `hierograph:virtual-external` group.

## 3. Scan

```bash
mvn clean install -Pjqassistant
```

Activating the profile is what triggers `scan` + `analyze`; a plain `mvn clean install` builds
without scanning. Confirm it completes without "Cannot find group" (→ recheck orchestrator Step 1
and the plugin/group block above). Re-run only when the code changes.

## 4. Start the jQAssistant Bolt server (background)

```bash
mvn -N com.buschmais.jqassistant:jqassistant-maven-plugin:2.9.1:server
```

- **`-N` (non-recursive) is required** in multi-module builds — otherwise the goal runs per reactor
  module and the server starts/stops repeatedly instead of staying up. `-N` restricts it to the
  root module (where `.jqassistant.yml` lives).
- Binds `bolt://localhost:7687` and a browser UI at `http://localhost:7474`. Wait until the log
  shows the Bolt endpoint listening.
- **If the MCP server will run in Docker**, bind all interfaces so the container can connect:
  append `-Djqassistant.store.embedded.listen-address=0.0.0.0`.

Leave this running and return to the orchestrator **Step 3** (start the Hierograph MCP server).

## Maven-specific failures

- **Server goal starts and stops repeatedly** — missing `-N`.
- **"Cannot find group" during `analyze`** — rules plugin missing from `~/.m2` (orchestrator
  Step 1) or from the `plugins:` block above.
- **`mvn clean` wiped the store** — `STORE_URI` was under `target/`; use a root-level path like
  `.jqassistant-store`.
- **Scan finds nothing** — the module wasn't compiled; run a normal `mvn install` first, or scan
  the module that actually contains the classes.
