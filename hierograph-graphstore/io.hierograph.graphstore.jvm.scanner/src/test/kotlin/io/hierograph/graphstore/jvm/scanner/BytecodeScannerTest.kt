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
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.model.MemberModifiers
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.graphstore.jvm.model.TypeModifiers
import io.hierograph.graphstore.jvm.parquet.ParquetReader
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
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
    private fun refCount(from: String, to: String, kind: RefKind): Int? =
        type(from).refs.singleOrNull { it.toKey == to && it.kind == kind }?.count
    private fun details(fromKey: String, relationship: String): List<DetailRef> =
        result.detailRefs.filter { it.fromKey == fromKey && it.relationship == relationship }

    @Test
    fun `types get their kind and real modifiers, nested and interface ones included`() {
        assertThat(result.classes.map { it.facts.fqn }).containsExactlyInAnyOrder(
            "fix.package-info", "fix.Base", "fix.Shape", "fix.Circle",
            "fix.Circle\$Nested", "fix.Circle\$Color", "fix.Circle\$Point", "fix.Circle\$Marker",
            "fix.User", "fix.Animal", "fix.Dog", "fix.Box",
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
        assertThat(refCount("fix.Circle", "fix.Base", RefKind.EXTENDS)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "fix.Shape", RefKind.IMPLEMENTS)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.io.Serializable", RefKind.IMPLEMENTS)).isEqualTo(1)
        assertThat(refCount("fix.Shape", "java.lang.Object", RefKind.EXTENDS)).isEqualTo(1) // as in the bytecode
        assertThat(refCount("fix.Base", "java.lang.Deprecated", RefKind.ANNOTATED_BY)).isEqualTo(1)

        assertThat(refCount("fix.Circle", "java.util.List", RefKind.FIELD_TYPE)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.util.List", RefKind.RETURN_TYPE)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.lang.String", RefKind.TYPE_ARGUMENT)).isEqualTo(2) // List<String> field + return
        assertThat(refCount("fix.Circle", "java.lang.String", RefKind.PARAM_TYPE)).isEqualTo(1)   // String... more
        assertThat(refCount("fix.Circle", "java.io.IOException", RefKind.THROWS)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.lang.Deprecated", RefKind.MEMBER_ANNOTATED_BY)).isEqualTo(1)

        assertThat(refCount("fix.Circle", "java.util.ArrayList", RefKind.INSTANTIATES)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.lang.String", RefKind.CAST)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.lang.IllegalStateException", RefKind.CATCHES)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "fix.Shape", RefKind.CLASS_LITERAL)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.io.File", RefKind.READS)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.lang.Runnable", RefKind.INVOKES)).isEqualTo(1)
        assertThat(refCount("fix.Circle", "java.util.List", RefKind.INVOKES)).isEqualTo(1)

        assertThat(type("fix.Circle").refs.map { it.toKey }).doesNotContain("int", "double", "void", "fix.Circle")
        // annotation values: the enum constant in @Retention(RetentionPolicy.RUNTIME)
        assertThat(refCount("fix.Circle\$Marker", "java.lang.annotation.Retention", RefKind.ANNOTATED_BY)).isEqualTo(1)
        assertThat(refCount("fix.Circle\$Marker", "java.lang.annotation.RetentionPolicy", RefKind.ANNOTATION_VALUE)).isEqualTo(1)
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
    fun `the scanner emits only direct member edges - no overrides or virtual dispatch`() {
        // Hierarchy-dependent edges are derived at load (HierarchyResolution), not by the scanner.
        assertThat(result.detailRefs.map { it.relationship }.toSet()).doesNotContain("overrides")
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
        assertThat(fromJar.detailRefs.filter { it.fromType != "fix.OnlyNew" }).hasSameSizeAs(result.detailRefs)
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
    fun `the result round-trips through a Parquet snapshot into the GraphStore`(@TempDir tmp: Path) {
        result.writeTo(tmp, CompressionCodec.UNCOMPRESSED)

        val store = GraphStore()
        ParquetReader().load(tmp, store)

        assertThat(store.typeByFqn("fix.Circle")!!.placeholder).isFalse
        assertThat(store.memberByKey("fix.Circle#java.lang.String name()")).isNotNull
        val edges = store.typeDependencies().filter { it.from == "fix.Circle" }.associateBy { it.to }
        assertThat(edges.getValue("fix.Base").extends).isTrue()
        // weight = number of references: the sum of the counts over all ref kinds to that target
        val listRefs = type("fix.Circle").refs.filter { it.toKey == "java.util.List" }
        assertThat(edges.getValue("java.util.List").weight).isEqualTo(listRefs.sumOf { it.count ?: 1 })
        assertThat(listRefs.map { it.kind }).contains(RefKind.FIELD_TYPE, RefKind.RETURN_TYPE, RefKind.INVOKES)
        assertThat(ParquetReader().readDetailRefs(tmp, fromTypes = listOf("fix.Circle"))).isNotEmpty()
        assertThat(ParquetReader().readTypeRows(tmp, listOf("fix.Circle")).single().classHash).isEqualTo(type("fix.Circle").classHash)
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
