# `this` (scope receivers) vs. extensions in Kotlin

> Terminology note: Kotlin doesn't have a thing literally called an "extension class."
> What's in play are two different ways to add something like `node.children`, and they
> differ in **how many receivers (`this`) are involved**:
>
> 1. **Top-level extension** — an extension function/property declared at file scope.
>    (A file full of these compiles to a `SomethingKt` class, which is often what people
>    mean by "extension class.")
> 2. **Member extension** — an extension declared *inside a class* (the `HierarchyScope`
>    pattern). This is the one that involves an extra `this`.

## Top-level extension: one receiver

```kotlin
// declared at the top level of some file
fun HGNode.children(hierarchy: Hierarchy): List<HGNode> =
    hierarchy.childrenOf(this)          // `this` = the HGNode
```

- **One receiver**: `this` is the `HGNode`.
- It has no surrounding context, so anything else it needs (the `Hierarchy`) must be
  **passed as a parameter**.
- Always available — just import it.
- Usage: `node.children(hierarchy)` — you thread `hierarchy` through every call.

## Member extension in a scope class: two receivers

```kotlin
class HierarchyScope(val hierarchy: Hierarchy) {
    val HGNode.children: List<HGNode>
        get() = hierarchy.childrenOf(this)   // two receivers in play here
}
```

Inside that getter there are **two** `this` objects:

- **Extension receiver** — the `HGNode` the property is called on. That's what plain
  `this` refers to.
- **Dispatch receiver** — the `HierarchyScope` instance. Its members (like `hierarchy`)
  are in scope directly, and you can name it explicitly as `this@HierarchyScope`.

So `hierarchy.childrenOf(this)` reads the `hierarchy` from the *dispatch* receiver and
passes the *extension* receiver (`this`, the node). The context (`hierarchy`) comes from
the enclosing object instead of a parameter.

The catch: a member extension is only visible **while a `HierarchyScope` instance is the
receiver**. You get into that state by being inside the class, or by bringing an instance
into scope with `with`/`run`:

```kotlin
with(HierarchyScope(model.hierarchy)) {
    node.children            // works: HierarchyScope is `this`, so its member extension is visible
}

node.children               // ✗ does NOT compile outside the scope
```

## The core difference

|                          | Top-level extension          | Member extension (scope class)       |
| ------------------------ | ---------------------------- | ------------------------------------ |
| Receivers (`this`)       | 1 (the `HGNode`)             | 2 (the `HGNode` **and** the `HierarchyScope`) |
| Where context comes from | a **parameter** you pass     | the **dispatch receiver's** state    |
| Availability             | everywhere (import)          | only inside the scope (`with(scope){…}`) |
| Call site                | `node.children(hierarchy)`   | `with(scope){ node.children }`       |

The member-extension/scope approach exists precisely to **carry contextual state
implicitly**. `children` fundamentally needs a `Hierarchy`; rather than passing it on
every call, `HierarchyScope` holds it once, and inside the scope all the `HGNode`
extensions can reach it. That's why the API is
`with(HierarchyScope(h)) { a.children; b.parent; … }` — you establish the context once and
then write clean `node.x` calls.

The trade-off: top-level extensions are simpler and always available but force you to pass
context around; scope-class member extensions give cleaner call sites and richer behavior
but require you to enter the scope first.

## Using a `HierarchyScope` with `with()`

`with(HierarchyScope(model.hierarchy)) { … }` puts a `HierarchyScope` instance in as
`this`, so inside the braces every `HGNode` gains the scope's extension members
(`children`, `parent`, `hasChildren`, `traverse`, …), and the scope's own `hierarchy` /
`coreGraph` are directly accessible.

### Basic form

```kotlin
with(HierarchyScope(model.hierarchy)) {
    // `this` == the HierarchyScope
    val root = hierarchy.rootNode          // hierarchy is a member of the scope
    val children = root.children           // HGNode extension property
    println(children.size)
}
```

### What's available inside

```kotlin
with(HierarchyScope(model.hierarchy)) {
    val root = hierarchy.rootNode

    root.children          // List<HGNode>
    root.hasChildren       // Boolean
    someNode.parent        // HGNode?
    someNode.predecessors  // List<HGNode>

    root.traverse { node ->            // walk the subtree
        println(node.identifier)
    }

    a.outgoingTo(b)        // aggregated dependency a -> b
    a.isSuccessorOf(b)
}
```

### Returning a value

`with` returns its last expression, so you can produce a result:

```kotlin
val childCount = with(HierarchyScope(model.hierarchy)) {
    hierarchy.rootNode.children.size
}
```

### Reusing one scope

If you do several things, build the scope once and keep all calls inside the single block:

```kotlin
with(HierarchyScope(model.hierarchy)) {
    val root = hierarchy.rootNode
    val names = root.children.map { JQAssistantNodeMetadataProvider.getName(it) }
    val leaves = root.children.filter { !it.hasChildren }
    // ...
}
```

A few things to keep in mind:

- The extensions (`node.children` etc.) only resolve **inside** the `with` block — outside
  the scope they don't compile.
- `rootNode` lives on `Hierarchy`, not on the scope directly, so it's `hierarchy.rootNode`.
- `with(x)` is not null-safe; `model.hierarchy` here is non-null so that's fine.
- If you prefer a chained/extension style, `HierarchyScope(model.hierarchy).run { … }` is
  equivalent (`this`-based, returns the block result) and works the same way.

## Context receivers / parameters (the evolution)

For completeness: newer Kotlin has **context parameters/receivers**, which generalize
exactly this — letting a *top-level* extension declare "I need a `Hierarchy` in context"
without it being either a parameter or a wrapping class. `HierarchyScope` is the
established, pre-context-receivers way to achieve the same thing.
