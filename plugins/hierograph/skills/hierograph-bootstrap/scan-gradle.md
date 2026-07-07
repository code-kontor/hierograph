# scan-gradle — jQAssistant scan for a Gradle project

> **Status: stub — not yet documented.** The exact jQAssistant 2.9.x Gradle plugin id, version, and
> task names are not captured here yet. Do **not** invent them.

Sub-skill of **hierograph-bootstrap**, for projects built with Gradle (`build.gradle` /
`build.gradle.kts`). When this shape is detected:

1. Tell the user the Gradle path isn't documented in this skill yet.
2. Do not guess plugin coordinates or task names. Consult the official
   [jQAssistant Gradle documentation](https://jqassistant.github.io/jqassistant/current/) for the
   version in `JQASSISTANT_VERSION`, or ask the user for the working setup.
3. However you run it, the scan must still satisfy the orchestrator's **scan invariant** — bytecode
   scanned into the store at `STORE_URI`, the `io.hierograph:io.hierograph.jqassistant.rules`
   plugin on the plugin path, `analyze` run with group `hierograph:virtual-external` (+ the listed
   concepts), and a Bolt server started on the store. The `.jqassistant.yml` in
   [`scan-maven.md`](scan-maven.md) (store / plugins / analyze / scan blocks) is the canonical
   config and is largely reusable; the Gradle plugin's own docs define how it's applied and how
   extra plugins are declared.

Once a live Bolt server is running on a populated store, return to the orchestrator **Step 3**.

_To fill this in: capture the verified Gradle plugin id/version, the scan/analyze/server task names,
how an extra rules plugin is added, and whether `.jqassistant.yml` is read as-is._
