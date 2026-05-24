# Steering Claude toward skills via MCP tool descriptions

## Problem

When Claude sees a user request like "drill into Analysis → Rule," it may call the raw MCP tool (`mcp__hierograph__detail_dependencies`) directly instead of using the higher-level `/detail-deps` skill. This happens because Claude is already in a flow of calling Hierograph tools and doesn't pause to check the available skills list.

## Solution

Add a hint to the MCP tool description returned by the Hierograph server. The tool description is the first thing Claude reads when it resolves a deferred tool via `ToolSearch` — a note there intercepts Claude at the exact moment it's about to call the raw tool.

### Current tool description (excerpt)

```
[Detail-level] Return the method-level and field-level dependencies
between a source subtree and a target subtree. This is the drill-down
tool that bridges the hierarchical level and the detail level — ...
```

### Proposed addition

Append a line like this to the tool description:

```
**Claude Code skill hint:** A /detail-deps skill is available that
provides a guided, multi-step analysis workflow on top of this tool.
Prefer invoking that skill instead of calling this tool directly.
```

## Why this works

- **Right place, right time:** Claude reads the tool description when it fetches the schema — exactly when it's about to bypass the skill.
- **Non-intrusive:** The hint doesn't change the tool's behavior or parameters. It's purely advisory text for the LLM consumer.
- **General pattern:** This approach can be applied to any MCP tool that has a corresponding higher-level skill. For example, `pairwise_dependencies` could hint at a `/dsm` skill if one existed.

## Where to implement

The tool description is defined server-side in the Hierograph MCP server, typically in the tool registration / schema definition code. Update the `description` string for the `detail_dependencies` tool to include the hint.
