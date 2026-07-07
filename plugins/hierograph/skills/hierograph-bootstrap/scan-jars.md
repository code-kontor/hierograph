# scan-jars — jQAssistant scan for plain JARs / a classes directory

> **Status: stub — not yet documented.** The exact jQAssistant 2.9.x command-line distribution
> (download artifact, `scan`/`analyze`/`server` syntax, plugin installation) is not captured here
> yet. Do **not** invent commands or download URLs.

Sub-skill of **hierograph-bootstrap**, for the no-build-tool shape: you have prebuilt `.jar`s or a
`classes/` directory and scan them with the jQAssistant **command-line** distribution. When this
shape is detected:

1. Tell the user the CLI path isn't documented in this skill yet.
2. Do not guess the CLI download or command syntax. Consult the official
   [jQAssistant command-line documentation](https://jqassistant.github.io/jqassistant/current/) for
   the version in `JQASSISTANT_VERSION` (note the Neo4j v5 vs v4 distribution split), or ask the
   user for the working setup.
3. However you run it, the scan must still satisfy the orchestrator's **scan invariant** — the
   `.jar`s / `classes/` dir scanned into the store at `STORE_URI`, the
   `io.hierograph:io.hierograph.jqassistant.rules` plugin installed into the CLI's plugin path,
   `analyze` run with group `hierograph:virtual-external` (+ the listed concepts), and the CLI's
   Bolt server started on the store. The `.jqassistant.yml` in [`scan-maven.md`](scan-maven.md)
   (store / plugins / analyze / scan blocks) is the canonical config; the CLI reads
   `.jqassistant.yml` from the working directory, so most of it should carry over.

Once a live Bolt server is running on a populated store, return to the orchestrator **Step 3**.

_To fill this in: capture the verified CLI download (artifact name + release URL, Neo4j v5 variant),
the `scan -f …` / `analyze` / `server` command syntax, and how the extra rules plugin is added
(plugins dir vs `.jqassistant.yml` resolution from a maven repo)._
