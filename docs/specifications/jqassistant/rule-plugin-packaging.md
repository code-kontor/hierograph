# Packaging jQAssistant Rules as a Plugin

## Overview

Hierograph's jQAssistant rules (the canonical virtual-external-type concepts —
see [`virtual-external-types-spec-v2.md`](virtual-external-types-spec-v2.md))
ship as a **rule-only jQAssistant plugin** rather than as loose XML copied into
each consuming project's `jqassistant/` directory. The rules are packaged into a
JAR, published to a Maven repository, and pulled in as a plugin dependency;
jQAssistant discovers and loads them by ID.

This document specifies how that packaging works, documents the concrete
Hierograph implementation, and records the build/verify procedure.

The implementation lives in the `hierograph-jqassistant` Maven module
(artifact `io.hierograph.jqassistant.rules`). The consuming configuration is the
repository-root [`.jqassistant.yml`](../../../.jqassistant.yml).

## How jQAssistant loads a rule plugin

A rule plugin is a JAR with a specific classpath layout:

```
META-INF/
├── jqassistant-plugin.xml   (REQUIRED plugin descriptor)
└── jqassistant-rules/
    └── <namespace>/
        └── <rules>.xml       (XML rule files: concepts, constraints, groups)
```

> **A descriptor is mandatory — this is the common pitfall.** Rule XML placed
> under `META-INF/jqassistant-rules/` is **not** auto-discovered on its own.
> jQAssistant only scans that directory for JARs it recognizes as plugins, and
> recognition requires a `META-INF/jqassistant-plugin.xml` descriptor that
> *explicitly lists each rule resource* (paths relative to
> `META-INF/jqassistant-rules/`). A JAR carrying rule XML but no descriptor is
> silently ignored: `analyze` fails with `Cannot find group <your:group>`, and
> the plugin is absent from the `Scanning for jQAssistant plugins...` log line
> (the resolved-plugin count comes up one short).

This is the same mechanism every official plugin uses. For example the JUnit
plugin keeps its rules at `META-INF/jqassistant-rules/junit5.xml` and lists
`<resource>junit5.xml</resource>` in its `META-INF/jqassistant-plugin.xml`.

To avoid collisions with other plugins, rule files live in a namespaced
sub-folder — Hierograph uses `META-INF/jqassistant-rules/hierograph/`.

## Plugin descriptor

`io.hierograph.jqassistant.rules/src/main/resources/META-INF/jqassistant-plugin.xml`:

```xml
<jqassistant-plugin xmlns="http://schema.jqassistant.org/plugin/v2.4"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://schema.jqassistant.org/plugin/v2.4 https://schema.jqassistant.org/plugin/jqassistant-plugin-v2.4.xsd"
                    id="io.hierograph.jqassistant.rules"
                    name="Hierograph jQAssistant Rules"
                    version="${project.version}">
    <description>Canonical virtual-external-type concepts for Hierograph.</description>
    <rules>
        <resource>hierograph/virtual-external.xml</resource>
    </rules>
</jqassistant-plugin>
```

Notes:

- A rule-only plugin needs `<description>` and `<rules>` only. The `<model>`,
  `<scope>`, and `<scanner>` elements are for plugins that also ship Java code
  (model classes, scanners) — Hierograph's plugin has none.
- Each `<resource>` path is **relative to `META-INF/jqassistant-rules/`**, so the
  namespaced file `META-INF/jqassistant-rules/hierograph/virtual-external.xml` is
  declared as `hierograph/virtual-external.xml`.
- `version="${project.version}"` is resolved by Maven resource filtering (see
  *Maven module*), keeping the descriptor in sync with the build version.
- Match the descriptor **schema version** (`plugin/v2.4`) to the jQAssistant
  release in use (2.9.1). Confirm by inspecting an official plugin JAR of the
  same version in the local repository:
  `unzip -p ~/.m2/repository/com/buschmais/jqassistant/plugin/junit/2.9.1/junit-2.9.1.jar META-INF/jqassistant-plugin.xml`.

## Rule files

The rule XML is unchanged from a standalone rule file — a `<jqassistant-rules>`
document with concepts, constraints, and groups. Hierograph's single rule file
is `hierograph/virtual-external.xml`, defining the `hierograph:virtual-external`
group and its eight `hierograph:VirtualExternal*` / `hierograph:VirtualPackageHierarchy`
concepts. The rule IDs and behavior are documented in
[`virtual-external-types-spec-v2.md`](virtual-external-types-spec-v2.md).

Once declared in the descriptor's `<rules>` block, the rules are addressable by
their IDs exactly as local rule files are — including from a consuming project's
own rules via `includeConcept`, `includeConstraint`, and `includeGroup`.

## Maven module

