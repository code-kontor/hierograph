# Hierograph GraphStore — Parquet Snapshot Format

A **snapshot** is the persistent form of Hierograph's GraphStore: everything the
bytecode scanner learned about a set of JVM artifacts, written as a handful of flat
Parquet tables. A snapshot can be rebuilt into an in-memory graph at any time, and
it can be queried directly on disk — the detail-level tools read members,
parameters and dependencies straight from the tables instead of holding
them in memory.

This document defines that format: the files, the packaging, and every column —
what it describes and why it exists. The Kotlin API that writes and reads it is
described in the [archive module specification](hierograph-graphstore-archive.md).

---

## Contents

1. [Design principles](#1-design-principles)
2. [Layout](#2-layout)
3. [Conventions](#3-conventions)
4. [Tables](#4-tables)
   - [4.1 `types.parquet`](#41-typesparquet)
   - [4.2 `members.parquet`](#42-membersparquet)
   - [4.3 `parameters.parquet`](#43-parametersparquet)
   - [4.4 `dependencies.parquet`](#44-dependenciesparquet)
   - [4.5 `classpath.parquet`](#45-classpathparquet)
5. [Vocabularies](#5-vocabularies)
6. [Loading semantics](#6-loading-semantics)
7. [Example](#7-example)

---

## 1. Design principles

**Facts, not conclusions.** A snapshot records only what a single class file can
answer on its own: what a type declares, which types and members it names, and
where. Every dependency is stored once, as a detail fact; the type-level aggregate
is rolled up from those facts when a snapshot is loaded (§6.4), so it can never
disagree with them. Anything that depends on *which other artifacts are present* — override
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
(`CLASS`, `METHOD`, `calls`), not as ordinals. Dictionary encoding makes the
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
├── dependencies.parquet  every dependency, one detail fact per row
├── members.parquet       fields, methods and constructors
├── parameters.parquet    method and constructor parameters
└── types.parquet         the artifact's types
```

| Property | Rule |
|----------|------|
| Container | ZIP |
| Entry method | **STORED** — the tables are already compressed; deflating them again only costs CPU |
| Entry layout | flat: one entry per table, named exactly as above, no directories |
| Entry order | sorted by name |
| Required entries | `types`, `members`, `parameters`, `dependencies` (each may hold zero rows) |
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
├── dependencies.parquet
├── members.parquet
├── parameters.parquet
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

A directory that contains the table files directly, rather than artifacts, is an
**unpacked** snapshot. It is read the same way, as if it were the content of a
single archive. Its tables may cover several artifacts, because every type row
names its own artifact. The two kinds of directory do not mix: a directory that
holds any `*.hierograph` entry is a snapshot of artifacts, and table files beside
those entries are ignored.

**Containers.** A `.hierograph` file whose entries are themselves `.hierograph`
archives, rather than tables, is a **container** — the shape `hierograph:collect`
produces. A reader treats each inner archive as an artifact of the snapshot, in
entry-name order. A container may sit in a snapshot directory next to other
artifacts, and a single `.hierograph` file — archive or container — may also be
given in place of a snapshot directory.

**Reading in place.** Readers never extract an archive. Because entries are STORED
(§2.1), every table — also every table of an archive inside a container — is a
contiguous byte range of the file, and a reader reads it there. An entry that is not
STORED, as in an archive repacked by another tool, is inflated into memory while it
is read. Archives in the ZIP64 format are not supported; one artifact's tables are
far below its limits.

### 2.4 Parquet properties

| Property | Value |
|----------|-------|
| Compression | ZSTD |
| String columns | `BYTE_ARRAY` annotated with the `STRING` logical type (UTF-8) |
| Integer columns | `INT32` |
| Repetition | flat: every column is `REQUIRED` or `OPTIONAL`, with no nesting or lists |
| Row groups | writers currently emit one row group per table; readers must not depend on this |
| Schema root names | `type`, `member`, `parameter`, `dependency`, `classpath` |
| Footer key-value metadata | `hierograph.format.version` = `2` in every table (§6.5) |

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
  columns that describe a member's shape (§4.3, §4.4). They never count in the
  type-level aggregate (§6.4), where only real type dependencies are counted.

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
`members.owner_fqn`, `dependencies.from_type`.

**`kind`** is determined from the access flags in this order of precedence: an
annotation type is `ANNOTATION`; an enum is `ENUM`; a class whose superclass is
`java.lang.Record` is `RECORD`; an interface is `INTERFACE`; anything else is
`CLASS`. Tools use it to filter and display types, for example "all interfaces in
this package". These five names are the whole vocabulary of the column for the
format version (§6.5).

**`source_file`** links the type back to its source, so that a tool can point a
user at a file. Several types (nested classes, Kotlin file facades) can share one
source file. It is `null` when the class carries no `SourceFile` attribute.

**`class_hash`** fingerprints the exact bytecode a row was scanned from. A change
detector compares it with the hash of the current class file to find types that
need rescanning, so an incremental update touches only the classes that actually
changed.

**`modifiers`** carries the type's visibility, its `abstract` / `final` /
`static` flags, and whether it declares type parameters (`generic`, §5.1). For a nested type these are its *declared* modifiers, taken from
the class's own `InnerClasses` entry; the class header alone cannot express
`private`, `protected` or `static` on a nested type. Interfaces and annotation
types carry `abstract`, as the JVM defines them.

---

### 4.2 `members.parquet`

One row per declared field, method and constructor, including the static
initializer and compiler-generated (synthetic, bridge) members. Inherited members
are not repeated: a member appears only under the type that declares it.

The table is sorted by `owner_fqn`. Within a type, the members keep the order in
which the class file declares them.

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

**`owner_fqn`** groups members by type. It is the sort and filter column: a reader
fetches "all members of these types" by it, and because the table is sorted on it,
column statistics let the reader skip the data of other types.

**`member_key`** is the member's identity. Dependencies (§4.4) point at
it, parameters (§4.3) hang off it, and tools address a single method or field by
it.

**`kind`** separates fields from executables, and constructors from ordinary
methods. A member named `<init>` is a `CONSTRUCTOR`; `<clinit>` is a `METHOD`.
These three names are the whole vocabulary of the column for the format version
(§6.5).

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
| `annotations` | string | yes | The parameter's annotation types, each once, separated by spaces; `null` for none. |

**`method_key`** joins a parameter to its member in `members.parquet`.

**`index`** orders the parameters. It counts declared parameters only; the
implicit `this` of an instance method is not a parameter.

**`name`** is taken, in order of preference, from the `MethodParameters` attribute
(`javac -parameters`), then from the local-variable table (debug information), and
otherwise falls back to `arg<index>` (`arg0`, `arg1`, …). Named parameters make
method details readable and give tool schemas meaningful argument names.

**`annotations`** records which annotations sit on which parameter (`@NonNull` on the
second, say), which a `parameter_annotated_by` row (§5.2) cannot say because it names
the method only. Annotation types are FQNs and contain no spaces, so one string column
holds the list without a nested type.

**`type_fqn`** is the parameter's erased type reduced to its element type, so
primitives appear by keyword (`int`) and a `String[]` parameter is recorded as
`java.lang.String`. The exact spelling, with dimensions, is in the member's
signature; this column answers "which type does this parameter depend on".

---

### 4.4 `dependencies.parquet`

**Every dependency** the artifact's class files contain: one row per individual fact
from a type or a member to a member or a type, with its source line. The table
answers the precise questions — *which* method calls *which* method, which code
writes a field, where an exception is thrown, which class names which superclass —
and it is also the source of the type-level graph that Hierograph's structural
analysis is built on: dependency matrices, layering, cycle detection and impact
analysis all work on the aggregate a reader rolls up from these rows (§6.4).

It is sorted by (`from_type`, `from_key`, `to_type`, `to_key`, `relationship`), so
a query scoped to a set of types reads only the rows of those types.

| Column | Type | Null? | Description |
|--------|------|-------|-------------|
| `from_key` | string | no | Key of the referencing element: a member key, or the type's FQN for a type-level fact. |
| `from_type` | string | no | FQN of the type that declares `from_key`. |
| `to_key` | string | no | Key of the referenced element: a member key, or a type reference. |
| `to_type` | string | no | FQN of the type that owns `to_key`, or the referenced type itself. |
| `relationship` | string | no | What the dependency means (§5.2). |
| `line` | int32 | yes | Source line of the dependency, where meaningful. |

**`from_key`** is the precise source of the dependency: the method or field it
occurs in, or, for a fact about the type itself (`extends`, `implements`, its own
annotations, type arguments and annotation values), the type's FQN. That a fact is
the type's own is what tells a type's annotations from its members' (§6.4).

**`from_type`** is redundant with `from_key` by design. It is the sort and filter
column, and it lets a reader roll dependencies up to types without parsing keys. It
is always a type defined in the same artifact.

**`to_key`** is the precise target. For `calls`, `reads_field` and `writes_field` it
is a member key, named by the *instruction's owner*: a call compiled against
`List.size()` targets `java.util.List#int size()`, whichever class implements it at
run time. Resolving such a call to its possible implementations depends on the
full set of loaded artifacts, so it is done at load time (§6.3). For every other
relationship the target is a type reference, and `to_key` equals `to_type`.
Primitives and `void` are recorded here, so a member's full shape is represented.

**`to_type`** is the type side of the target. It may be any type: defined in the
same artifact, in another artifact of the snapshot, or nowhere in the snapshot at
all (an external type, §6.2). It always holds a type FQN or a primitive, so a reader
can filter incoming dependencies ("who uses `Money`?") on a single column.

**`relationship`** is the meaning of the dependency (§5.2).

**`line`** places the dependency in the source. For dependencies made by an
instruction (`calls`, `reads_field`, `writes_field`, `instantiates`, `casts`,
`class_literal`, and `raises`, the `throw`) it is the line of that instruction; for
`catches`, the line the handler's code belongs to. For the relationships that
describe a method's signature (`returns`, `parameter_type`, `throws`) it is the
method's first line. It
is `null` for type-level facts, annotations, annotation values, type arguments and
field types, and whenever no line information was compiled in.

Every row is one occurrence: two calls on different lines are two rows, and a method
`f(Map, Map)` has two `parameter_type` rows. Nothing is filtered: references to the
type itself, to primitives and to `void` are rows like any other.

---

### 4.5 `classpath.parquet`

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

**`internal`** separates the build's own modules from third-party dependencies, so
that a consumer can tell "our code" from libraries without knowing the build. It is
recorded for such consumers: loading does not need it, because binding (§6.1) uses
only the order. Index `0` is always internal.

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
| 13 | `0x2000` | `constant` | fields: a compile-time constant |
| 14 | `0x4000` | `generic` | types: declares type parameters |

Types use bits 0–5 and 14. Bits 13 and 14 are not modifiers, and each has one
meaning. Bit 13 says that a field carries a `ConstantValue` attribute, a value the
compiler inlines into every use, so `field_details` can answer `is_constant`.
Bit 14 says that a type's class signature opens with a type-parameter list
(`class Box<T>`), so `type_details` can answer `is_generic`. This layout is Hierograph's own rather than the JVM's
access-flag layout, so that `default`, which the JVM does not encode as a flag,
fits alongside the rest.

### 5.2 Dependency kinds (`dependencies.relationship`)

| Relationship | Group | From | To | The source … |
|--------------|-------|------|----|--------------|
| `extends` | structural | type | type | has the target as its superclass |
| `implements` | structural | type | type | implements (or, as an interface, extends) the target interface |
| `annotated_by` | declaration | type, method, field | type | carries the target annotation |
| `parameter_annotated_by` | declaration | method | type | has a parameter carrying the target annotation (once per annotated parameter) |
| `annotation_value` | declaration | type, method, field | type | uses the target in an annotation value: an enum constant, a class value, or a nested annotation |
| `type_argument` | declaration | type, method, field | type | names the target inside a generic type argument or bound (`List<Money>` → `Money`) |
| `has_type` | declaration | field | type | is declared with the target type |
| `parameter_type` | declaration | method | type | takes a parameter of the target type (once per parameter) |
| `returns` | declaration | method | type | returns the target type |
| `throws` | declaration | method | type | declares the target in its `throws` clause |
| `calls` | body | method | method | invokes the target; also links a lambda or method reference to its implementation method |
| `reads_field` | body | method | field | reads the target field |
| `writes_field` | body | method | field | assigns the target field |
| `instantiates` | body | method | type | creates an instance or an array of the target |
| `casts` | body | method | type | casts to, or tests `instanceof` against, the target |
| `catches` | body | method | type | catches the target exception type |
| `class_literal` | body | method | type | uses the target as a class literal (`Money.class`) |
| `raises` | body | method | type | executes `throw new <target>(…)` in its body; not counted in the aggregate (§6.4) |

The **structural** kinds describe how a type is defined, **declaration** kinds come
from the declarations of the type and its members, and **body** kinds from the
bytecode of method bodies. The erased types of a generic signature are recorded under
their position's kind (`has_type`, `parameter_type`, …); `type_argument` records only
the types that appear *inside* the angle brackets, so no type is recorded twice for
the same position.

`throws` and `raises` are complementary. The first is the declared contract; the
second is what the body actually throws. `raises` records only a freshly
constructed exception that is thrown at once; a stored, rethrown or
factory-produced exception is not attributed.

Relationships that depend on more than one class file, such as `overrides`,
resolved virtual-call targets, and the inverse views (`read_by`, `written_by`,
callers), are never stored. They are derived at load time (§6.3) and never count in
the aggregate (§6.4).

The vocabulary is closed for each format version: a `relationship` holds one of the
names in the table above, and a new relationship comes with a new format version
(§6.5).

---

## 6. Loading semantics

A snapshot is a set of facts. The rules below turn the facts of all its artifacts
into one consistent graph. They are part of the format's contract, because they
define what the stored keys mean.

### 6.1 Binding type names to artifacts

A type FQN in a reference is only a name; the artifact that provides it is decided
at load time. When exactly one loaded artifact defines the FQN, the reference
binds to it. When several do, the reader resolves the name **for the referencing
artifact**:

1. If the referencing artifact has a `classpath` table, the reference binds to the
   first entry on that classpath that defines the FQN. This is the JVM's own rule.
2. Otherwise — the artifact has no `classpath` table, or none of its entries defines
   the FQN — it binds to the referencing artifact itself if that artifact defines
   the FQN, and failing that to the defining artifact with the lexicographically
   smallest key, so the choice is deterministic.

### 6.2 External types

A type that is referenced but defined by no loaded archive is **external**. The
reader represents it as a placeholder node, created from its incoming references.
If an archive that defines it is loaded later, the placeholder is replaced by the
real type and every edge pointing at it stays valid. A reader may filter
which external types become placeholders (for example, omitting
`java.lang.Object`). The references themselves are facts and always remain in the
tables.

### 6.3 Derived facts

Once all archives are loaded, the reader computes the cross-artifact facts:
method overrides (using `bridge_target` to look through bridge methods), the
possible run-time targets of virtual calls, and inverse relationships. Because
these are computed over whichever archives are loaded together, the same archive is
correct in every combination it is loaded in.

### 6.4 The type-level aggregate

The type-to-type graph is not stored; a reader **rolls it up** from each type's
`dependencies` rows when it loads the snapshot. For a type `S`, every row with
`from_type` = `S` counts 1 towards the aggregated dependency from `S` to the row's
`to_type`, under the row's kind, with three exceptions:

- `raises` rows do not count: they refine facts that are already counted, the
  `instantiates` and the constructor `calls` of the thrown exception;
- rows whose `to_type` is `S` itself, a primitive or `void` do not count;
- derived relationships (§6.3) are never stored, so they never count. A relationship
  outside §5.2 does not occur in a readable artifact (§6.5).

`annotated_by` counts as structural when its `from_key` is the type itself and as an
ordinary use when it is a member, so the rollup needs `from_key` alongside
`from_type`, `to_type` and `relationship`. Those four columns are all it reads.

Each aggregated dependency therefore has a weight — the number of rows behind it —
and the kinds those rows have. Two calls to `Money.add` and one to `Money.of` within
`Invoice` give a dependency from `Invoice` to `Money` of weight `3`; weights let a
matrix show how strongly two parts are coupled rather than merely whether they are.
Then `to_type` is bound to a node as §6.1 describes. Because every unit of weight is
one row, a reader can always show the rows behind a weight.

### 6.5 Format version

Every table carries the format version in its footer key-value metadata, under
`hierograph.format.version`. This document describes version `2`.

A reader rejects an artifact it cannot read correctly. It checks the first two
rules before it loads anything, and the third as it reads the rows:

- an artifact with a `refs.parquet` or `detail_refs.parquet` table, or whose `types`
  table carries no version, an older one, or one that is not a number, is **stale**.
  Format 1, the only older format, stored a separate type-level aggregate and a
  `detail_refs` table that lacked part of the dependencies (among them `extends`,
  `implements`, casts and class literals). Rolling the aggregate up from such an
  artifact would give wrong weights, so it has to be **rescanned** from its classes;
- an artifact of a **newer** version needs a newer reader;
- a row whose value is outside its column's vocabulary — a `types.kind` outside §4.1,
  a `members.kind` outside §4.2, a `dependencies.relationship` outside §5.2 — means a
  corrupt table or one from another writer. Skipping it would leave types, members or
  weights out of the graph without anyone noticing, so the artifact is rejected when
  the row is read.

### 6.6 Robustness

- A table that is absent from an archive is read as empty.
- Readers find a table by the file name of its entry and ignore directory entries, so
  an archive that another tool repacked with directories still reads. Writers keep to
  the flat layout of §2.1.
- Readers ignore columns they do not know, and address known columns by name.
- Rows may be read in any order; only `members`, `parameters` and `dependencies`
  guarantee a sort order (§4.2, §4.3, §4.4). An archive written before `members` was
  sorted is still valid; a reader only skips less of it.
- Artifacts are independent of each other, so a reader may read them in parallel and
  combine them afterwards; the result must not depend on the order in which they
  are read.

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

**`dependencies.parquet`**

| from_key | from_type | to_key | to_type | relationship | line |
|----------|-----------|--------|---------|--------------|-----:|
| `…Invoice` | `…Invoice` | `java.lang.Object` | `java.lang.Object` | `extends` | |
| `…Invoice#java.util.List lines` | `…Invoice` | `java.util.List` | `java.util.List` | `has_type` | |
| `…Invoice#java.util.List lines` | `…Invoice` | `…Invoice$Line` | `…Invoice$Line` | `type_argument` | |
| `…Invoice#…Money total(java.lang.String)` | `…Invoice` | `…Money#…Money zero(java.lang.String)` | `…Money` | `calls` | `13` |
| `…Invoice#…Money total(java.lang.String)` | `…Invoice` | `…Money#…Money add(…Money)` | `…Money` | `calls` | `14` |
| `…Invoice#…Money total(java.lang.String)` | `…Invoice` | `…Invoice#java.util.List lines` | `…Invoice` | `reads_field` | `14` |
| `…Invoice#…Money total(java.lang.String)` | `…Invoice` | `com.example.billing.Money` | `com.example.billing.Money` | `returns` | `13` |
| `…Invoice#…Money total(java.lang.String)` | `…Invoice` | `java.lang.String` | `java.lang.String` | `parameter_type` | `13` |

From these rows a reader rolls up (§6.4), among others, a dependency from `Invoice`
to `Money` of weight `3` (one `returns`, two `calls`), and one to `java.lang.Object`
of weight `1` (`extends`). The `reads_field` row targets `Invoice` itself and adds
no weight.

**`classpath.parquet`**

| artifact_key | name | index | dep_key | internal |
|--------------|------|------:|---------|---------:|
| `com.example:billing-core:1.4.0` | `billing-core` | `0` | `com.example:billing-core:1.4.0` | `1` |
| `com.example:billing-core:1.4.0` | `billing-core` | `1` | `com.example:billing-api:1.4.0` | `1` |
| `com.example:billing-core:1.4.0` | `billing-core` | `2` | `com.google.guava:guava:33.0.0-jre` | `0` |

*(`…` abbreviates `com.example.billing.` for readability; the tables store full
names. The example shows a selection of rows: the field initializer's `new
ArrayList<>()`, for instance, adds `instantiates` and `calls` rows to the
constructor, and the loop's iterator adds further `calls` rows.)*
