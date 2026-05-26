# Hierograph: Limits vs. Skill Guidance for `detail_dependencies`

A design discussion about whether to remove the size limit on `detail_dependencies` entirely and rely on a Skill to teach the LLM the proper drill-down workflow, or keep the limit and use a Skill for complementary purposes.

## The proposal

Instead of `detail_dependencies` returning a `limit`-bounded list with a `truncated` flag, the tool would return the full result regardless of size — and a Skill would teach the LLM to call it correctly (request summaries first via aggregated tools, drill into specific relationships, etc.).

The Skill becomes the *protocol* for using the tool well. The tool itself becomes simpler (no limit logic, no truncation handling), and the smart behavior shifts to the Skill layer.

## What's appealing about it

A few things make this attractive:

**Cleaner tool surface.** Without limits, `detail_dependencies` has fewer parameters and simpler semantics. The tool does one thing: given two subtrees and an optional relationship, return everything that exists between them. No "but only up to N" complexity.

**Higher fidelity by default.** When the LLM does want exhaustive data, it gets exhaustive data. No silent truncation, no "you got 50 of 247" confusion.

**Forces better LLM behavior through guidance, not constraints.** Currently, the tool *constrains* the LLM to handle truncated responses. With the Skill approach, the tool *educates* the LLM about how to think about detail-level queries. The Skill becomes the teaching layer, the tool becomes the mechanism.

**Better composition with aggregated tools.** If the LLM learns to think "summarize first, drill second," it'll naturally use `aggregated_outgoing` and `aggregated_incoming` to scope its queries before reaching for `detail_dependencies`. This is the workflow you want anyway.

**It matches the principle "Hierograph shouldn't hide data."** From the limits-and-pagination discussion — the goal is that complete information is always reachable. Removing limits is the strongest version of that principle.

## What's concerning about it

But also, several real concerns:

**Skills don't substitute for tool behavior at runtime.** A Skill is documentation that Claude reads — but it doesn't *enforce* behavior. The LLM might read the Skill, agree with its guidance, and then call `detail_dependencies` without first checking the size. Most of the time this works fine; occasionally it produces a 50MB response that crashes the LLM's context.

**Response size is a runtime concern, not a guidance concern.** When `detail_dependencies(from=root, to=common_method)` would return 50,000 edges, the LLM doesn't *know* that until it makes the call. The Skill can say "be cautious with global queries" but can't predict which queries are dangerous. The tool, in contrast, has the actual data and can return a controlled response.

**The "Skill teaches caution" assumption is fragile.** Skills work well for *teaching workflows* ("when the user asks for a DSM, do these things in this order"). They work less well for *runtime safety* ("don't ask for too much data"). Safety constraints should generally live in the system that has visibility into the actual data — i.e., the server.

**You're moving complexity, not removing it.** With limits in the tool, complexity is in the tool (which knows about truncation, summary fields). Without limits, complexity moves to the Skill (which has to teach the LLM the whole protocol) and to the LLM's reasoning (which has to remember and apply the protocol on every call). Net complexity isn't lower; it's just relocated.

**Skills are session-local; tools are universal.** A Skill installed in your project's `.claude/skills/` directory teaches Claude in *your* sessions. But Hierograph is intended for distribution. Other users will install Hierograph and use it without your Skill. If the tool has no limit, *their* sessions will sometimes return 50MB responses and crash. Tools should be safe by default, regardless of what Skills accompany them.

## A subtle but important point

Worth being explicit about something: **the limit isn't really about teaching the LLM to behave well. It's about ensuring the tool can't return responses that destroy the session.**

A 50MB response doesn't just inconvenience the LLM — it blows out the context window, possibly crashes the conversation, and produces no useful output. The limit isn't a politeness constraint; it's an integrity constraint.

You can imagine an LLM that's smart enough to never call `detail_dependencies` in ways that produce 50MB responses. But even that LLM, if it called the tool incorrectly once, would have its session disrupted. The limit protects against the rare failure mode, not the typical one. Removing it accepts that the rare failure can happen catastrophically.

## A reframe: what limits actually do

Let me suggest a different mental model. The current limit serves three distinct purposes:

1. **Safety net against catastrophic responses.** Stops 50MB responses from ever happening. This is essential and doesn't transfer to the Skill layer well.

2. **Encouragement to think summary-first.** When the LLM sees `truncated: true, total_matching: 1247`, it learns to filter further. The Skill could partially substitute for this.

3. **Bounded responses for genuine exhaustive needs.** The LLM can issue tighter queries if a result is truncated. This is currently the recovery path.

Of these, only #2 is really substitutable by a Skill. The Skill could replace the "encourages summary-first thinking" function, but #1 and #3 remain in the tool.

## A possible hybrid

