# Hierograph — Claude Code plugin

Claude Code skills for setting up and analyzing JVM codebases with the Hierograph MCP server.

Skills included:

- **`/hierograph:hierograph-bootstrap`** — first-time setup for a Maven or Gradle project. By default it
  starts the Hierograph Docker container with the project mounted read-only. For a Maven build (with
  `~/.m2` mounted read-only too) the container produces and regenerates the build manifest itself, and
  the project needs no change at all. For a Gradle build the host writes the manifest with the
  `hierographManifest` task through an init script, and the container serves it. Alternatively it runs
  the server as a local Java process on a manifest from the host. Then it registers the server with
  your client. Afterwards the server follows code and module changes on its own; there is no rescan
  step. Optionally, for a Maven build, it derives a multi-module view from the POMs.
- **`/hierograph:hierograph-structure`** — dependency structure: DSM, layered dependency graph,
  layering and cycle analysis, also in a view — by multi-module, layer, context or target
  architecture — which it can propose and define.
- **`/hierograph:hierograph-extract`** — plan a module / package extraction, projected as a
  restructuring view: the server shows the new module's dependencies and any cycle before code is
  touched.

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

### Upgrading from 0.2.0

Plugin 0.2.0 shipped skills that no longer match the server: a `hierograph-bootstrap` that sets up
jQAssistant + Neo4j, a DSM-only `hierograph-dsm`, and a `hierograph-extract` without views. They
trigger on the same phrases as the current skills and call tools with outdated parameters. Update
the plugin rather than running both:

```bash
/plugin marketplace update code-kontor
/plugin update hierograph@code-kontor
```

`hierograph-dsm` is gone; `hierograph-structure` covers the DSM and more.

## Local development / testing

To try changes without going through GitHub, add the working tree as a local marketplace:

```bash
/plugin marketplace add /Users/wuetherich/Development/io.hierograph
/plugin install hierograph@code-kontor
```

Re-run `/plugin marketplace update code-kontor` after editing a `SKILL.md` to reload it.

Alternatively, `plugins/link-skills.sh` symlinks the skills into this repo's `.claude/skills/`, so a
session in the repo loads them live. The skills then exist twice if the plugin is installed too —
as `hierograph-structure` and `hierograph:hierograph-structure` — and both sets trigger on the same
phrases. This repo's `.claude/settings.json` therefore disables the plugin:

```json
{ "enabledPlugins": { "hierograph@code-kontor": false } }
```

Do the same in any other project that links the skills. A plugin installed with `--scope local`
records `true` in `.claude/settings.local.json`, which overrides `settings.json`; remove that entry
or run `/plugin disable hierograph` there.

## Use

After installing, invoke a skill directly (`/hierograph:hierograph-bootstrap`) or just describe the
task — e.g. *"bootstrap hierograph for this project"* or *"show these modules as a DSM"* — and the
matching skill triggers automatically.

The bootstrap needs a released Hierograph **0.3.0 or later** (server image on GHCR; Maven and Gradle
plugins and server jar on Maven Central), and Docker — or Java 21 for the local-JVM mode.
