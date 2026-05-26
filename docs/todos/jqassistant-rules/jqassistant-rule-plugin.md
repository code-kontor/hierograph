# Adding Rules to jQAssistant via a Rule Plugin

jQAssistant has a first-class rule plugin mechanism. You package your rules
into a JAR and pull it in as a dependency — jQAssistant discovers and loads
the rules automatically, with no need to copy XML files into each consuming
project's `jqassistant/` folder.

## How it works

A rule plugin is a JAR with a specific layout on the classpath:

```
META-INF/
└── jqassistant-rules/
    └── my-rules.xml        (XML rule files)
```

XML rule files (`<jqassistant-rules>` with concepts, constraints, and groups)
are picked up automatically when placed under `META-INF/jqassistant-rules/`.
This is the same pattern used by every official rule plugin — for example,
the JUnit plugin keeps its rules at `META-INF/jqassistant-rules/junit5.xml`
and the JAX-RS plugin at `META-INF/jqassistant-rules/jaxrs-resource.xml`.

To avoid collisions with other plugins, place your rule files in a
namespaced sub-folder, e.g. `META-INF/jqassistant-rules/my-namespace/`.

## Example rule file

`src/main/resources/META-INF/jqassistant-rules/my-namespace/architecture.xml`:

```xml
<jqassistant-rules
    xmlns="http://schema.jqassistant.org/rule/v1.10"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://schema.jqassistant.org/rule/v1.10
                        http://schema.jqassistant.org/rule/jqassistant-rule-v1.10.xsd">

    <group id="my-rules:Default">
        <includeConstraint refId="my-rules:NoCyclicPackageDependencies"/>
    </group>

    <constraint id="my-rules:NoCyclicPackageDependencies">
        <description>Packages must not have cyclic dependencies.</description>
        <cypher><![CDATA[
            MATCH (p1:Package)-[:DEPENDS_ON]->(p2:Package),
                  (p2)-[:DEPENDS_ON*]->(p1)
            RETURN p1 AS Package, p2 AS Cycle
        ]]></cypher>
    </constraint>

</jqassistant-rules>
```

## Consuming the rules (jQAssistant 2.x)

In the consuming project's `.jqassistant.yml`, declare the plugin as a
dependency:

```yaml
jqassistant:
  plugins:
    - group-id: com.example
      artifact-id: my-jqassistant-rules
      version: 1.0.0
  analyze:
    groups:
      - "my-rules:Default"
```

When you run `mvn jqassistant:scan jqassistant:analyze`, jQAssistant resolves
the JAR, scans `META-INF/jqassistant-rules/`, and makes the rules available
by their IDs just like local rule files. You can also reference them from
your own rules via `includeConcept`, `includeConstraint`, and `includeGroup`.

## Practical recipe for a rule-only plugin

1. Create a plain JAR project (Maven or Gradle). No jQAssistant code is
   needed if you only ship rules.
2. Put your `*.xml` rule files under
   `src/main/resources/META-INF/jqassistant-rules/<your-namespace>/`.
3. Publish to your Maven repository (or install locally for testing).
4. Reference it in the consumer's `.jqassistant.yml` under `plugins:`.

## Real-world examples

- `IsyFact/isyfact-jqassistant-plugin` — a pure rule-pack plugin (rules
  only, no Java code).
- `kontext-e/jqassistant-plugins` — a collection of rule and scanner
  plugins.
- The `jqassistant-plugin` GitHub organization's plugins (Java testing,
  Jakarta EE, etc.) — official rule plugins worth studying for layout.

## Note on jQAssistant 2.x

From jQAssistant v2.0.0 onward, several previously-bundled plugins moved
out of the core distribution and must be declared explicitly in
`.jqassistant.yml`. That is the same mechanism you'll use for your own
plugin, which keeps things consistent.
