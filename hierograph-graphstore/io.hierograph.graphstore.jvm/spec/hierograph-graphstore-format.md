# Hierograph GraphStore — Parquet Snapshot Format

A **snapshot** is the persistent form of Hierograph's GraphStore: everything the
bytecode scanner learned about a set of JVM artifacts, written as a handful of flat
Parquet tables. A snapshot can be rebuilt into an in-memory graph at any time, and
it can be queried directly on disk — the detail-level tools read members,
parameters and member-level references straight from the tables instead of holding
them in memory.

This document defines that format: the files, the packaging, and every column —
what it describes and why it exists.

---

## Contents

1. [Design principles](#1-design-principles)
2. [Layout](#2-layout)
3. [Conventions](#3-conventions)
4. [Tables](#4-tables)
   - [4.1 `types.parquet`](#41-typesparquet)
   - [4.2 `members.parquet`](#42-membersparquet)
   - [4.3 `parameters.parquet`](#43-parametersparquet)
   - [4.4 `refs.parquet`](#44-refsparquet)
   - [4.5 `detail_refs.parquet`](#45-detail_refsparquet)
   - [4.6 `classpath.parquet`](#46-classpathparquet)
5. [Vocabularies](#5-vocabularies)
6. [Loading semantics](#6-loading-semantics)
7. [Example](#7-example)

---

## 1. Design principles

**Facts, not conclusions.** A snapshot records only what a single class file can
answer on its own: what a type declares, which types and members it names, and
where. Anything that depends on *which other artifacts are present* — override
relationships, virtual-call targets, which of two same-named classes a reference
binds to — is derived when snapshots are loaded together, never stored. That is
what makes an artifact's snapshot reusable: a library is scanned once and its
archive serves every project that depends on it.

**One artifact, one set of tables.** The unit of scanning, caching and exchange is
a single artifact (a module's compiled output, a library jar, the JDK). Each is
written as its own set of tables, packaged by use: as a `.hierograph` archive for
shipping, or as a directory of the same name for local work. A project is simply a
directory of artifacts.

**Flat, keyed by strings.** Every row is self-contained and every relationship is
expressed through human-readable keys — fully-qualified type names and member keys —
rather than numeric IDs. Tables from different archives can be concatenated without
renumbering, a reference to a type in another artifact looks exactly like a
reference to a local one, and a snapshot can be inspected with any Parquet tool.

**Readable vocabularies.** Kinds and relationships are stored as their names
(`CLASS`, `INVOKES`, `calls`), not as ordinals. Dictionary encoding makes the
repeated strings cost almost nothing, and the files stay self-explanatory.

**Sorted for scoped reads.** Tables that are read selectively are sorted by the
column they are filtered on, so column statistics let a reader skip data that
cannot match.

---

## 2. Layout

### 2.1 The `.hierograph` archive

An archive holds the tables of exactly one artifact, packaged as a ZIP file:

```
<artifact>.hierograph
├── classpath.parquet     the artifact's ordered classpath        (optional)
├── detail_refs.parquet   member-level references
├── members.parquet       fields, methods and constructors
├── parameters.parquet    method and constructor parameters
├── refs.parquet          type-level references
└── types.parquet         the artifact's types
```

| Property | Rule |
|----------|------|
| Container | ZIP |
| Entry method | **STORED** — the tables are already compressed; deflating them again only costs CPU |
| Entry layout | flat: one entry per table, named exactly as above, no directories |
| Entry order | sorted by name |
| Required entries | `types`, `members`, `parameters`, `refs`, `detail_refs` (each may hold zero rows) |
| Optional entries | `classpath` — present when the producer knows the artifact's classpath |

**File name.** The archive is named after its artifact key (§3.1): a trailing
`.jar` or `.zip` is dropped, the characters `:`, `/` and `\` are replaced by `_`,
and `.hierograph` is appended.

| Artifact key | Archive file name |
|--------------|-------------------|
| `com.example:billing-core:1.4.0` | `com.example_billing-core_1.4.0.hierograph` |
| `com.example:billing-core:1.4.0:test` | `com.example_billing-core_1.4.0_test.hierograph` |
| `spring-core-7.0.8.jar` | `spring-core-7.0.8.hierograph` |

The file name is only a filesystem-safe label. The authoritative key is the one
stored *inside* the tables.

### 2.2 The exploded artifact

The same tables may sit unzipped in a **directory** named exactly like the archive
would be. This is the exploded form of the artifact:

```
com.example_billing-core_1.4.0.hierograph/
├── classpath.parquet
├── detail_refs.parquet
├── members.parquet
├── parameters.parquet
├── refs.parquet
└── types.parquet
```

The entries follow the archive's rules: flat, named as in §2.1, with the same
required and optional tables. Because the name is the same, the two forms are
interchangeable: zipping the directory's files as STORED entries yields the
archive, unzipping the archive into a directory of its name yields the exploded
form. Archives suit shipping, for example deploying to an artifact repository; the
exploded form suits local work, where nothing is shipped and nothing has to be
unpacked.

### 2.3 The snapshot directory

A snapshot is a directory of artifacts, one per artifact, each an archive or
exploded:

```
snapshot/
├── com.example_billing-api_1.4.0.hierograph          archive (file)
├── com.example_billing-core_1.4.0.hierograph/        exploded (directory)
└── com.example_billing-core_1.4.0_test.hierograph    archive (file)
```

A reader loads every `*.hierograph` entry in the directory — file or directory — in
name order, and treats the union as one graph. The two forms may be mixed freely.

A directory that contains the six table files directly, rather than artifacts, is an
**unpacked** snapshot. It is read the same way, as if it were the content of a
single archive. Its tables may cover several artifacts, because every type row
names its own artifact.

### 2.4 Parquet properties

| Property | Value |
|----------|-------|
| Compression | ZSTD |
| String columns | `BYTE_ARRAY` annotated with the `STRING` logical type (UTF-8) |
| Integer columns | `INT32` |
| Repetition | flat: every column is `REQUIRED` or `OPTIONAL`, with no nesting or lists |
| Row groups | writers currently emit one row group per table; readers must not depend on this |
| Schema root names | `type`, `member`, `parameter`, `ref`, `detail_ref`, `classpath` |

Readers resolve columns **by name**, not by position.

---

## 3. Conventions

### 3.1 Artifact key

The **artifact key** identifies an artifact uniquely within a snapshot. It is an
opaque string; producers choose it as follows:

| Producer | Key | Example |
|----------|-----|---------|
| Maven plugin, main output | `groupId:artifactId:version` (GAV) | `com.example:billing-core:1.4.0` |
| Maven plugin, test output | the GAV with `:test` appended | `com.example:billing-core:1.4.0:test` |
| Command-line scanner | the jar file name or the class-directory name | `spring-core-7.0.8.jar` |

GAV keys keep different versions of the same library apart on one classpath, and
keep the main and test output of a module apart.

### 3.2 Type names

A **type FQN** is the fully-qualified binary name of a type, written with dots
between packages and `$` between nested types:

```
java.lang.String
java.util.Map$Entry
com.example.billing.Invoice$Line
```

Where a column holds a *type reference*, two further rules apply:

- **Arrays are reduced to their element type.** A reference to `String[][]` is a
  reference to `java.lang.String`. Dependency analysis cares about the type named,
  not its dimensionality.
- **Primitives and `void`** appear by their keyword (`int`, `boolean`, `void`) in
  columns that describe a member's shape (§4.3, §4.5). They never appear in
  `refs.parquet`, where only real type dependencies are counted.

### 3.3 Member signature and member key

A member's **signature** is its Java-source spelling, built from its descriptor:

| Member | Signature form | Example |
|--------|----------------|---------|
| Field | `<type> <name>` | `java.util.List items` |
| Method | `<return type> <name>(<param types>)` | `java.math.BigDecimal total(java.lang.String,int[])` |
| Constructor | `void <init>(<param types>)` | `void <init>(java.lang.String)` |
| Static initializer | `void <clinit>()` | `void <clinit>()` |

Parameter types are joined with `,` and no spaces; array types keep their `[]`;
generic type arguments are not part of the signature (the descriptor is erased).

A **member key** identifies a member globally:

```
<owner type FQN>#<signature>

com.example.billing.Invoice#java.math.BigDecimal total(java.lang.String,int[])
```

Because the signature includes the parameter types, overloads get distinct keys.

### 3.4 Line numbers

Line numbers come from the class file's `LineNumberTable`. They are 1-based source
lines and are absent (`null`) when the class was compiled without line debug
information, or when the member has no code (fields, abstract and native methods).

---

## 4. Tables

### 4.1 `types.parquet`

One row per type defined by the artifact: top-level and nested classes,
interfaces, enums, records and annotation types. This table is the anchor of the
snapshot. Every other table hangs off the types listed here, and a type FQN that
appears elsewhere but not in any `types` table is *external* (§6.2).

`module-info` classes and anything under `META-INF/` are not scanned. When a jar
is a multi-release jar, each class is recorded once, in the newest version it
contains.

| Column | Type | Null? | Description |
|--------|------|-------|-------------|
| `artifact` | string | no | Key of the artifact that defines the type (§3.1). |
| `fqn` | string | no | The type's fully-qualified name (§3.2). |
| `kind` | string | no | What kind of type this is: `CLASS`, `INTERFACE`, `ENUM`, `RECORD` or `ANNOTATION`. |
| `source_file` | string | yes | Simple name of the source file the type was compiled from (`Invoice.java`, `Billing.kt`). |
| `class_hash` | string | yes | SHA-256 of the class file's bytes, as 64 lower-case hex digits. |
| `modifiers` | int32 | no | The type's modifiers, packed into a bit set (§5.1). `0` means package-private with no flags. |

**`artifact`** records ownership. A table normally holds a single artifact, but the
column makes every row self-describing, so concatenated or unpacked tables stay
unambiguous. It is also how two artifacts that define the *same* FQN (a shaded
copy, two versions of one library) are kept apart.

**`fqn`** is the type's identity and the join key for every other table:
`members.owner_fqn`, `refs.from_type`, `detail_refs.from_type`.

**`kind`** is determined from the access flags in this order of precedence: an
annotation type is `ANNOTATION`; an enum is `ENUM`; a class whose superclass is
`java.lang.Record` is `RECORD`; an interface is `INTERFACE`; anything else is
`CLASS`. Tools use it to filter and display types, for example "all interfaces in
this package".

**`source_file`** links the type back to its source, so that a tool can point a
user at a file. Several types (nested classes, Kotlin file facades) can share one
source file. It is `null` when the class carries no `SourceFile` attribute.

**`class_hash`** fingerprints the exact bytecode a row was scanned from. A change
detector compares it with the hash of the current class file to find types that
need rescanning, so an incremental update touches only the classes that actually
changed.

**`modifiers`** carries the type's visibility and its `abstract` / `final` /
`static` flags. For a nested type these are its *declared* modifiers, taken from
the class's own `InnerClasses` entry; the class header alone cannot express
`private`, `protected` or `static` on a nested type. Interfaces and annotation
types carry `abstract`, as the JVM defines them.

---

### 4.2 `members.parquet`

One row per declared field, method and constructor, including the static
initializer and compiler-generated (synthetic, bridge) members. Inherited members
are not repeated: a member appears only under the type that declares it.

| Column | Type | Null? | Description |
|--------|------|-------|-------------|
| `owner_fqn` | string | no | FQN of the declaring type. |
| `member_key` | string | no | The member's global key, `<owner_fqn>#<signature>` (§3.3). |
| `kind` | string | no | `FIELD`, `METHOD` or `CONSTRUCTOR`. |
| `name` | string | no | The member's simple name: `items`, `total`, `<init>`, `<clinit>`. |
| `signature` | string | no | The member's signature (§3.3). |
| `modifiers` | int32 | yes | Packed modifier bits (§5.1). `null` stands for `0`. |
| `line_from` | int32 | yes | First source line of the member's code. |
| `line_to` | int32 | yes | Last source line of the member's code. |
| `bridge_target` | string | yes | For a bridge method, the member key of the method it forwards to. |

**`owner_fqn`** groups members by type. It is the column a reader filters on to
fetch "all members of these types" without scanning the whole table.

**`member_key`** is the member's identity. Member-level references (§4.5) point at
it, parameters (§4.3) hang off it, and tools address a single method or field by
it.

**`kind`** separates fields from executables, and constructors from ordinary
methods. A member named `<init>` is a `CONSTRUCTOR`; `<clinit>` is a `METHOD`.

**`name`** is stored separately from the signature because tools search and
display members by plain name ("find `total`") and should not have to parse it out
of the signature.

**`signature`** is the human-readable, overload-safe description of the member. It
is the second half of `member_key` and is what tools show as the member's label.

**`modifiers`** carries visibility and storage flags (`static`, `final`,
`abstract`, `synchronized`, `native`, `transient`, `volatile`, `default`), plus the
compiler markers `bridge` and `synthetic`. Tools use the markers to hide
compiler-generated members and to reason correctly about covariant overrides.
`default` is set for an interface method that has a body and is neither static nor
private.

**`line_from` / `line_to`** are the lowest and highest line numbers found in the
method's code. Together they locate the member in its source file. Both are `null`
for fields and for methods without code.

**`bridge_target`** exists because the compiler implements a covariant or generic
override as two methods: the real one, and a synthetic *bridge* with the erased
signature that just forwards to it. Recording the forwarding target lets
load-time resolution point override and virtual-call edges at the real
implementation rather than at the bridge. It is `null` for every non-bridge
member.

---

### 4.3 `parameters.parquet`

One row per declared parameter of a method or constructor, sorted by
(`method_key`, `index`). Parameters are a detail-level concern: they are read on
demand, one member at a time, never loaded wholesale. The sort order lets that
lookup skip everything but the member's own rows.

| Column | Type | Null? | Description |
|--------|------|-------|-------------|
| `method_key` | string | no | Member key of the owning method or constructor (§3.3). |
| `index` | int32 | no | 0-based position in the declared parameter list. |
| `name` | string | no | The parameter's name. |
| `type_fqn` | string | no | The parameter's type, as a type reference (§3.2). |

**`method_key`** joins a parameter to its member in `members.parquet`.

**`index`** orders the parameters. It counts declared parameters only; the
implicit `this` of an instance method is not a parameter.

**`name`** is taken, in order of preference, from the `MethodParameters` attribute
(`javac -parameters`), then from the local-variable table (debug information), and
otherwise falls back to `arg<index>` (`arg0`, `arg1`, …). Named parameters make
method details readable and give tool schemas meaningful argument names.

**`type_fqn`** is the parameter's erased type reduced to its element type, so
primitives appear by keyword (`int`) and a `String[]` parameter is recorded as
`java.lang.String`. The exact spelling, with dimensions, is in the member's
signature; this column answers "which type does this parameter depend on".

---

### 4.4 `refs.parquet`

The **type-level dependency graph**: one row per distinct (source type, target
type, reference kind) triple, with the number of times it occurs. This is the
table Hierograph's structural analysis is built on: dependency matrices, layering
and cycle detection, and impact analysis all aggregate these rows up the package
and module hierarchy.

| Column | Type | Null? | Description |
|--------|------|-------|-------------|
| `from_type` | string | no | FQN of the type whose bytecode contains the reference. |
| `to_type` | string | no | FQN of the referenced type (§3.2). |
| `kind` | string | no | How the target is referenced (§5.2). |
| `count` | int32 | yes | How many times this (from, to, kind) reference occurs in the source type. |

**`from_type`** is always a type defined in the same archive. References made by
members are attributed to the type that declares the member, which is what makes
this a type-to-type graph. Readers filter on it to fetch a type's outgoing
dependencies.

**`to_type`** may be any type: defined in the same artifact, in another artifact of
the snapshot, or nowhere in the snapshot at all (an external type, §6.2).
Self-references are not recorded; primitives and `void` are never targets.

**`kind`** keeps the *nature* of a dependency, so analysis can tell inheritance
(`EXTENDS`, `IMPLEMENTS`) from use in a signature (`PARAM_TYPE`, `RETURN_TYPE`) from
use in code (`INVOKES`, `INSTANTIATES`). The same pair of types produces one row per
kind that connects them.

**`count`** is the dependency's weight: two calls to `Money.add` and one to
`Money.of` within `Invoice` give one `INVOKES` row from `Invoice` to `Money` with
count `3`. Weights let a matrix show how strongly two parts are coupled rather than
merely whether they are. A `null` count reads as `1`.

Rows appear grouped by `from_type`, in the order the references were first
encountered.

---

### 4.5 `detail_refs.parquet`

The **member-level dependency graph**: one row per individual reference from a
member (or a type) to a member or a type, with its source line. This table answers
the precise questions the type-level graph cannot: *which* method calls *which*
method, which code writes a field, where an exception is thrown.

It is sorted by (`from_type`, `from_key`, `to_type`, `to_key`, `relationship`), so
a query scoped to a set of types reads only the rows of those types.

| Column | Type | Null? | Description |
|--------|------|-------|-------------|
| `from_key` | string | no | Key of the referencing element: a member key, or a type FQN for a type-level annotation. |
| `from_type` | string | no | FQN of the type that declares `from_key`. |
| `to_key` | string | no | Key of the referenced element: a member key, or a type reference. |
| `to_type` | string | no | FQN of the type that owns `to_key`, or the referenced type itself. |
| `relationship` | string | no | What the reference means (§5.3). |
| `line` | int32 | yes | Source line of the reference, where meaningful. |

**`from_key`** is the precise source of the reference: normally the method or
field it occurs in. For an annotation on the type itself it is the type's FQN, so
type-level annotations are as queryable as member-level ones.

**`from_type`** is redundant with `from_key` by design. It is the sort and filter
column, and it lets a reader roll member-level edges up to types without parsing
keys.

**`to_key`** is the precise target. For `calls`, `reads_field` and `writes_field` it
is a member key, named by the *instruction's owner*: a call compiled against
`List.size()` targets `java.util.List#int size()`, whichever class implements it at
run time. Resolving such a call to its possible implementations depends on the
full set of loaded artifacts, so it is done at load time (§6.3). For the
type-shaped relationships (`returns`, `parameter_type`, `has_type`, `throws`,
`raises`, `annotated_by`) the target is a type reference, and `to_key` equals
`to_type`. Primitives and `void` are recorded here, so a member's full shape is
represented.

**`to_type`** is the type side of the target. It always holds a type FQN or a
primitive, so a reader can filter incoming references ("who uses `Money`?") on a
single column.

**`relationship`** is the meaning of the edge, using Hierograph's detail
vocabulary (§5.3).

**`line`** places the reference in the source. For references made by an
instruction (`calls`, `reads_field`, `writes_field`) it is the line of that
instruction. For the relationships that describe a method's signature (`returns`,
`parameter_type`, `throws`, `raises`) it is the method's first line. It is `null`
for annotations and field types, and whenever no line information was compiled in.

A detail reference belongs to the archive of the artifact that defines its
`from_type`.

---

### 4.6 `classpath.parquet`

The artifact's **classpath**, in resolution order: one row per entry, with the
artifact itself at index `0`. It is optional, written when the producer knows the
classpath (the Maven plugin always does), and it is what lets a reader bind a type
name to the *right* artifact when several define it (§6.1).

| Column | Type | Null? | Description |
|--------|------|-------|-------------|
| `artifact_key` | string | no | Key of the artifact this classpath belongs to (the same on every row). |
| `name` | string | no | The artifact's display name (the same on every row). |
| `index` | int32 | no | Position on the classpath; `0` is the artifact itself, then its dependencies in order. |
| `dep_key` | string | no | Artifact key of the entry at this position. |
| `internal` | int32 | no | `1` if the entry is part of the same build (a sibling module), `0` if it is an external dependency. |

**`artifact_key`** ties the classpath to its owner. It equals the `artifact` column
of the archive's `types` rows.

**`name`** is the short, human-readable label shown for the artifact in the
hierarchy. A GAV key names an artifact precisely but reads poorly in a tree, so
for a Maven module this is its `artifactId`, and for a test archive
`<artifactId> (test)`. When no classpath table is present, readers use the
artifact key as the name.

**`index`** is the classpath order, and order is the whole point of the table. As
in the JVM, the first entry that defines a class wins. Index `0` is always the
artifact itself, since its own classes shadow those of its dependencies. For a test
archive, index `1` is the module's main artifact, followed by the full test
classpath.

**`dep_key`** names each classpath entry using the same key scheme as the archives,
so an entry can be matched to the archive that provides it.

**`internal`** separates the build's own modules from third-party dependencies. It
lets tools distinguish "our code" from libraries, for example when deciding which
unresolved references deserve attention. Index `0` is always internal.

---

## 5. Vocabularies

### 5.1 Modifier bits

`types.modifiers` and `members.modifiers` share one bit layout. Package-private
visibility is the absence of all three visibility bits.

| Bit | Value | Flag | Applies to |
|----:|------:|------|-----------|
| 0 | `0x0001` | `public` | types, members |
| 1 | `0x0002` | `protected` | types, members |
| 2 | `0x0004` | `private` | types, members |
| 3 | `0x0008` | `static` | types, members |
| 4 | `0x0010` | `final` | types, members |
| 5 | `0x0020` | `abstract` | types, methods |
| 6 | `0x0040` | `synchronized` | methods |
| 7 | `0x0080` | `native` | methods |
| 8 | `0x0100` | `transient` | fields |
| 9 | `0x0200` | `volatile` | fields |
| 10 | `0x0400` | `default` | interface methods |
| 11 | `0x0800` | `bridge` | methods (compiler-generated) |
| 12 | `0x1000` | `synthetic` | members (compiler-generated) |

Types use bits 0–5 only. This layout is Hierograph's own rather than the JVM's
access-flag layout, so that `default`, which the JVM does not encode as a flag,
fits alongside the rest.

### 5.2 Type-level reference kinds (`refs.kind`)

| Kind | Group | The source type … |
|------|-------|-------------------|
| `EXTENDS` | structural | has the target as its superclass |
| `IMPLEMENTS` | structural | implements (or, as an interface, extends) the target interface |
| `ANNOTATED_BY` | structural | carries the target as an annotation on the type itself |
| `FIELD_TYPE` | signature | declares a field of the target type |
| `PARAM_TYPE` | signature | declares a method parameter of the target type |
| `RETURN_TYPE` | signature | declares a method returning the target type |
| `THROWS` | signature | declares the target in a method's `throws` clause |
| `MEMBER_ANNOTATED_BY` | signature | carries the target as an annotation on a field, method or parameter |
| `TYPE_ARGUMENT` | signature | names the target inside a generic type argument or bound (`List<Money>` → `Money`) |
| `ANNOTATION_VALUE` | signature | uses the target in an annotation value: an enum constant, a class value, or a nested annotation |
| `INVOKES` | body | calls a method or constructor owned by the target, including lambda and method-reference implementations |
| `READS` | body | reads a field owned by the target |
| `WRITES` | body | writes a field owned by the target |
| `INSTANTIATES` | body | creates an instance or an array of the target |
| `CAST` | body | casts to, or tests `instanceof` against, the target |
| `CATCHES` | body | catches the target exception type |
| `CLASS_LITERAL` | body | uses the target as a class literal (`Money.class`) |

The **structural** kinds describe how a type is defined, **signature** kinds come
from declarations, and **body** kinds from the bytecode of method bodies. The
erased types of a generic signature are counted under their position's kind
(`FIELD_TYPE`, `PARAM_TYPE`, …); `TYPE_ARGUMENT` counts only the types that appear
*inside* the angle brackets, so no type is counted twice for the same position.

### 5.3 Member-level relationships (`detail_refs.relationship`)

| Relationship | From | To | Meaning |
|--------------|------|----|---------|
| `calls` | method | method | invokes the target; also links a lambda or method reference to its implementation method |
| `reads_field` | method | field | reads the target field |
| `writes_field` | method | field | assigns the target field |
| `returns` | method | type | returns the target type |
| `parameter_type` | method | type | takes a parameter of the target type (once per distinct type) |
| `throws` | method | type | declares the target in its `throws` clause |
| `raises` | method | type | executes `throw new <target>(…)` in its body |
| `has_type` | field | type | is declared with the target type |
| `annotated_by` | type, method, field | type | carries the target annotation |

`throws` and `raises` are complementary. The first is the declared contract; the
second is what the body actually throws. `raises` records only a freshly
constructed exception that is thrown at once; a stored, rethrown or
factory-produced exception is not attributed.

Relationships that depend on more than one class file, such as `overrides`,
resolved virtual-call targets, and the inverse views (`read_by`, `written_by`,
callers), are never stored. They are derived at load time (§6.3).

---

## 6. Loading semantics

A snapshot is a set of facts. The rules below turn the facts of all its archives
into one consistent graph. They are part of the format's contract, because they
define what the stored keys mean.

### 6.1 Binding type names to artifacts

A type FQN in a reference is only a name; the artifact that provides it is decided
at load time. When exactly one loaded artifact defines the FQN, the reference
binds to it. When several do, the reader resolves the name **for the referencing
artifact**:

1. If the referencing artifact has a `classpath` table, the reference binds to the
   first entry on that classpath that defines the FQN. This is the JVM's own rule.
2. Otherwise, it binds to the referencing artifact itself if that artifact defines
   the FQN, and failing that to the defining artifact with the lexicographically
   smallest key, so the choice is deterministic.

### 6.2 External types

A type that is referenced but defined by no loaded archive is **external**. The
reader represents it as a placeholder node, created from its incoming references.
If an archive that defines it is loaded later, the placeholder is replaced by the
real type and every edge pointing at it stays valid. A reader may decide by policy
which external types become placeholders (for example, omitting
`java.lang.Object`). The references themselves are facts and always remain in the
tables.

### 6.3 Derived facts

Once all archives are loaded, the reader computes the cross-artifact facts:
method overrides (using `bridge_target` to look through bridge methods), the
possible run-time targets of virtual calls, and inverse relationships. Because
these are computed over whichever archives are loaded together, the same archive is
correct in every combination it is loaded in.

### 6.4 Robustness

- A table that is absent from an archive is read as empty.
- Readers ignore columns they do not know, and address known columns by name.
- Rows may be read in any order; only `parameters` and `detail_refs` guarantee a
  sort order (§4.3, §4.5).

---

## 7. Example

The class

```java
package com.example.billing;

public final class Invoice {
    private final java.util.List<Line> lines = new java.util.ArrayList<>();

    public Money total(String currency) {          // line 12
        Money sum = Money.zero(currency);          // line 13
        for (Line line : lines) sum = sum.add(line.amount());
        return sum;                                // line 15
    }
}
```

in artifact `com.example:billing-core:1.4.0` produces, among others, these rows.

**`types.parquet`**

| artifact | fqn | kind | source_file | class_hash | modifiers |
|----------|-----|------|-------------|------------|----------:|
| `com.example:billing-core:1.4.0` | `com.example.billing.Invoice` | `CLASS` | `Invoice.java` | `9f2c…e41a` | `17` *(public, final)* |

**`members.parquet`**

| owner_fqn | member_key | kind | name | signature | modifiers | line_from | line_to |
|-----------|------------|------|------|-----------|----------:|----------:|--------:|
| `…Invoice` | `…Invoice#java.util.List lines` | `FIELD` | `lines` | `java.util.List lines` | `20` | | |
| `…Invoice` | `…Invoice#com.example.billing.Money total(java.lang.String)` | `METHOD` | `total` | `com.example.billing.Money total(java.lang.String)` | `1` | `13` | `15` |

**`parameters.parquet`**

| method_key | index | name | type_fqn |
|------------|------:|------|----------|
| `…Invoice#com.example.billing.Money total(java.lang.String)` | `0` | `currency` | `java.lang.String` |

**`refs.parquet`**

| from_type | to_type | kind | count |
|-----------|---------|------|------:|
| `…Invoice` | `java.lang.Object` | `EXTENDS` | `1` |
| `…Invoice` | `java.util.List` | `FIELD_TYPE` | `1` |
| `…Invoice` | `com.example.billing.Invoice$Line` | `TYPE_ARGUMENT` | `1` |
| `…Invoice` | `com.example.billing.Money` | `RETURN_TYPE` | `1` |
| `…Invoice` | `com.example.billing.Money` | `INVOKES` | `2` |

**`detail_refs.parquet`**

| from_key | from_type | to_key | to_type | relationship | line |
|----------|-----------|--------|---------|--------------|-----:|
| `…Invoice#java.util.List lines` | `…Invoice` | `java.util.List` | `java.util.List` | `has_type` | |
| `…Invoice#…Money total(java.lang.String)` | `…Invoice` | `…Money#…Money zero(java.lang.String)` | `…Money` | `calls` | `13` |
| `…Invoice#…Money total(java.lang.String)` | `…Invoice` | `…Money#…Money add(…Money)` | `…Money` | `calls` | `14` |
| `…Invoice#…Money total(java.lang.String)` | `…Invoice` | `…Invoice#java.util.List lines` | `…Invoice` | `reads_field` | `14` |
| `…Invoice#…Money total(java.lang.String)` | `…Invoice` | `com.example.billing.Money` | `com.example.billing.Money` | `returns` | `13` |
| `…Invoice#…Money total(java.lang.String)` | `…Invoice` | `java.lang.String` | `java.lang.String` | `parameter_type` | `13` |

**`classpath.parquet`**

| artifact_key | name | index | dep_key | internal |
|--------------|------|------:|---------|---------:|
| `com.example:billing-core:1.4.0` | `billing-core` | `0` | `com.example:billing-core:1.4.0` | `1` |
| `com.example:billing-core:1.4.0` | `billing-core` | `1` | `com.example:billing-api:1.4.0` | `1` |
| `com.example:billing-core:1.4.0` | `billing-core` | `2` | `com.google.guava:guava:33.0.0-jre` | `0` |

*(`…` abbreviates `com.example.billing.` for readability; the tables store full
names. The example shows a selection of rows: the loop's iterator calls, for
instance, add further `INVOKES` and `calls` rows.)*
