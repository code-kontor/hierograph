# Hierograph Bytecode Scanner — Specification

`io.hierograph.graphstore.jvm.scanner` turns compiled JVM code into **facts**. Its
`BytecodeScanner` reads the class files of JARs and class directories and produces
one `ClassFacts` record per class, plus the member-level references found in each
class's code. Everything Hierograph knows about a codebase starts here: the
GraphStore is built from these facts, and the Parquet snapshot stores them.

This document explains why the scanner exists, what it reads, what it extracts,
and how to work with it.

---

## Contents

1. [Purpose](#1-purpose)
2. [Where it sits](#2-where-it-sits)
3. [Inputs](#3-inputs)
4. [Output](#4-output)
5. [What is extracted](#5-what-is-extracted)
6. [What the scanner does not produce](#6-what-the-scanner-does-not-produce)
7. [Parallelism and determinism](#7-parallelism-and-determinism)
8. [Errors](#8-errors)
9. [API reference](#9-api-reference)
10. [Command-line use](#10-command-line-use)
11. [Working with the scanner](#11-working-with-the-scanner)
12. [Guarantees](#12-guarantees)
13. [Boundaries](#13-boundaries)

---

## 1. Purpose

Architectural analysis needs to know, for every type in a system, what it declares
and what it depends on. Compiled bytecode answers both questions precisely:

- It is **complete**. Every dependency the code actually has is in the class file,
  including those hidden behind imports, wildcards, generated code or other JVM
  languages.
- It is **unambiguous**. Names are fully qualified, overloads are distinguished by
  their descriptors, and nothing has to be inferred from source.
- It is **available everywhere**. Libraries and the JDK ship as bytecode, so a
  third-party jar can be analysed exactly like the project's own modules.

The scanner reads bytecode with [ASM](https://asm.ow2.io/) and records what each
class file says about itself. It is built around four principles:

**One class file, one fact record.** Each class is scanned in isolation. The
scanner needs no classpath, resolves nothing, and loads no classes. What it emits
is exactly what that single class file states.

**Facts, not conclusions.** Anything that depends on other class files, such as
which method overrides which, or where a virtual call may land, is left to the
loader, which sees every artifact at once (§6). This is what makes the scan of an
artifact valid in every project that uses it: a library is scanned once and its
facts reused everywhere.

**Counted, typed references.** Every reference is recorded with *how* it is made
(extends, parameter type, invocation, …) and *how often*. A dependency therefore
has a nature and a weight, not just an existence.

**Deterministic.** The same inputs always produce the same output, in the same
order, however many threads the scan uses.

---

## 2. Where it sits

```
  JARs, class directories
           │
           ▼
  ┌──────────────────┐        ScanResult
  │  BytecodeScanner │ ────────────────────────────────┐
  └──────────────────┘   classes: ClassFacts per type   │
                         detailRefs: member-level refs  │
           ┌────────────────────────┬───────────────────┘
           ▼                        ▼
   ScanResult.writeTo        GraphStore.applyClass
   (Parquet snapshot,        (in-memory model,
    one .hierograph           no round trip
    archive per artifact)     through disk)
```

The scanner is used in three places:

| User | How |
|------|-----|
| **Maven plugin** (`hierograph:scan`) | scans a module's compiled output and writes it, with the module's classpath, as the module's `.hierograph` archive |
| **Server** (build-watching mode) | scans each module of a build and feeds the result straight into the GraphStore; the snapshot is written in the background |
| **Command line** | scans any set of JARs and class directories into a snapshot directory (§10) |

---

## 3. Inputs

### 3.1 `ScanArtifact`

A scan input is an **artifact**: a JAR file or a directory of class files, with the
key that identifies it.

```kotlin
data class ScanArtifact(val key: String, val path: Path)
```

| Field | Description |
|-------|-------------|
| `key` | The artifact key. It becomes the `artifact` of every type scanned from this input, and so the name the artifact is known by everywhere downstream. |
| `path` | A class directory, or a file with extension `.jar` or `.zip`. |

`ScanArtifact.of(path)` keys an artifact by its file or directory name, such as
`spring-core-7.0.8.jar` or `classes`. Producers that know better identities use
them instead: the Maven plugin keys a module by its `groupId:artifactId:version`, and
its test output by the same coordinates with `:test` appended.

### 3.2 What is read

**Class directory.** Every regular file under the directory whose name ends in
`.class` is scanned, recursively, in path order.

**JAR or ZIP.** Every entry whose name ends in `.class` is scanned, in entry-name
order. **Multi-release JARs** are read as a current JVM reads them: a class under
`META-INF/versions/<n>/` replaces the base class of the same name, and each class is
scanned **once, in the newest version** the jar contains. A class that exists only
in a version directory is scanned too.

**Skipped**, in both cases:

| Skipped | Why |
|---------|-----|
| `module-info.class` | a module descriptor, not a type |
| anything else under `META-INF/` (other than versioned classes) | metadata, not code |
| files that do not end in `.class` | resources |

**Scanned as types**, which may be surprising:

| Class file | Becomes |
|------------|---------|
| nested classes (`Invoice$Line`) | a type of its own, as on the JVM |
| anonymous and local classes (`Invoice$1`) | a type of its own |
| `package-info.class` | a type (`com.example.billing.package-info`); package annotations appear as its references |
| synthetic and compiler-generated classes | ordinary types; their members carry the `synthetic` flag where the compiler sets it |

---

## 4. Output

`BytecodeScanner.scan(artifacts)` returns a `ScanResult`:

```kotlin
data class ScanResult(
    val classes: List<ArtifactClass>,   // one per scanned class: (artifactKey, ClassFacts)
    val detailRefs: List<DetailRef>,    // member-level references of all scanned classes
)
```

**`classes`** holds one `ArtifactClass(artifactKey, facts)` per class file. A
`ClassFacts` record carries the type itself, its members with their parameters, and
its type-level references:

```kotlin
data class ClassFacts(
    val fqn: String,
    val kind: JavaNodeKind,
    val modifiers: Int,
    val members: List<MemberFacts>,
    val refs: List<Ref>,
    val classHash: String,
    val sourceFile: String?,
)
```

This is the GraphStore's ingest unit: each element of `classes` can be passed to
`GraphStore.applyClass` as it is.

**`detailRefs`** holds the member-level references: which method calls which, which
code reads or writes which field, what each member's signature names, with source
lines. They are kept apart from `ClassFacts` because they are large and are read on
demand rather than held in memory.

**`writeTo(dir, codec, classpaths, packaging)`** writes the result as a snapshot
directory, with one `.hierograph` artifact per artifact key — an archive or an
exploded directory of the same name, with identical tables. An artifact of the same
name already in the directory is replaced, whichever its form. Nothing is written
outside the directory: an archive's tables are staged in a `.staging-<artifact>`
directory there, which is deleted once the archive is written. Tables, columns and
conventions are defined in the
[snapshot format specification](../../io.hierograph.graphstore.jvm/spec/hierograph-graphstore-format.md).

| Parameter | Default | Description |
|-----------|---------|-------------|
| `dir` | — | the snapshot directory; created if missing |
| `codec` | ZSTD | the Parquet compression codec |
| `classpaths` | none | per artifact key, the ordered classpath to store in that artifact's `classpath` table |
| `packaging` | `ARCHIVE` | `ARCHIVE` writes `<artifact>.hierograph` files (for shipping), `EXPLODED` writes `<artifact>.hierograph/` directories (for local work) |

---

## 5. What is extracted

Names follow the snapshot's conventions: binary FQNs with `$` for nested types,
member keys `<owner>#<signature>`, and arrays reduced to their element type where a
type is *referenced* (see §3 of the snapshot format specification).

### 5.1 Types

| Fact | Taken from |
|------|------------|
| `fqn` | the class name in the class header |
| `kind` | the access flags, in this order of precedence: annotation → `ANNOTATION`; enum → `ENUM`; superclass `java.lang.Record` → `RECORD`; interface → `INTERFACE`; otherwise `CLASS` |
| `modifiers` | visibility, `abstract`, `final`, `static`. For a **nested** type they come from the class's own `InnerClasses` entry, because only that entry records `private`, `protected` and `static` on a nested type |
| `sourceFile` | the `SourceFile` attribute, if present |
| `classHash` | the SHA-256 of the class file's bytes, as 64 lower-case hex digits |

### 5.2 Members

Every declared field and method becomes a `MemberFacts`: constructors (`<init>`),
the static initializer (`<clinit>`), and compiler-generated members such as bridge
methods, lambda bodies, and an enum's `values()` / `$VALUES`. Inherited members are
not repeated; a member belongs to the class that declares it.

| Fact | Taken from |
|------|------------|
| `key`, `signature` | name and descriptor, as `<return> <name>(<params>)` for methods and `<type> <name>` for fields |
| `name` | the member's name |
| `kind` | `CONSTRUCTOR` for `<init>`, `METHOD` for any other method, `FIELD` |
| `modifiers` | the access flags (visibility, `static`, `final`, `abstract`, `synchronized`, `native`, `transient`, `volatile`, `bridge`, `synthetic`); `default` for an interface method that has a body and is neither static nor private |
| `lineFrom`, `lineTo` | the lowest and highest line in the method's `LineNumberTable`; absent for fields and for methods without code |
| `bridgeTarget` | for a bridge method: the first call it makes to a method of the same name on its own class, which is the method it forwards to |

### 5.3 Parameters

Each method and constructor parameter becomes a `ParameterFacts` with its 0-based
`index`, its `typeFqn` (the erased type, reduced to its element type), and its
`name`. The name is taken from the first of these sources that has it:

1. the `MethodParameters` attribute, written by `javac -parameters` and `kotlinc -java-parameters`;
2. the local-variable table, written when debug information is compiled in;
3. otherwise `arg<index>`.

### 5.4 Type-level references

Each reference a class makes to another type is recorded as a `Ref` from the class
to the target type, with a **kind** and a **count**: the number of places in the
class file that make that reference. References are aggregated per (target, kind),
in the order they are first encountered.

| Kind | Recorded for each … |
|------|---------------------|
| `EXTENDS` | superclass (one per class) |
| `IMPLEMENTS` | implemented interface, or extended interface of an interface |
| `ANNOTATED_BY` | annotation on the class itself |
| `FIELD_TYPE` | field of the target type |
| `PARAM_TYPE` | method parameter of the target type |
| `RETURN_TYPE` | method returning the target type |
| `THROWS` | entry in a method's `throws` clause |
| `MEMBER_ANNOTATED_BY` | annotation on a field, a method or a method parameter |
| `TYPE_ARGUMENT` | type that appears inside a generic signature's type arguments or bounds, in the class, field and method signatures (`List<Money>` → `Money`; `<T extends Money>` → `Money`) |
| `ANNOTATION_VALUE` | enum constant, class value or nested annotation used as an annotation value, including an annotation element's default value |
| `INVOKES` | method or constructor call (`invokevirtual`, `invokestatic`, `invokespecial`, `invokeinterface`), on the call's owner type; also the implementation method of a lambda or method reference |
| `READS` | field read (`getfield`, `getstatic`), on the field's owner type |
| `WRITES` | field write (`putfield`, `putstatic`), on the field's owner type |
| `INSTANTIATES` | `new`, and the creation of an array of the target type |
| `CAST` | `checkcast` or `instanceof` against the target type |
| `CATCHES` | exception handler for the target type (a `finally` block names no type) |
| `CLASS_LITERAL` | class constant, such as `Money.class` |

The rules that apply to every kind:

- **Element types.** The target of a reference to an array type is its element type:
  a `Money[][]` parameter is a `PARAM_TYPE` reference to `Money`.
- **No primitives, no `void`.** They are not types one can depend on.
- **No self-references.** A class's references to itself are dropped. References to
  its own nested types, or from a nested type to its outer type, are kept, because
  those are separate classes.
- **Annotations of all retentions in the class file.** `RUNTIME` and `CLASS`
  annotations are both recorded. `SOURCE` annotations are not in the class file.

Some source constructs leave more than one reference. `new Money()` records
`INSTANTIATES` (the `new`) and `INVOKES` (the constructor call), and every
constructor records `INVOKES` on its superclass for its `super(…)` call.

### 5.5 Member-level references

Member-level references (`DetailRef`) record the same code at the finest grain: from
a member (or a class, for its own annotations) to a member or a type. They use the
detail vocabulary of the snapshot format.

| Relationship | From → To | Recorded for each … | Line |
|--------------|-----------|---------------------|------|
| `calls` | method → method | call instruction, naming the call's owner type and the called signature; and the implementation method of a lambda or method reference | the call's line |
| `reads_field` | method → field | field read instruction | the instruction's line |
| `writes_field` | method → field | field write instruction | the instruction's line |
| `returns` | method → type | method (its return type; `void` included) | the method's first line |
| `parameter_type` | method → type | distinct parameter type of the method (primitives included) | the method's first line |
| `throws` | method → type | entry in the method's `throws` clause | the method's first line |
| `raises` | method → type | `throw new X(…)` in the method's body | the method's first line |
| `has_type` | field → type | field (its declared type; primitives included) | none |
| `annotated_by` | class, method or field → type | annotation on the element | none |

Unlike type-level references, member-level references are **not aggregated and not
filtered for self-reference**. A method calling a method of its own class is a
`calls` reference like any other, and two calls on different lines are two
references.

**Call targets are named, not resolved.** A call compiled against `Shape.area()` is
recorded as a call to `Shape#double area()`, whichever subclass implements it, and
even if `Shape` only inherits the method. Resolving it to declarations and possible
implementations requires the other class files, and so happens at load (§6).

**`raises` is precise rather than complete.** It records an exception that is
constructed and thrown at once, which is the bytecode shape of `throw new X(…)`. An
exception that is stored first, rethrown, or produced by a factory is not
attributed, because the bytecode alone does not say what it is. Together with
`throws` (the declared contract) it separates what a method promises from what it
visibly does.

**Calls on arrays** (`array.clone()`) are not recorded; an array has no owner type.

---

## 6. What the scanner does not produce

### 6.1 Facts that need more than one class file

These are computed by the loader, over all artifacts loaded together:

| Not produced | Why | Where it comes from |
|--------------|-----|---------------------|
| method overrides | which method a method overrides depends on its supertypes' class files | load-time resolution, using the scanned members and `bridgeTarget` |
| virtual-call targets and calls resolved to their declaring type | depend on the full type hierarchy | load-time resolution |
| inverse relationships (callers, `read_by`, `written_by`) | the same facts read the other way | query time |
| which artifact provides a referenced type | depends on the consuming artifact's classpath | the GraphStore's type resolver |

### 6.2 What bytecode does not show

| Not visible | Reason |
|-------------|--------|
| compile-time constants | the compiler inlines `static final` primitives and strings, so no reference to their declaring class remains |
| generic type arguments in method bodies | erased; a body only shows the casts the compiler inserts (`CAST`) |
| `SOURCE`-retention annotations | not written to the class file |
| type-use annotations (`List<@NonNull String>`) | not read by the scanner |
| references made only through reflection or string names | not references in the bytecode |
| `invokedynamic` other than lambdas and method references (string concatenation, records' generated methods, pattern-matching switches) | their bootstrap arguments are not recorded |

---

## 7. Parallelism and determinism

```kotlin
BytecodeScanner(parallelism = Runtime.getRuntime().availableProcessors())
```

The scanner runs **one task per class file** on up to `parallelism` threads (and never
more threads than class files). It first lists the class files of every artifact,
then scans them all through one pool. The work therefore spreads evenly however
unequal the artifacts are: a single large artifact uses every thread, and a few
large artifacts at the end of the input do not leave the other threads idle.

`scanByKey` scans like `scan` and splits the result by artifact key (keys in order of
first appearance, an empty result for a key without class files). The server scans
all module outputs of a manifest in one `scanByKey` call, so the modules share the
pool instead of being scanned one after another.

Output order is fixed:

- **artifacts** in input order, whatever order their tasks finish in;
- **classes** within an artifact in path order (directories) or entry-name order
  (JARs);
- **references** within a class in the order the bytecode presents them.

A parallel scan is therefore identical to a sequential one, and two scans of the
same inputs are identical to each other. The archives written from them are
identical too.

A `BytecodeScanner` holds no state between calls. One instance can be reused, and
`scan` can be called from several threads.

---

## 8. Errors

| Situation | Result |
|-----------|--------|
| `parallelism < 1` | `IllegalArgumentException` from the constructor |
| a path that is neither a directory nor a `.jar` / `.zip` file | `IllegalArgumentException("Not a JAR or class directory: …")` |
| an unreadable file, or a class file ASM cannot parse | the scan fails. In a multi-threaded scan the error is an `IllegalStateException` naming the artifact (`Scanning <path> failed: …`), with the original error as its cause |

A scan either succeeds completely or fails. It never returns a partial result.

---

## 9. API reference

| Element | Description |
|---------|-------------|
| `BytecodeScanner(parallelism)` | a scanner using up to `parallelism` threads (default: available processors) |
| `BytecodeScanner.scan(artifacts): ScanResult` | scans the artifacts (§3) into facts (§5) |
| `ScanArtifact(key, path)` | one input, with its artifact key |
| `ScanArtifact.of(path)` | an input keyed by its file or directory name |
| `ScanResult.classes` | the scanned classes, as `ArtifactClass(artifactKey, facts)` |
| `ScanResult.detailRefs` | the member-level references |
| `ScanResult.writeTo(dir, codec, classpaths, packaging)` | writes the result as a snapshot of `.hierograph` artifacts, archived or exploded, one per artifact (§4) |

---

## 10. Command-line use

The module's main class, `io.hierograph.graphstore.jvm.scanner.BytecodeScannerKt`, scans
from the command line:

```
scan --out <snapshot-dir> [--threads <n>] <jar-or-class-dir>...
```

| Argument | Description |
|----------|-------------|
| `--out <dir>` | the snapshot directory to write; required |
| `--threads <n>` | scanner parallelism; defaults to the number of processors |
| `<jar-or-class-dir>...` | one or more inputs; at least one is required |

Each input is keyed by its file or directory name. An input may contain a **glob
pattern in its last path segment**, such as `libs/*.jar`, which the scanner expands
itself, in sorted order. This makes patterns work where no shell expands them: in
quoted arguments, IDE run configurations, or `mvn exec:java -Dexec.args="…"`. A
missing input, or a pattern that matches nothing, is an error that names it.

The snapshot is written with ZSTD compression and without classpath tables. On
success the scanner prints a summary line: the number of artifacts and threads,
types, members and member-level references, the output directory, and the elapsed
time.

```
$ java -cp <scanner classpath> io.hierograph.graphstore.jvm.scanner.BytecodeScannerKt \
      --out snapshot 'target/classes' 'libs/*.jar'
Scanned 42 artifacts on 10 threads: 18342 types, 151204 members, 1287711 detail refs → snapshot in 6412 ms
```

---

## 11. Working with the scanner

### 11.1 Scan to a snapshot

```kotlin
val result = BytecodeScanner().scan(
    listOf(
        ScanArtifact("com.example:billing-core:1.4.0", Path.of("billing-core/target/classes")),
        ScanArtifact.of(Path.of("libs/guava-33.0.0-jre.jar")),
    ),
)
result.writeTo(Path.of("snapshot"))
```

### 11.2 Scan with classpaths

When the classpath of an artifact is known, store it with the archive. The loader
then resolves names the way the JVM would (index 0 of each classpath is the artifact
itself, and the writer adds it):

```kotlin
val key = "com.example:billing-core:1.4.0"
val classpath = ArtifactClasspath(
    artifactKey = key,
    name = "billing-core",
    entries = listOf(
        ClasspathEntry("com.example:billing-api:1.4.0", internal = true),
        ClasspathEntry("com.google.guava:guava:33.0.0-jre", internal = false),
    ),
)
BytecodeScanner().scan(listOf(ScanArtifact(key, classesDir)))
    .writeTo(archiveDir, classpaths = mapOf(key to classpath))
```

### 11.3 Scan straight into a GraphStore

To build the in-memory model without writing to disk, apply the classes directly.
Register each artifact first, then apply its classes:

```kotlin
val result = BytecodeScanner().scan(artifacts)
val store = GraphStore(ExternalTypeFilter.EXCLUDE_UBIQUITOUS)
for (a in artifacts) store.registerArtifact(ArtifactInfo(a.key, a.key, a.key))
for (c in result.classes) store.applyClass(c.artifactKey, c.facts)
```

`result.detailRefs` are not part of the GraphStore. Write them to a snapshot so
member-level queries can read them.

### 11.4 Rescan a changed class

Scanning is per class file, and so is the GraphStore's update. To refresh one
changed class, scan its artifact (or a directory holding just that class, keyed as
its artifact) and apply the new facts for that FQN. Each class's `classHash` tells a
change detector which classes need this.

---

## 12. Guarantees

1. **Isolation.** The facts of a class depend only on its own bytes. The same class
   file scanned in any artifact, project or order yields the same facts.
2. **One record per class.** Each scanned class file yields exactly one
   `ClassFacts`, and a multi-release class is scanned exactly once.
3. **Consistent keys.** Every member key a reference uses, as source or as target,
   follows the same `<owner>#<signature>` convention as the members themselves, so a
   reference to a scanned member matches that member's key exactly.
4. **Self-contained references.** Every reference's source is a member or type of
   the scanned class itself. A detail reference's `fromType` is always the class that
   was scanned.
5. **Determinism.** Output content and order are a function of the inputs alone
   (§7).
6. **All or nothing.** A scan returns a complete result or fails (§8).

---

## 13. Boundaries

| Not in the scanner | Where it lives |
|--------------------|----------------|
| Resolving references across class files (overrides, virtual calls, shadowing) | load-time resolution and the GraphStore (§6) |
| Knowing the classpath or build layout | the Maven plugin and the build manifest, which supply keys and classpaths |
| Change detection and watching | the server, using `classHash` and the build's output directories |
| Source code | not read; everything comes from bytecode |
| Storage format | the snapshot format specification and `ParquetWriter` |
| The in-memory model | the GraphStore |

Keeping the scanner to "one class file in, its facts out" is what makes its output
reusable, cacheable and safe to compute in parallel: a scan never has to know what
else exists.
