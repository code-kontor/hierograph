/*
 * Copyright 2026 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.graphstore.jvm.scanner

import dev.hardwood.metadata.CompressionCodec
import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.model.DependencyRollup
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.MemberModifiers
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.TypeModifiers
import io.hierograph.graphstore.jvm.archive.ParquetReader
import io.hierograph.graphstore.jvm.SnapshotLoader
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import javax.tools.ToolProvider

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BytecodeScannerTest {

    private val sources = mapOf(
        "fix/package-info.java" to """
            @Deprecated
            package fix;
        """,
        "fix/Base.java" to """
            package fix;
            @Deprecated
            public abstract class Base implements Runnable {
                protected int count;
                public abstract String name();
                public void run() {}
            }
        """,
        "fix/Shape.java" to """
            package fix;
            public interface Shape {
                double area();
                default String label() { return "shape"; }
            }
        """,
        "fix/Circle.java" to """
            package fix;
            import java.io.IOException;
            import java.util.ArrayList;
            import java.util.List;
            public final class Circle extends Base implements Shape, java.io.Serializable {
                public static final double PI = 3.14;
                private transient volatile List<String> tags = new ArrayList<>();
                private byte[] data;
                private double r;
                public Circle(double r) throws IOException { this.r = r; }
                public String name() { return "circle"; } // NAME_LINE
                public double area() { return PI * r * r; }
                @Deprecated
                public synchronized List<String> tags(int[] xs, String... more) {
                    Runnable l = () -> helper(xs.length);
                    Object shape = Shape.class;
                    String sep = java.io.File.separator;
                    try { tags.add((String) shape); } catch (IllegalStateException e) { count++; }
                    l.run();
                    return tags;
                }
                private static void helper(int n) {}
                static class Nested {}
                enum Color { RED, GREEN }
                record Point(int x, int y) {}
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @interface Marker {}
            }
        """,
        "fix/User.java" to """
            package fix;
            public class User {
                double use(Shape s) { return s.area(); }        // virtual: Shape#area -> Circle#area
                String label(Circle c) { return c.label(); }    // inherited: Circle#label -> Shape#label
            }
        """,
        "fix/Animal.java" to """
            package fix;
            public class Animal {
                public Object self() { return this; }
                void boom() { throw new IllegalStateException("x"); }          // raises, not declared
                void declared() throws java.io.IOException {}                  // declared, never thrown
                void rethrow() { try { boom(); } catch (RuntimeException e) { throw e; } } // rethrow: no new
                void check(int i) {
                    if (i < 0) throw new IllegalArgumentException("negative");  // RAISE_NEGATIVE
                    if (i > 9) throw new IllegalArgumentException("too big");   // RAISE_TOO_BIG
                }
            }
        """,
        "fix/Dog.java" to """
            package fix;
            public class Dog extends Animal { public Dog self() { return this; } } // covariant: javac adds a bridge
        """,
        "fix/Box.java" to """
            package fix;
            public class Box<T extends Comparable<T>> { T value; void put(int slot, @Deprecated T v) { value = v; } }
        """,
        "fix/Ledger.java" to """
            package fix;
            public class Ledger {
                void transfer(Circle from, Circle to, @Deprecated String memo) {}   // one type at two positions
                class Entry { Entry(String text) {} }                               // inner: outer instance first
            }
        """,
    )

    private lateinit var classesDir: Path
    private lateinit var result: ScanResult

    @BeforeAll
    fun scanFixtures(@TempDir tmp: Path) {
        // -g: line numbers and local variable names (no -parameters, so names come from the LVT)
        classesDir = compile(tmp.resolve("classes"), sources, listOf("-g"))
        result = BytecodeScanner().scan(listOf(ScanArtifact.of(classesDir)))
    }

    private fun type(fqn: String): ClassFacts = result.classes.single { it.facts.fqn == fqn }.facts
    /** The type-level aggregate a loader rolls up from [fqn]'s detail facts; the scanner itself reports facts only. */
    private fun refs(fqn: String): List<Ref> = DependencyRollup.refsOf(fqn, result.dependencies.filter { it.fromType == fqn })
    private fun refCount(from: String, to: String, kind: DependencyKind, typeLevel: Boolean? = null): Int? =
        refs(from).singleOrNull { it.toKey == to && it.kind == kind && (typeLevel == null || it.typeLevel == typeLevel) }?.count
    private fun details(fromKey: String, relationship: String): List<Dependency> =
        result.dependencies.filter { it.fromKey == fromKey && it.kind.value == relationship }

    @Test
    fun `types get their kind and real modifiers, nested and interface ones included`() {
        assertThat(result.classes.map { it.facts.fqn }).containsExactlyInAnyOrder(
            "fix.package-info", "fix.Base", "fix.Shape", "fix.Circle",
            "fix.Circle\$Nested", "fix.Circle\$Color", "fix.Circle\$Point", "fix.Circle\$Marker",
            "fix.User", "fix.Animal", "fix.Dog", "fix.Box", "fix.Ledger", "fix.Ledger\$Entry",
        )
        assertThat(result.classes.map { it.artifactKey }.toSet()).containsExactly("classes")

        assertThat(type("fix.Base").kind).isEqualTo(JavaNodeKind.CLASS)
        assertThat(TypeModifiers.modifiers(type("fix.Base").modifiers)).containsExactly("public", "abstract")
        assertThat(type("fix.Shape").kind).isEqualTo(JavaNodeKind.INTERFACE)
        assertThat(TypeModifiers.isAbstract(type("fix.Shape").modifiers)).isTrue() // interfaces are abstract
        assertThat(TypeModifiers.modifiers(type("fix.Circle").modifiers)).containsExactly("public", "final")
        assertThat(type("fix.package-info").kind).isEqualTo(JavaNodeKind.INTERFACE)

        assertThat(TypeModifiers.modifiers(type("fix.Circle\$Nested").modifiers)).containsExactly("package-private", "static")
        assertThat(type("fix.Circle\$Color").kind).isEqualTo(JavaNodeKind.ENUM)
        assertThat(type("fix.Circle\$Point").kind).isEqualTo(JavaNodeKind.RECORD)
        assertThat(type("fix.Circle\$Marker").kind).isEqualTo(JavaNodeKind.ANNOTATION)
        assertThat(type("fix.Circle").sourceFile).isEqualTo("Circle.java")
    }

    @Test
    fun `a type that declares type parameters is marked generic`() {
        assertThat(TypeModifiers.isGeneric(type("fix.Box").modifiers)).isTrue()
        assertThat(TypeModifiers.modifiers(type("fix.Box").modifiers)).containsExactly("public")
        // an enum's signature names a generic supertype (Enum<Color>) but declares no type parameter
        assertThat(TypeModifiers.isGeneric(type("fix.Circle\$Color").modifiers)).isFalse()
        assertThat(TypeModifiers.isGeneric(type("fix.Circle").modifiers)).isFalse()
    }

    @Test
    fun `parameter annotations are kept per parameter and as detail refs`() {
        val put = type("fix.Box").members.single { it.name == "put" }
        assertThat(put.parameters.map { it.annotations }).containsExactly(emptyList(), listOf("java.lang.Deprecated"))
        assertThat(details(put.key, "parameter_annotated_by").map { it.toType }).containsExactly("java.lang.Deprecated")
    }

    @Test
    fun `parameter facts are one per parameter, with its position and name`() {
        val transfer = type("fix.Ledger").members.single { it.name == "transfer" }
        assertThat(details(transfer.key, "parameter_type").map { Triple(it.parameterIndex, it.parameterName, it.toType) })
            .containsExactly(Triple(0, "from", "fix.Circle"), Triple(1, "to", "fix.Circle"), Triple(2, "memo", "java.lang.String"))
        assertThat(details(transfer.key, "parameter_annotated_by").map { Triple(it.parameterIndex, it.parameterName, it.toType) })
            .containsExactly(Triple(2, "memo", "java.lang.Deprecated"))
        // Every other kind carries no parameter.
        assertThat(result.dependencies.filter { it.kind != DependencyKind.PARAMETER_TYPE && it.kind != DependencyKind.PARAMETER_ANNOTATED_BY })
            .allMatch { it.parameterIndex == null && it.parameterName == null }
    }

    @Test
    fun `parameter facts agree with the members' parameters`() {
        for (member in result.classes.flatMap { it.facts.members }.filter { it.parameters.isNotEmpty() }) {
            assertThat(details(member.key, "parameter_type").map { Triple(it.parameterIndex, it.parameterName, it.toType) })
                .`as`(member.key)
                .containsExactlyElementsOf(member.parameters.map { Triple(it.index, it.name, it.typeFqn) })
        }
    }

    @Test
    fun `parameter positions follow the descriptor, synthetic parameters included`() {
        // A non-static inner class's constructor takes the outer instance first; an enum's takes name and ordinal.
        val entry = type("fix.Ledger\$Entry").members.single { it.name == "<init>" }
        assertThat(details(entry.key, "parameter_type").map { it.parameterIndex to it.toType })
            .containsExactly(0 to "fix.Ledger", 1 to "java.lang.String")
        val color = type("fix.Circle\$Color").members.single { it.name == "<init>" }
        assertThat(details(color.key, "parameter_type").map { it.parameterIndex to it.toType })
            .containsExactly(0 to "java.lang.String", 1 to "int")
    }

    @Test
    fun `classHash is the sha256 of the class file`() {
        val bytes = Files.readAllBytes(classesDir.resolve("fix/Circle.class"))
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertThat(type("fix.Circle").classHash).isEqualTo(sha)
    }

    @Test
    fun `members use the snapshot key format, with modifiers, lines and parameters`() {
        val members = type("fix.Circle").members.associateBy { it.key }
        assertThat(members.keys).contains(
            "fix.Circle#void <init>(double)",
            "fix.Circle#java.util.List tags(int[],java.lang.String[])",
            "fix.Circle#byte[] data",
            "fix.Circle#java.util.List tags",
            "fix.Circle#double PI",
        )
        assertThat(type("fix.Circle\$Color").members.map { it.key }).contains("fix.Circle\$Color#void <clinit>()")
        assertThat(members.keys.filter { it.contains("lambda\$tags\$") }).hasSize(1) // synthetic members are kept

        assertThat(members.getValue("fix.Circle#void <init>(double)").kind).isEqualTo(JavaNodeKind.CONSTRUCTOR)
        val tagsMethod = members.getValue("fix.Circle#java.util.List tags(int[],java.lang.String[])")
        assertThat(tagsMethod.name).isEqualTo("tags")
        assertThat(MemberModifiers.methodModifiers(tagsMethod.modifiers)).containsExactly("public", "synchronized")
        assertThat(tagsMethod.parameters.map { it.name to it.typeFqn }).containsExactly("xs" to "int", "more" to "java.lang.String")
        assertThat(MemberModifiers.fieldModifiers(members.getValue("fix.Circle#java.util.List tags").modifiers))
            .containsExactly("private", "transient", "volatile")
        assertThat(MemberModifiers.fieldModifiers(members.getValue("fix.Circle#double PI").modifiers))
            .containsExactly("public", "static", "final")
        assertThat(MemberModifiers.isConstant(members.getValue("fix.Circle#double PI").modifiers)).isTrue()
        assertThat(MemberModifiers.isConstant(members.getValue("fix.Circle#java.util.List tags").modifiers)).isFalse()
        assertThat(MemberModifiers.methodModifiers(type("fix.Shape").members.single { it.name == "label" }.modifiers))
            .containsExactly("public", "default")

        val nameLine = sources.getValue("fix/Circle.java").trimIndent().lines().indexOfFirst { it.contains("NAME_LINE") } + 1
        val name = members.getValue("fix.Circle#java.lang.String name()")
        assertThat(name.lineFrom).isEqualTo(nameLine)
        assertThat(name.lineTo).isEqualTo(nameLine)
    }

    @Test
    fun `parameter names come from MethodParameters when compiled with -parameters, else argN`(@TempDir tmp: Path) {
        val src = mapOf("p/P.java" to "package p; public class P { public void m(int alpha, String beta) {} }")
        fun paramNames(options: List<String>): List<String> {
            val dir = compile(tmp.resolve(options.joinToString("_").ifEmpty { "none" }), src, options)
            return BytecodeScanner().scan(listOf(ScanArtifact.of(dir))).classes.single().facts.members
                .single { it.name == "m" }.parameters.map { it.name }
        }
        assertThat(paramNames(listOf("-parameters", "-g:none"))).containsExactly("alpha", "beta")
        assertThat(paramNames(listOf("-g:none"))).containsExactly("arg0", "arg1")
    }

    @Test
    fun `type-level refs are fine-grained and counted, without primitives or self`() {
        assertThat(refCount("fix.Circle", "fix.Base", DependencyKind.EXTENDS)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "fix.Shape", DependencyKind.IMPLEMENTS)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.io.Serializable", DependencyKind.IMPLEMENTS)).isEqualTo(1)
        assertThat(refCount("fix.Shape", "java.lang.Object", DependencyKind.EXTENDS)).isEqualTo(1) // as in the bytecode
        assertThat(refCount("fix.Base", "java.lang.Deprecated", DependencyKind.ANNOTATED_BY, typeLevel = true)).isEqualTo(1)

        assertThat(refCount("fix.Circle", "java.util.List", DependencyKind.HAS_TYPE)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.util.List", DependencyKind.RETURNS)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.lang.String", DependencyKind.TYPE_ARGUMENT)).isEqualTo(2) // List<String> field + return
        assertThat(refCount("fix.Circle", "java.lang.String", DependencyKind.PARAMETER_TYPE)).isEqualTo(1)   // String... more
        assertThat(refCount("fix.Circle", "java.io.IOException", DependencyKind.THROWS)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.lang.Deprecated", DependencyKind.ANNOTATED_BY, typeLevel = false)).isEqualTo(1)

        assertThat(refCount("fix.Circle", "java.util.ArrayList", DependencyKind.INSTANTIATES)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.lang.String", DependencyKind.CASTS)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.lang.IllegalStateException", DependencyKind.CATCHES)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "fix.Shape", DependencyKind.CLASS_LITERAL)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.io.File", DependencyKind.READS_FIELD)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.lang.Runnable", DependencyKind.CALLS)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.util.List", DependencyKind.CALLS)).isEqualTo(1)

        assertThat(refs("fix.Circle").map { it.toKey }).doesNotContain("int", "double", "void", "fix.Circle")
        // annotation values: the enum constant in @Retention(RetentionPolicy.RUNTIME)
        assertThat(refCount("fix.Circle\$Marker", "java.lang.annotation.Retention", DependencyKind.ANNOTATED_BY, typeLevel = true)).isEqualTo(1)
        assertThat(refCount("fix.Circle\$Marker", "java.lang.annotation.RetentionPolicy", DependencyKind.ANNOTATION_VALUE)).isEqualTo(1)
    }

    @Test
    fun `equal names are one string instance across classes, refs and members`() {
        // Two classes reference java.lang.String: one instance for both type-level ref targets.
        val fromBase = refs("fix.Base").first { it.toKey == "java.lang.String" }.toKey
        val fromCircle = refs("fix.Circle").first { it.toKey == "java.lang.String" }.toKey
        assertThat(fromCircle).isSameAs(fromBase)

        // A call target is the same instance as the key of the member it calls, and as other calls to it.
        val helper = type("fix.Circle").members.single { it.key == "fix.Circle#void helper(int)" }.key
        val calls = result.dependencies.filter { it.toKey == helper }
        assertThat(calls).isNotEmpty
        calls.forEach { assertThat(it.toKey).isSameAs(helper) }

        // A detail ref's target type is the same instance as the type-level ref target.
        val listCall = details("fix.Circle#java.util.List tags(int[],java.lang.String[])", "calls")
            .first { it.toKey.startsWith("java.util.List#") }
        assertThat(listCall.toType).isSameAs(refs("fix.Circle").first { it.toKey == "java.util.List" }.toKey)
    }

    @Test
    fun `member-level refs use the detail vocabulary`() {
        val tags = "fix.Circle#java.util.List tags(int[],java.lang.String[])"
        assertThat(details(tags, "calls").map { it.toKey }).contains(
            "java.util.List#boolean add(java.lang.Object)",
            "java.lang.Runnable#void run()",
            "fix.Circle#void lambda\$tags\$0(int[])", // the lambda's implementation method
        )
        assertThat(details(tags, "calls").first { it.toKey.startsWith("java.util.List#") }.toType).isEqualTo("java.util.List")
        assertThat(details(tags, "reads_field").map { it.toKey })
            .contains("fix.Circle#java.util.List tags", "java.io.File#java.lang.String separator")
        assertThat(details(tags, "calls").all { it.line != null }).isTrue()
        assertThat(details(tags, "returns").map { it.toType }).containsExactly("java.util.List")
        assertThat(details(tags, "parameter_type").map { it.toType }).containsExactly("int", "java.lang.String")
        assertThat(details(tags, "annotated_by").map { it.toType }).containsExactly("java.lang.Deprecated")

        val ctor = "fix.Circle#void <init>(double)"
        assertThat(details(ctor, "returns").map { it.toType }).containsExactly("void")
        assertThat(details(ctor, "throws").map { it.toType }).containsExactly("java.io.IOException")
        assertThat(details(ctor, "writes_field").map { it.toKey }).contains("fix.Circle#double r")
        assertThat(details("fix.Circle#byte[] data", "has_type").map { it.toType }).containsExactly("byte")
        assertThat(details("fix.Circle#void lambda\$tags\$0(int[])", "calls").map { it.toKey }).contains("fix.Circle#void helper(int)")
        // A class-level annotation emits a detail `annotated_by` edge keyed by the type itself (from == type fqn).
        assertThat(details("fix.Circle\$Marker", "annotated_by").map { it.toType })
            .containsExactly("java.lang.annotation.Retention")
    }

    @Test
    fun `type-level facts are detail facts of the type itself`() {
        assertThat(details("fix.Circle", "extends").map { it.toType }).containsExactly("fix.Base")
        assertThat(details("fix.Circle", "implements").map { it.toType }).containsExactly("fix.Shape", "java.io.Serializable")
        assertThat(details("fix.Circle", "extends").single().line).isNull()
        // a type-parameter bound of the class signature: Box<T extends Comparable<T>>
        assertThat(details("fix.Box", "type_argument").map { it.toType }).contains("java.lang.Comparable")
        // type arguments of a field and of a method signature: List<String>
        assertThat(details("fix.Circle#java.util.List tags", "type_argument").map { it.toType }).containsExactly("java.lang.String")
        assertThat(details("fix.Circle#java.util.List tags(int[],java.lang.String[])", "type_argument").map { it.toType })
            .containsExactly("java.lang.String")
        // the enum constant in @Retention(RetentionPolicy.RUNTIME), a fact of the annotated type
        assertThat(details("fix.Circle\$Marker", "annotation_value").map { it.toType })
            .containsExactly("java.lang.annotation.RetentionPolicy")
    }

    @Test
    fun `body facts are detail facts with their lines`() {
        val lines = sources.getValue("fix/Circle.java").trimIndent().lines()
        fun lineOf(text: String) = lines.indexOfFirst { it.contains(text) } + 1
        val tags = "fix.Circle#java.util.List tags(int[],java.lang.String[])"

        assertThat(details(tags, "casts").map { it.toType to it.line }).containsExactly("java.lang.String" to lineOf("try {"))
        assertThat(details(tags, "catches").map { it.toType to it.line })
            .containsExactly("java.lang.IllegalStateException" to lineOf("try {"))
        assertThat(details(tags, "class_literal").map { it.toType to it.line }).containsExactly("fix.Shape" to lineOf("Shape.class"))

        // `new ArrayList<>()` in the field initializer runs in the constructor: an instantiation and a constructor call
        val ctor = "fix.Circle#void <init>(double)"
        assertThat(details(ctor, "instantiates").map { it.toType to it.line })
            .containsExactly("java.util.ArrayList" to lineOf("new ArrayList"))
        assertThat(details(ctor, "calls").map { it.toKey }).contains("java.util.ArrayList#void <init>()")
    }

    @Test
    fun `every fact counts once - per parameter, per annotated parameter, and new is two facts`(@TempDir tmp: Path) {
        val src = mapOf(
            "c/C.java" to """
                package c;
                public class C {
                    void both(java.util.Map a, java.util.Map b) {}
                    void marked(@Deprecated String a, @Deprecated String b) {}
                    Object make() { return new java.util.ArrayList<String>(); }
                    void fail() { throw new IllegalStateException(); }
                }
            """,
        )
        val scan = BytecodeScanner().scan(listOf(ScanArtifact.of(compile(tmp.resolve("c"), src, listOf("-g")))))
        val c = scan.classes.single().facts
        val cRefs = DependencyRollup.refsOf(c.fqn, scan.dependencies.filter { it.fromType == c.fqn })
        fun facts(method: String, relationship: String) =
            scan.dependencies.filter { it.fromKey.startsWith("c.C#") && it.fromKey.contains(" $method(") && it.kind.value == relationship }
        fun count(to: String, kind: DependencyKind) = cRefs.singleOrNull { it.toKey == to && it.kind == kind }?.count

        assertThat(facts("both", "parameter_type").map { it.toType }).containsExactly("java.util.Map", "java.util.Map")
        assertThat(count("java.util.Map", DependencyKind.PARAMETER_TYPE)).isEqualTo(2)

        assertThat(facts("marked", "parameter_annotated_by").map { it.toType }).containsExactly("java.lang.Deprecated", "java.lang.Deprecated")
        assertThat(count("java.lang.Deprecated", DependencyKind.PARAMETER_ANNOTATED_BY)).isEqualTo(2)

        assertThat(facts("make", "instantiates").map { it.toType }).containsExactly("java.util.ArrayList")
        assertThat(facts("make", "calls").map { it.toKey }).containsExactly("java.util.ArrayList#void <init>()")
        assertThat(count("java.util.ArrayList", DependencyKind.INSTANTIATES)).isEqualTo(1)
        assertThat(count("java.util.ArrayList", DependencyKind.CALLS)).isEqualTo(1)

        // `raises` refines the instantiation and the constructor call; it adds no weight of its own
        assertThat(facts("fail", "raises").map { it.toType }).containsExactly("java.lang.IllegalStateException")
        assertThat(count("java.lang.IllegalStateException", DependencyKind.INSTANTIATES)).isEqualTo(1)
        assertThat(count("java.lang.IllegalStateException", DependencyKind.CALLS)).isEqualTo(1)
        assertThat(cRefs.filter { it.toKey == "java.lang.IllegalStateException" }.sumOf { it.count ?: 1 }).isEqualTo(2)
    }

    @Test
    fun `raises captures body-thrown exceptions while throws stays the declared clause`() {
        val boom = "fix.Animal#void boom()"
        assertThat(details(boom, "raises").map { it.toType }).containsExactly("java.lang.IllegalStateException")
        assertThat(details(boom, "throws")).isEmpty()

        val declared = "fix.Animal#void declared()"
        assertThat(details(declared, "throws").map { it.toType }).containsExactly("java.io.IOException")
        assertThat(details(declared, "raises")).isEmpty()

        // A rethrow of a caught exception constructs nothing, so it raises nothing (no false positive).
        assertThat(details("fix.Animal#void rethrow()", "raises")).isEmpty()
    }

    @Test
    fun `every throw new is a raises fact of its own, on the line of the throw`() {
        val lines = sources.getValue("fix/Animal.java").trimIndent().lines()
        fun lineOf(marker: String) = lines.indexOfFirst { it.contains(marker) } + 1

        assertThat(details("fix.Animal#void check(int)", "raises").map { it.toType to it.line }).containsExactly(
            "java.lang.IllegalArgumentException" to lineOf("RAISE_NEGATIVE"),
            "java.lang.IllegalArgumentException" to lineOf("RAISE_TOO_BIG"),
        )
    }

    @Test
    fun `the scanner emits only direct member edges - no overrides or virtual dispatch`() {
        // Hierarchy-dependent edges are derived at load (HierarchyResolution), not by the scanner.
        assertThat(result.dependencies.map { it.kind.value }.toSet()).doesNotContain("overrides")
        // A covariant override's real + bridge methods are both scanned (the resolver relies on this).
        val dogMembers = type("fix.Dog").members.associateBy { it.key }
        assertThat(dogMembers.keys).contains("fix.Dog#fix.Dog self()", "fix.Dog#java.lang.Object self()")
        // The bridge carries its real target so load-time resolution can redirect edges to it.
        assertThat(dogMembers.getValue("fix.Dog#java.lang.Object self()").bridgeTarget).isEqualTo("fix.Dog#fix.Dog self()")
        assertThat(dogMembers.getValue("fix.Dog#fix.Dog self()").bridgeTarget).isNull()
        // Direct calls name the static receiver type; they are not fanned out to subtype implementations.
        assertThat(details("fix.User#double use(fix.Shape)", "calls").map { it.toKey })
            .contains("fix.Shape#double area()")
            .doesNotContain("fix.Circle#double area()")
    }

    @Test
    fun `a JAR scans like its directory, keyed by the JAR file name`(@TempDir tmp: Path) {
        val jar = tmp.resolve("fixtures-1.0.jar")
        val overlayOnly = compile(tmp.resolve("overlay"), mapOf("fix/OnlyNew.java" to "package fix; public class OnlyNew {}"), emptyList())
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.walk(classesDir).filter { Files.isRegularFile(it) }.forEach { file ->
                out.putNextEntry(ZipEntry(classesDir.relativize(file).toString().replace('\\', '/')))
                Files.copy(file, out)
                out.closeEntry()
            }
            // multi-release overlays: a copy of a base class is skipped, an overlay-only class is scanned
            out.putNextEntry(ZipEntry("META-INF/versions/21/fix/Circle.class"))
            out.write(Files.readAllBytes(classesDir.resolve("fix/Circle.class")))
            out.closeEntry()
            out.putNextEntry(ZipEntry("META-INF/versions/21/fix/OnlyNew.class"))
            out.write(Files.readAllBytes(overlayOnly.resolve("fix/OnlyNew.class")))
            out.closeEntry()
        }

        val fromJar = BytecodeScanner().scan(listOf(ScanArtifact.of(jar)))

        assertThat(fromJar.classes.map { it.facts.fqn })
            .containsExactlyInAnyOrderElementsOf(result.classes.map { it.facts.fqn } + "fix.OnlyNew")
        assertThat(fromJar.classes.map { it.artifactKey }.toSet()).containsExactly("fixtures-1.0.jar")
        assertThat(fromJar.dependencies.filter { it.fromType != "fix.OnlyNew" }).hasSameSizeAs(result.dependencies)
    }

    @Test
    fun `a parallel scan gives exactly the sequential result, in input order`(@TempDir tmp: Path) {
        // three artifacts (the same classes under three keys: also covers same-FQN types across artifacts)
        fun copyOf(name: String): Path = tmp.resolve(name).also { target ->
            Files.walk(classesDir).filter { Files.isRegularFile(it) }.forEach { file ->
                val dest = target.resolve(classesDir.relativize(file).toString())
                Files.createDirectories(dest.parent)
                Files.copy(file, dest)
            }
        }
        val artifacts = listOf(ScanArtifact.of(classesDir), ScanArtifact.of(copyOf("lib-a")), ScanArtifact.of(copyOf("lib-b")))

        val sequential = BytecodeScanner(parallelism = 1).scan(artifacts)
        val parallel = BytecodeScanner(parallelism = 4).scan(artifacts)

        assertThat(parallel).isEqualTo(sequential)
        assertThat(parallel.classes.map { it.artifactKey }.distinct()).containsExactly("classes", "lib-a", "lib-b")
    }

    @Test
    fun `a single artifact scanned in parallel gives exactly the sequential result`() {
        val artifacts = listOf(ScanArtifact.of(classesDir))

        assertThat(BytecodeScanner(parallelism = 4).scan(artifacts)).isEqualTo(BytecodeScanner(parallelism = 1).scan(artifacts))
    }

    @Test
    fun `scanByKey splits the scan by key, in order of first appearance`(@TempDir tmp: Path) {
        val empty = Files.createDirectories(tmp.resolve("empty"))
        val artifacts = listOf(
            ScanArtifact("b", classesDir),
            ScanArtifact("a", empty),
            ScanArtifact("b", classesDir),
        )

        val byKey = BytecodeScanner(parallelism = 4).scanByKey(artifacts)

        assertThat(byKey.keys).containsExactly("b", "a")
        assertThat(byKey.getValue("a")).isEqualTo(ScanResult(emptyList(), emptyList()))
        assertThat(byKey.getValue("b")).isEqualTo(BytecodeScanner(parallelism = 1).scan(artifacts))
    }

    @Test
    fun `every fact names its artifact, so copies of a class keep their own facts in the snapshot`(@TempDir tmp: Path) {
        // The same classes in two artifacts, like two versions of one library: every FQN is defined twice.
        val twice = BytecodeScanner().scan(listOf(ScanArtifact("app", classesDir), ScanArtifact("copy", classesDir)))
        val once = result.dependencies.map { it.copy(fromArtifact = null) }

        assertThat(twice.dependencies.groupBy({ it.fromArtifact }, { it.copy(fromArtifact = null) }))
            .containsOnlyKeys("app", "copy")
            .allSatisfy { _, facts -> assertThat(facts).containsExactlyInAnyOrderElementsOf(once) }

        twice.writeTo(tmp, CompressionCodec.UNCOMPRESSED)
        val reader = ParquetReader()
        for (artifact in listOf("app", "copy")) {
            assertThat(reader.readDependencies(tmp.resolve("$artifact.hierograph")).map { it.copy(fromArtifact = null) })
                .describedAs("the facts in $artifact.hierograph")
                .containsExactlyInAnyOrderElementsOf(once)
        }
    }

    @Test
    fun `the result round-trips through a Parquet snapshot into the GraphStore`(@TempDir tmp: Path) {
        result.writeTo(tmp, CompressionCodec.UNCOMPRESSED)

        val store = GraphStore()
        SnapshotLoader().load(tmp, store)

        assertThat(store.typeByFqn("fix.Circle")!!.placeholder).isFalse
        assertThat(store.memberByKey("fix.Circle#java.lang.String name()")).isNotNull
        val edges = store.typeDependencies().filter { it.fromFqn == "fix.Circle" }.associateBy { it.toFqn }
        assertThat(edges.getValue("fix.Base").extends).isTrue()
        // weight = number of references: the sum of the counts over all ref kinds to that target
        val listRefs = refs("fix.Circle").filter { it.toKey == "java.util.List" }
        assertThat(edges.getValue("java.util.List").weight).isEqualTo(listRefs.sumOf { it.count ?: 1 })
        assertThat(listRefs.map { it.kind }).contains(DependencyKind.HAS_TYPE, DependencyKind.RETURNS, DependencyKind.CALLS)
        assertThat(ParquetReader().readDependencies(tmp, fromTypes = listOf("fix.Circle"))).isNotEmpty()
        assertThat(ParquetReader().readTypeRows(tmp, listOf("fix.Circle")).single().classHash).isEqualTo(type("fix.Circle").classHash)
    }

    @Test
    fun `a store built from the scan and one loaded from its snapshot have the same dependencies`(@TempDir tmp: Path) {
        // Neither the scan nor the snapshot carries an aggregate: both loaders roll it up from the same facts.
        result.writeTo(tmp, CompressionCodec.UNCOMPRESSED)
        val loaded = GraphStore().also { SnapshotLoader().load(tmp, it) }
        val scanned = GraphStore().also { store ->
            result.classes.map { it.artifactKey }.distinct().forEach { store.registerArtifact(io.hierograph.graphstore.jvm.model.ArtifactInfo(it, it, it)) }
            result.classes.forEach { store.applyClass(it.artifactKey, it.facts, refs(it.facts.fqn)) }
        }
        assertThat(loaded.typeDependencies().toSet()).isNotEmpty.isEqualTo(scanned.typeDependencies().toSet())
    }

    /** Scans [src], compiled with line and variable information, as one artifact. */
    private fun scanSources(tmp: Path, src: Map<String, String>): ScanResult =
        BytecodeScanner().scan(listOf(ScanArtifact.of(compile(tmp.resolve("classes"), src, listOf("-g")))))

    private fun ScanResult.targets(fromKey: String, kind: DependencyKind): List<String> =
        dependencies.filter { it.fromKey == fromKey && it.kind == kind }.map { it.toType }

    @Test
    fun `annotation values of every shape are facts of the annotated element, on fields and in defaults too`(@TempDir tmp: Path) {
        val scan = scanSources(
            tmp,
            mapOf(
                "a/Tag.java" to """
                    package a;
                    public @interface Tag {
                        Class<?>[] types() default {String.class};
                        Inner inner() default @Inner;
                    }
                """,
                "a/Inner.java" to """
                    package a;
                    public @interface Inner { Class<?> value() default Object.class; }
                """,
                "a/A.java" to """
                    package a;
                    public class A {
                        @Tag(types = {java.util.List.class, java.util.Map.class}, inner = @Inner(java.util.Set.class))
                        java.util.Collection<String> field;
                    }
                """,
            ),
        )

        // a field's own annotation, and every value inside it: class values, an array of them, a nested
        // annotation and the class value inside that
        val field = "a.A#java.util.Collection field"
        assertThat(scan.targets(field, DependencyKind.ANNOTATED_BY)).containsExactly("a.Tag")
        assertThat(scan.targets(field, DependencyKind.ANNOTATION_VALUE))
            .containsExactlyInAnyOrder("java.util.List", "java.util.Map", "a.Inner", "java.util.Set")

        // an annotation element's default value is a fact of the element
        assertThat(scan.targets("a.Tag#java.lang.Class[] types()", DependencyKind.ANNOTATION_VALUE)).containsExactly("java.lang.String")
        assertThat(scan.targets("a.Tag#a.Inner inner()", DependencyKind.ANNOTATION_VALUE)).containsExactly("a.Inner")
        assertThat(scan.targets("a.Inner#java.lang.Class value()", DependencyKind.ANNOTATION_VALUE)).containsExactly("java.lang.Object")
    }

    @Test
    fun `class and interface bounds are type arguments, a multi-dimensional array is an instantiation`(@TempDir tmp: Path) {
        val src = """
            package b;
            public class B<T extends Number & Comparable<T>> {
                Object grid() {
                    return new String[2][3];   // GRID
                }
            }
        """
        val scan = scanSources(tmp, mapOf("b/B.java" to src))

        assertThat(scan.targets("b.B", DependencyKind.TYPE_ARGUMENT)).containsExactlyInAnyOrder("java.lang.Number", "java.lang.Comparable")
        val grid = scan.dependencies.single { it.fromKey == "b.B#java.lang.Object grid()" && it.kind == DependencyKind.INSTANTIATES }
        assertThat(grid.toType).isEqualTo("java.lang.String")
        assertThat(grid.line).isEqualTo(src.trimIndent().lines().indexOfFirst { "GRID" in it } + 1)
    }

    @Test
    fun `a catch clause takes the line of a line entry that starts at its handler`(@TempDir tmp: Path) {
        val src = """
            package c;
            public class C {
                void work() {}
                void handle() {
                    try {
                        work();
                    } catch (IllegalStateException e) {   // CATCH
                        work();
                    }
                }
            }
        """
        val scan = scanSources(tmp, mapOf("c/C.java" to src))

        val catches = scan.dependencies.single { it.fromKey == "c.C#void handle()" && it.kind == DependencyKind.CATCHES }
        assertThat(catches.toType).isEqualTo("java.lang.IllegalStateException")
        assertThat(catches.line).isEqualTo(src.trimIndent().lines().indexOfFirst { "CATCH" in it } + 1)
    }

    @Test
    fun `an exception that is stored before it is thrown is no raises fact, whatever runs in between`(@TempDir tmp: Path) {
        val scan = scanSources(
            tmp,
            mapOf(
                "d/D.java" to """
                    package d;
                    public class D {
                        void stored(int i) {
                            RuntimeException e = new RuntimeException();
                            i++;
                            switch (i) { case 1: i--; break; case 2: i -= 2; break; case 3: i -= 3; break; default: break; }
                            switch (i) { case 1: i--; break; case 1000: i -= 2; break; default: break; }
                            throw e;
                        }
                    }
                """,
            ),
        )

        assertThat(scan.targets("d.D#void stored(int)", DependencyKind.RAISES)).isEmpty()
        assertThat(scan.targets("d.D#void stored(int)", DependencyKind.INSTANTIATES)).containsExactly("java.lang.RuntimeException")
    }

    @Test
    fun `a class file that cannot be parsed fails the scan, naming the file and its artifact`(@TempDir tmp: Path) {
        val dir = compile(tmp.resolve("broken"), mapOf("e/E.java" to "package e; public class E {}"), emptyList())
        Files.write(dir.resolve("e/Broken.class"), byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 1, 2, 3))

        for (parallelism in listOf(1, 4)) {
            assertThatThrownBy { BytecodeScanner(parallelism).scan(listOf(ScanArtifact("broken", dir))) }
                .describedAs("parallelism $parallelism")
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageStartingWith("Scanning e/Broken.class of $dir failed")
                .hasCauseInstanceOf(Exception::class.java)
        }
    }

    @Test
    fun `an input that is neither a JAR nor a directory, and a parallelism below one, are rejected`(@TempDir tmp: Path) {
        val notes = Files.writeString(tmp.resolve("notes.txt"), "not classes")

        assertThatThrownBy { BytecodeScanner().scan(listOf(ScanArtifact.of(notes))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("Not a JAR or class directory: $notes")
        assertThatThrownBy { BytecodeScanner(parallelism = 0) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("parallelism must be >= 1, was 0")
    }

    @Test
    fun `the command line scans its inputs into a snapshot and prints a summary`(@TempDir tmp: Path) {
        val out = tmp.resolve("snapshot")
        val printed = ByteArrayOutputStream()
        val stdout = System.out
        System.setOut(PrintStream(printed, true))
        try {
            main(arrayOf("--out", out.toString(), "--threads", "2", classesDir.toString()))
        } finally {
            System.setOut(stdout)
        }

        assertThat(Files.isRegularFile(out.resolve("classes.hierograph"))).isTrue()
        assertThat(ParquetReader().readTypeRows(out, listOf("fix.Circle")).map { it.artifact }).containsExactly("classes")
        assertThat(printed.toString())
            .startsWith("Scanned 1 artifacts on 2 threads: ${result.classes.size} types, ")
            .contains("${result.dependencies.size} detail refs → $out in ")
    }

    @Test
    fun `the command line requires --out and at least one input`(@TempDir tmp: Path) {
        val usage = "usage: scan --out <snapshot-dir> [--threads <n>] <jar-or-class-dir>..."
        assertThatThrownBy { main(arrayOf(classesDir.toString())) }.isInstanceOf(IllegalArgumentException::class.java).hasMessage(usage)
        assertThatThrownBy { main(arrayOf("--out", tmp.toString())) }.isInstanceOf(IllegalArgumentException::class.java).hasMessage(usage)
        assertThatThrownBy { main(arrayOf(classesDir.toString(), "--out")) }.isInstanceOf(IllegalArgumentException::class.java).hasMessage(usage)
    }

    private fun compile(out: Path, sources: Map<String, String>, options: List<String>): Path {
        val srcDir = Files.createDirectories(out.resolveSibling(out.fileName.toString() + "-src"))
        val files = sources.map { (path, code) ->
            srcDir.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, code.trimIndent()) }.toFile()
        }
        Files.createDirectories(out)
        val compiler = ToolProvider.getSystemJavaCompiler()
        compiler.getStandardFileManager(null, null, null).use { fm ->
            val ok = compiler.getTask(null, fm, null, options + listOf("-d", out.toString()), null, fm.getJavaFileObjectsFromFiles(files)).call()
            check(ok) { "fixture compilation failed" }
        }
        return out
    }
}
