# Hierograph — Claude Code plugin

Claude Code skills for setting up and analyzing JVM codebases with
[jQAssistant](https://jqassistant.org) + the Hierograph MCP server.

Skills included:

- **`/hierograph:hierograph-bootstrap`** — first-time setup: add the jQAssistant scan profile and
  `.jqassistant.yml`, scan the project, start the Bolt + MCP servers, and register the MCP server
  with your client.
- **`/hierograph:hierograph-dsm`** — render a Dependency Structure Matrix; layering / cycle analysis.
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