So here's a thought: **keep the limit, but make it generous and make the Skill teach summary-first thinking.**

Specifically:

- Keep `detail_dependencies`'s `limit` parameter, with a higher default (say, 200 instead of 50)
- Server-side cap at maybe 1000 or 2000 — still bounded, but much higher than the current cap
- Add a Skill that teaches the LLM the typical workflow: aggregated tools first, then `detail_dependencies` for specific drill-downs, then `method_details`/`field_details` for individual entities

This gets you most of the benefit of both approaches:

- The tool is still safe by default (no catastrophic responses possible)
- The tool is more permissive than today (limit is generous, so most real queries return complete data)
- The Skill educates the LLM on the *workflow* of summarize-then-drill, not on raw size management
- For exhaustive cases that exceed 1000 edges, the truncation is honest and the LLM can apply tighter filters

The Skill's job becomes the part it's actually good at: *teaching the workflow*. The tool's job stays the part it's actually good at: *enforcing safety bounds*.

## What the Skill might actually look like

To make this concrete, here's what I'd draft if you went the hybrid route:

```markdown
---
name: Architectural drill-down with Hierograph
description: Use when investigating dependencies, coupling, or architectural relationships in a codebase analyzed by Hierograph.
---

# Architectural Drill-Down

When investigating dependencies or coupling in a Hierograph-analyzed codebase, work from *aggregated* to *specific* rather than reaching for detailed data first.

## The standard progression

1. **Start broad.** Use `describe_graph` or `find_node` to orient. Use `aggregated_outgoing` or `aggregated_incoming` to see what depends on what at the subtree level.

2. **Identify what's worth investigating.** The aggregated tools return weight, type_pair_count, and kinds. Use these to spot the interesting subtree pairs — usually the heaviest weights or unexpected dependencies.

3. **Drill to detail only when needed.** Once you have a specific subtree pair, use `detail_dependencies` to see method/field-level evidence. If the question is about one specific entity, use `method_details` or `field_details` directly.

4. **Read source code when structural data isn't enough.** Hierograph tells you *what* depends on *what*. The actual implementation details live in source files, accessible via your file-reading tools.

## When to be cautious

- Avoid global queries (`from=root`) unless the question genuinely needs whole-codebase scope.
- Prefer filtering by relationship kind when possible — `relationship: "throws"` is much smaller than no filter.
- If `truncated: true` appears in a response, the answer is to *narrow the query*, not increase the limit.

## When to use detail_dependencies vs neighbors

- For aggregated-level questions: use `outgoing_core_dependencies` or `aggregated_outgoing`.
- For method/field-level evidence between two subtrees: use `detail_dependencies`.
- For everything about one specific method or field: use `method_details` or `field_details`.
```

This Skill teaches the workflow, doesn't try to teach size management. That division of labor feels right.

## Honest recommendation

Keep the limit. Make it generous. Add the Skill for workflow guidance.

Specifically:

1. **Don't remove limits from `detail_dependencies`.** The safety case is too strong, and the failure mode of removing limits is too catastrophic.

2. **Raise the default limit.** From 50 to 200 or 500. Most real queries fit; truncation becomes rare in practice.

3. **Raise the server-side cap.** From 500 to maybe 2000. The cap exists for the catastrophic case (millions of edges), not the merely-large case.

4. **Write a Skill that teaches summary-first workflow.** This is the right place for behavioral guidance — not limit-related, but workflow-related. The Skill tells the LLM how to *think* about drill-down questions, which is much more useful than telling it how to handle pagination.

5. **Make truncation more informative.** When truncation happens, the response should include `suggested_filters` — things the LLM could pass to narrow the query (e.g., "try `relationship: 'throws'` to get just the exception throws" if throws are a large category). The tool helps the LLM recover from truncation, doesn't just signal that it happened.

This hybrid keeps the safety benefits of limits while moving the workflow-teaching role to the Skill (where it belongs). You get cleaner separation of concerns: tools enforce safety, Skills teach workflows.

## One thing worth being explicit about

There's a broader pattern here worth flagging: **the question "should this constraint be in the tool or in the Skill?" comes up often when designing for LLMs.**

The general principle: *constraints that protect against catastrophic failures belong in the tool. Constraints that improve quality but aren't catastrophe-relevant can move to the Skill.*

Truncation limits prevent catastrophes (context destruction). They belong in the tool.

Workflow guidance (summarize first, drill second) improves quality but doesn't prevent catastrophes. It belongs in the Skill.

This pattern probably applies to other questions you'll face as Hierograph matures. Worth keeping in mind.

## Summary

Don't remove the limit. Raise it, soften it with good error messages, and pair it with a Skill that teaches the right workflow. You get the API simplification you want for typical use, the safety net for atypical use, and the workflow education through a Skill that's actually well-suited to that job.
