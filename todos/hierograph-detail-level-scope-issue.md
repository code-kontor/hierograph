# Hierograph: Detail-level queries require type-or-higher scope

## Summary

When using `outgoing_dependencies` or `incoming_dependencies` with `detail_level='detail'`, the source scope (`arg0`) must be a **type ID or higher** (type, package, module). Passing a **method ID** silently returns zero results — no error is raised, even when edges clearly exist.

## Reproduction

### Setup

- Target method: `SchedulerAccessor.registerJobsAndTriggers()` (ID: 422756)
- Declaring type: `SchedulerAccessor` (ID: 422702)
- Package: `org.springframework.scheduling.quartz` (ID: 422258)
- Module: `spring-context-support-7.0.7.jar` (ID: 419815)

### Query with method ID as source — returns zero results

```
outgoing_dependencies(
  arg0 = 422756,   // registerJobsAndTriggers (method)
  arg1 = 422258,   // quartz package
  arg2 = "detail",
  arg3 = "calls"
)
→ 0 edges
```

Same result when broadening the target to the entire module (419815).

### Query with type ID as source — returns all edges

```
outgoing_dependencies(
  arg0 = 422702,   // SchedulerAccessor (type)
  arg1 = 419815,   // spring-context-support module
  arg2 = "detail",
  arg3 = "calls"
)
→ 18 edges
```

This includes 6 edges originating from `registerJobsAndTriggers` (422756):

| Line | Target                                    |
|------|-------------------------------------------|
| 206  | `ResourceLoaderClassLoadHelper.<init>()`  |
| 207  | `ClassLoadHelper.initialize()`            |
| 210  | `getScheduler()`                          |
| 217  | `addJobToScheduler()`                     |
| 229  | `getScheduler()`                          |
| 236  | `addTriggerToScheduler()`                 |

## Workaround

Always use the **declaring type's ID** as `arg0`, not the method ID. Filter the returned edges by the method's ID in the `from` field to isolate a specific method's calls.

## Expected behavior

Either:
1. Detail-level queries should accept method IDs as source scope and return matching edges, or
2. The tool should return an error indicating that method-scoped queries are not supported at detail level.
