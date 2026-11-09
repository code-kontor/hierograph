# Hierograph — Claude Code plugin

Claude Code skills for setting up and analyzing JVM codebases with the Hierograph MCP server.

Skills included:

- **`/hierograph:hierograph-bootstrap`** — first-time setup for a Maven project. By default it starts
  the self-contained Hierograph Docker container with the project and `~/.m2` mounted read-only: the
  container produces and regenerates the build manifest itself, and the project needs no change at all.
  Alternatively it runs the server as a local Java process on a manifest from the host
  (`hierograph:manifest`). Then it registers the server with your client. Afterwards the server follows
  code, module and POM changes on its own; there is no rescan step. Gradle builds are not supported yet.
- **`/hierograph:hierograph-structure`** — dependency structure: DSM, layered dependency graph,
  layering and cycle analysis.
- **`/hierograph:hierograph-extract`** — plan a module / package extraction.

## Install

The plugin is published from a marketplace hosted in this repo.

```bash
# 1. Add the marketplace (once). Use the repo's GitHub slug or its git URL.
/plugin marketplace add code-kontor/hierograph

# 2. Install the plugin.
/plugin install hierograph@code-kontor
```

Once installed, the skills are available **user-wide** — in any project you open, not just this
repo. That matters for `hierograph-bootstrap`, which you run from the target project you want to
analyze.

### Managing the plugin

```bash
/plugin marketplace update code-kontor   # pull the latest skill definitions
/plugin disable hierograph               # turn off without uninstalling
/plugin uninstall hierograph@code-kontor
```

## Local development / testing

To try changes without going through GitHub, add the working tree as a local marketplace:

```bash
/plugin marketplace add /Users/wuetherich/Development/io.hierograph
/plugin install hierograph@code-kontor
```

Re-run `/plugin marketplace update code-kontor` after editing a `SKILL.md` to reload it.

## Use

After installing, invoke a skill directly (`/hierograph:hierograph-bootstrap`) or just describe the
task — e.g. *"bootstrap hierograph for this project"* or *"show these modules as a DSM"* — and the
matching skill triggers automatically.

The bootstrap needs a released Hierograph **0.2.0 or later** (server image on GHCR; Maven plugin and
server jar on Maven Central), and Docker — or Java 21 for the local-JVM mode.