The plugin is a plain JAR module. Its Maven layout mirrors the repo's aggregator +
reverse-DNS-leaf idiom (as used by `hierograph-core` / `hierograph-mcp`):

```
hierograph-jqassistant/                       aggregator POM (packaging: pom)
└── io.hierograph.jqassistant.rules/          rule-only JAR (packaging: jar)
    ├── pom.xml
    └── src/main/resources/
        └── META-INF/
            ├── jqassistant-plugin.xml         descriptor
            └── jqassistant-rules/hierograph/
                └── virtual-external.xml        rules
```

Two non-default settings in the leaf `pom.xml`:

1. **`<jqassistant.skip>true</jqassistant.skip>`** — the rules module *ships*
   rules; it is not itself a jQAssistant analysis target, so the inherited
   `jqassistant-maven-plugin` scan/analyze binding is skipped for it.

2. **Resource filtering scoped to the descriptor** — so `${project.version}`
   resolves in `jqassistant-plugin.xml` without filtering the rule XML (whose
   Cypher must pass through verbatim):

   ```xml
   <build>
     <resources>
       <resource>
         <directory>src/main/resources</directory>
         <filtering>true</filtering>
         <includes><include>META-INF/jqassistant-plugin.xml</include></includes>
       </resource>
       <resource>
         <directory>src/main/resources</directory>
         <filtering>false</filtering>
         <excludes><exclude>META-INF/jqassistant-plugin.xml</exclude></excludes>
       </resource>
     </resources>
   </build>
   ```

The aggregator module is registered in the root `pom.xml` `<modules>`, and the
`io.hierograph.jqassistant.rules` artifact is declared in
`hierograph-parent/pom.xml` `dependencyManagement` like every other module.

## Consuming the plugin

In the consuming project's `.jqassistant.yml`, declare the JAR under `plugins:`;
its rules then resolve by ID under `analyze:`. Hierograph's repository-root
configuration:

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
      version: 0.2.0-SNAPSHOT
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

The `groups: hierograph:virtual-external` reference resolves from the plugin JAR;
no `jqassistant/` rule directory is needed (jQAssistant logs
`Rules directory '.../jqassistant' does not exist, skipping.`).

## Building and verifying

This project builds offline (remote repositories return 401). The rules JAR must
be installed to the local Maven repository before jQAssistant can resolve it as a
plugin.

1. **Install the plugin** to `~/.m2`:

   ```
   mvn -o -pl hierograph-jqassistant/io.hierograph.jqassistant.rules install -DskipTests
   ```

2. **Run scan + analyze** with the fully-qualified, versioned goal (the short
   `jqassistant:` prefix cannot be resolved offline):

   ```
   mvn -o com.buschmais.jqassistant:jqassistant-maven-plugin:2.9.1:scan \
         com.buschmais.jqassistant:jqassistant-maven-plugin:2.9.1:analyze -N
   ```

A successful run shows the plugin in the scan list and applies every concept:

```
Scanning for jQAssistant plugins...
Hierograph jQAssistant Rules 0.2.0-SNAPSHOT [io.hierograph.jqassistant.rules]
...
Executing group 'hierograph:virtual-external'
Applying concept 'hierograph:VirtualExternalType' with severity: 'MINOR'.
... (all eight concepts) ...
BUILD SUCCESS
```

> `scan.reset: true` rebuilds the store from scratch on every scan — the prior
> store contents are removed and the sources rescanned.

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `analyze` fails: `Cannot find group hierograph:virtual-external`; plugin missing from the scan list | JAR has rule XML but no (or an unmatched) `META-INF/jqassistant-plugin.xml`, or the `<resource>` path is wrong | Add/repair the descriptor; ensure each `<resource>` path is relative to `META-INF/jqassistant-rules/` |
| `Plugin not found in any plugin repository` resolving the `jqassistant:` prefix | Offline build can't resolve the short Maven plugin prefix | Use the fully-qualified goal `com.buschmais.jqassistant:jqassistant-maven-plugin:2.9.1:...` |
| Plugin resolves but rules don't apply after an edit | Stale JAR in `~/.m2` | Re-run the install step before scan/analyze |
| `Cannot determine execution root` building the module with `-pl` | Aggregator inherits the scan binding; isolated reactor can't find the root | Build in the full reactor, or pass `-Djqassistant.skip=true` for isolated builds |

## References

- jQAssistant rule semantics for these rules:
  [`virtual-external-types-spec-v2.md`](virtual-external-types-spec-v2.md)
- Module: `hierograph-jqassistant/io.hierograph.jqassistant.rules/`
- Consumer config: [`.jqassistant.yml`](../../../.jqassistant.yml)
- From jQAssistant 2.0.0 onward several previously-bundled plugins (e.g. `java`,
  `common`) moved out of the core distribution and must be declared explicitly in
  `.jqassistant.yml` — the same mechanism used for this plugin.
