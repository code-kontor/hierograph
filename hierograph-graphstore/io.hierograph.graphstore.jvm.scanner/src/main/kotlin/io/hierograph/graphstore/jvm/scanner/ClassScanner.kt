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

import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.DependencyKind
import io.hierograph.graphstore.jvm.model.DependencyRollup
import io.hierograph.graphstore.jvm.model.Dependency
import io.hierograph.graphstore.jvm.model.MemberFacts
import io.hierograph.graphstore.jvm.model.MemberModifiers
import io.hierograph.graphstore.jvm.model.ParameterFacts
import io.hierograph.graphstore.jvm.model.TypeModifiers
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import java.security.MessageDigest

/** One scanned class file: its facts and its member-level refs. */
internal class ScannedClass(
    val facts: ClassFacts,
    val dependencies: List<Dependency>,
)

/**
 * Scans one class file — its declarations, signatures and method bodies — into a [ScannedClass].
 *
 * Every dependency the class file contains becomes one detail fact ([Dependency], one [DependencyKind]),
 * through a single emission point ([Visitor.emit]): type-level facts (`extends`, `implements`, the type's
 * annotations, its type arguments and annotation values) with the type itself as source, and
 * member-level facts with the field or method as source. Each fact is one occurrence. Only the
 * **direct** facts are emitted; `overrides` and virtual/resolved calls need the type hierarchy and are
 * derived at load (see `HierarchyResolution` in the graph service).
 *
 * The class's aggregated references ([ClassFacts.refs]) are not counted separately: at the end of the
 * class they are rolled up from its detail facts by [DependencyRollup], so the aggregate is exactly what
 * the facts say.
 */
internal object ClassScanner {

    fun scan(bytes: ByteArray): ScannedClass {
        val visitor = Visitor(sha256(bytes))
        ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES)
        return visitor.result()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Visitor(private val classHash: String) : ClassVisitor(Opcodes.ASM9) {
        private lateinit var internalName: String
        private lateinit var fqn: String
        private var access = 0
        private var innerAccess: Int? = null
        private var superFqn: String? = null
        private var sourceFile: String? = null
        private val members = ArrayList<MemberFacts>()
        private val dependencies = ArrayList<Dependency>()
        private var generic = false

        fun result(): ScannedClass {
            val effective = innerAccess ?: access
            val facts = ClassFacts(
                fqn = fqn,
                kind = kindOf(access, superFqn),
                modifiers = TypeModifiers.pack(
                    visibility = visibility(effective),
                    abstract = effective and Opcodes.ACC_ABSTRACT != 0,
                    final = effective and Opcodes.ACC_FINAL != 0,
                    static = effective and Opcodes.ACC_STATIC != 0,
                    generic = generic,
                ),
                members = members,
                refs = DependencyRollup.refsOf(fqn, dependencies),
                classHash = classHash,
                sourceFile = sourceFile,
            )
            return ScannedClass(facts, dependencies)
        }

        // ── detail facts ─────────────────────────────────────────────

        /**
         * The single emission point: records one detail fact from [fromKey] (a member key, or [fqn] for a
         * type-level fact) to [toKey] (a member key, or a type) owned by [toType]. Every dependency the
         * scanner finds goes through here, so the rolled-up [ClassFacts.refs] always match the facts.
         * [into] is the list the fact goes to: a method buffers its body facts until its signature facts
         * are recorded.
         */
        fun emit(fromKey: String, toKey: String, toType: String, kind: DependencyKind, line: Int?, into: MutableList<Dependency> = dependencies) {
            into += Dependency(fromKey, fqn, toKey, toType, kind, line)
        }

        /** Emits a fact whose target is [type]'s element type (primitives and `void` included). */
        fun emitType(fromKey: String, type: Type, kind: DependencyKind, line: Int?, into: MutableList<Dependency> = dependencies) {
            val target = typeTarget(type)
            emit(fromKey, target, target, kind, line, into)
        }

        /** [emitType] for an internal name or an array descriptor, as instructions name their operand types. */
        fun emitInternal(fromKey: String, internalNameOrDescriptor: String, kind: DependencyKind, line: Int?, into: MutableList<Dependency> = dependencies) =
            emitType(fromKey, JavaNames.operandType(internalNameOrDescriptor), kind, line, into)

        /**
         * Emits a [DependencyKind.TYPE_ARGUMENT] fact from [fromKey] for every type argument and type-parameter
         * bound of a generic signature (the erased types are facts of their own position elsewhere).
         */
        fun emitTypeArguments(fromKey: String, signature: String?, isFieldSignature: Boolean = false) {
            if (signature == null) return
            val reader = SignatureReader(signature)
            // A field's signature is a type signature; class and method signatures are read with accept().
            if (isFieldSignature) reader.acceptType(TypeArgumentCollector(fromKey, inArgument = false))
            else reader.accept(TypeArgumentCollector(fromKey, inArgument = false))
        }

        /**
         * Collects the class types that appear *inside* type arguments or bounds. ASM hands out a child visitor
         * per type argument/bound; every other position (superclass, parameter, return, array, …) keeps the
         * current visitor, so erased top-level types are not emitted twice.
         */
        private inner class TypeArgumentCollector(private val fromKey: String, private val inArgument: Boolean) : SignatureVisitor(Opcodes.ASM9) {
            override fun visitClassType(name: String) { if (inArgument) emitInternal(fromKey, name, DependencyKind.TYPE_ARGUMENT, null) }
            override fun visitTypeArgument(wildcard: Char): SignatureVisitor = TypeArgumentCollector(fromKey, inArgument = true)
            override fun visitClassBound(): SignatureVisitor = TypeArgumentCollector(fromKey, inArgument = true)
            override fun visitInterfaceBound(): SignatureVisitor = TypeArgumentCollector(fromKey, inArgument = true)
        }

        // ── class ────────────────────────────────────────────────────

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
            this.internalName = name
            this.fqn = shared(JavaNames.fqn(name))
            this.access = access
            this.superFqn = superName?.let { JavaNames.fqn(it) }
            // A class signature opens with its formal type parameters, `<T:…>`, exactly when it declares any.
            this.generic = signature?.startsWith('<') == true
            superName?.let { emitInternal(fqn, it, DependencyKind.EXTENDS, null) }
            interfaces.orEmpty().forEach { emitInternal(fqn, it, DependencyKind.IMPLEMENTS, null) }
            emitTypeArguments(fqn, signature)
        }

        override fun visitSource(source: String?, debug: String?) { sourceFile = source }

        /** A nested type's real modifiers (static, private, protected) live in its own InnerClasses entry. */
        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
            if (name == internalName) innerAccess = access
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
            // The type's own annotation: the annotated element is the type, so the source is the type's FQN.
            emitType(fqn, Type.getType(descriptor), DependencyKind.ANNOTATED_BY, null)
            return AnnotationValues(fqn)
        }

        /**
         * Walks an annotation's values on the element [fromKey]: enum constants, class values and nested
         * annotations are [DependencyKind.ANNOTATION_VALUE] facts of that element.
         */
        inner class AnnotationValues(private val fromKey: String) : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(name: String?, value: Any?) {
                if (value is Type && (value.sort == Type.OBJECT || value.sort == Type.ARRAY)) emitType(fromKey, value, DependencyKind.ANNOTATION_VALUE, null)
            }
            override fun visitEnum(name: String?, descriptor: String, value: String) =
                emitType(fromKey, Type.getType(descriptor), DependencyKind.ANNOTATION_VALUE, null)
            override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor {
                emitType(fromKey, Type.getType(descriptor), DependencyKind.ANNOTATION_VALUE, null)
                return this
            }
            override fun visitArray(name: String?): AnnotationVisitor = this
        }

        // ── fields ───────────────────────────────────────────────────

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
            val fieldSignature = shared(JavaNames.fieldSignature(name, descriptor))
            val key = shared("$fqn#$fieldSignature")
            members += MemberFacts(
                key = key,
                kind = JavaNodeKind.FIELD,
                signature = fieldSignature,
                name = shared(name),
                modifiers = MemberModifiers.pack(
                    visibility = visibility(access),
                    static = access and Opcodes.ACC_STATIC != 0,
                    final = access and Opcodes.ACC_FINAL != 0,
                    transient = access and Opcodes.ACC_TRANSIENT != 0,
                    volatile = access and Opcodes.ACC_VOLATILE != 0,
                    synthetic = access and Opcodes.ACC_SYNTHETIC != 0,
                    constant = value != null, // a ConstantValue attribute: inlined at every use
                ),
            )
            emitType(key, Type.getType(descriptor), DependencyKind.HAS_TYPE, null)
            emitTypeArguments(key, signature, isFieldSignature = true)
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                    emitType(key, Type.getType(descriptor), DependencyKind.ANNOTATED_BY, null)
                    return AnnotationValues(key)
                }
            }
        }

        // ── methods ──────────────────────────────────────────────────

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor =
            MethodScanner(access, name, descriptor, signature, exceptions?.toList().orEmpty())

        private inner class MethodScanner(
            private val access: Int,
            private val name: String,
            private val descriptor: String,
            private val genericSignature: String?,
            private val exceptions: List<String>,
        ) : MethodVisitor(Opcodes.ASM9) {
            private val signature = shared(JavaNames.methodSignature(name, descriptor))
            private val key = shared("$fqn#$signature")
            private val argumentTypes = Type.getArgumentTypes(descriptor)
            private var line: Int? = null
            private var minLine: Int? = null
            private var maxLine: Int? = null
            private val declaredNames = ArrayList<String?>()
            private val localNames = HashMap<Int, String>()
            private val annotations = ArrayList<Type>()
            private val parameterAnnotations = HashMap<Int, MutableList<Type>>()
            private val bodyRefs = ArrayList<Dependency>()
            private var bridgeTarget: String? = null
            // The type whose constructor was the last real bytecode instruction — i.e. a freshly-built
            // object sitting on the stack top. `throw new X(...)` ends `...; invokespecial X.<init>; athrow`,
            // so an ATHROW seen while this is set is throwing that new object. Any other instruction clears
            // it, so an exception that is stored, returned, or rethrown is not mistaken for a `throw new`.
            private var constructed: String? = null
            private val raised = LinkedHashSet<String>()
            // Catch clauses by handler label, until the handler's code is reached and its line is known.
            private val catchesByHandler = HashMap<Label, MutableList<String>>()
            private val catches = ArrayList<Catch>()
            private var handlerLabel: Label? = null
            private var atHandler: List<Catch> = emptyList()

            override fun visitLineNumber(line: Int, start: Label) {
                this.line = line
                minLine = minOf(minLine ?: line, line)
                maxLine = maxOf(maxLine ?: line, line)
                // A line entry that starts exactly at the handler gives its catch clauses that line.
                if (start === handlerLabel) atHandler.forEach { it.line = line }
            }

            override fun visitLabel(label: Label) {
                // A catch clause's line is the line its handler's code belongs to: the line entry in effect at
                // the handler, as in a stack trace, unless an entry starts exactly there (see visitLineNumber).
                // javac starts no new entry when the handler is on the same line as the code before it.
                atHandler = catchesByHandler.remove(label)?.map { Catch(it, line) }.orEmpty()
                catches += atHandler
                handlerLabel = label.takeIf { atHandler.isNotEmpty() }
            }

            override fun visitParameter(name: String?, access: Int) { declaredNames += name }

            override fun visitLocalVariable(name: String, descriptor: String, signature: String?, start: Label, end: Label, index: Int) {
                localNames.putIfAbsent(index, name)
            }

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                annotations += Type.getType(descriptor)
                return AnnotationValues(key)
            }

            override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean): AnnotationVisitor {
                parameterAnnotations.getOrPut(parameter) { ArrayList() } += Type.getType(descriptor)
                return AnnotationValues(key)
            }

            /** The default value of an annotation type's element. */
            override fun visitAnnotationDefault(): AnnotationVisitor = AnnotationValues(key)

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                call(owner, name, descriptor)
                // A constructor call leaves the built object on the stack; anything else consumes the stack.
                constructed = if (opcode == Opcodes.INVOKESPECIAL && name == "<init>") shared(JavaNames.fqn(owner)) else null
            }

            override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrap: Handle, vararg arguments: Any?) {
                constructed = null
                // A lambda or method reference: link to its implementation method.
                if (bootstrap.owner == "java/lang/invoke/LambdaMetafactory") {
                    (arguments.getOrNull(1) as? Handle)?.let { call(it.owner, it.name, it.desc) }
                }
            }

            // Any instruction other than the throw itself means the freshly-built object (if any) was
            // consumed, so it is no longer a `throw new` candidate.
            override fun visitVarInsn(opcode: Int, varIndex: Int) { constructed = null }
            override fun visitIntInsn(opcode: Int, operand: Int) { constructed = null }
            override fun visitJumpInsn(opcode: Int, label: Label) { constructed = null }
            override fun visitIincInsn(varIndex: Int, increment: Int) { constructed = null }
            override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) { constructed = null }
            override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray?, labels: Array<out Label>?) { constructed = null }

            private fun call(owner: String, name: String, descriptor: String) {
                if (owner.startsWith("[")) return // array pseudo-methods (clone) have no owner type
                // A bridge method forwards to the real method: same name, on its own class.
                if (access and Opcodes.ACC_BRIDGE != 0 && bridgeTarget == null && owner == internalName && name == this.name) {
                    bridgeTarget = "$fqn#${JavaNames.methodSignature(name, descriptor)}"
                }
                val ownerFqn = shared(JavaNames.fqn(owner))
                emit(key, shared("$ownerFqn#${JavaNames.methodSignature(name, descriptor)}"), ownerFqn, DependencyKind.CALLS, line, bodyRefs)
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                constructed = null
                val write = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC
                val ownerFqn = shared(JavaNames.fqn(owner))
                emit(
                    key, shared("$ownerFqn#${JavaNames.fieldSignature(name, descriptor)}"), ownerFqn,
                    if (write) DependencyKind.WRITES_FIELD else DependencyKind.READS_FIELD, line, bodyRefs,
                )
            }

            override fun visitTypeInsn(opcode: Int, type: String) {
                constructed = null // NEW leaves an *uninitialized* ref; it becomes a candidate only after its <init>
                when (opcode) {
                    Opcodes.NEW, Opcodes.ANEWARRAY -> emitInternal(key, type, DependencyKind.INSTANTIATES, line, bodyRefs)
                    Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> emitInternal(key, type, DependencyKind.CASTS, line, bodyRefs)
                }
            }

            override fun visitInsn(opcode: Int) {
                // `raises`: the body-level counterpart of the declared `throws` clause. A `throw new X(...)`
                // ends with `invokespecial X.<init>; athrow`, so an ATHROW with `constructed` still set is
                // throwing that freshly-built object. Any other instruction has cleared it, so a rethrown,
                // stored, returned or factory-produced exception is not counted (that is not a `throw new`).
                if (opcode == Opcodes.ATHROW) constructed?.let { raised += it } else constructed = null
            }

            override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
                constructed = null
                emitType(key, Type.getType(descriptor), DependencyKind.INSTANTIATES, line, bodyRefs)
            }

            override fun visitLdcInsn(value: Any?) {
                constructed = null
                if (value is Type && (value.sort == Type.OBJECT || value.sort == Type.ARRAY)) emitType(key, value, DependencyKind.CLASS_LITERAL, line, bodyRefs)
            }

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                // Try-catch blocks are visited before the code; the handler's line is known only once its label is reached.
                type?.let { catchesByHandler.getOrPut(handler) { ArrayList() } += it }
            }

            override fun visitEnd() {
                val isStatic = access and Opcodes.ACC_STATIC != 0
                val isInterface = this@Visitor.access and Opcodes.ACC_INTERFACE != 0
                val isAbstract = access and Opcodes.ACC_ABSTRACT != 0
                members += MemberFacts(
                    key = key,
                    kind = if (name == "<init>") JavaNodeKind.CONSTRUCTOR else JavaNodeKind.METHOD,
                    signature = signature,
                    name = shared(name),
                    modifiers = MemberModifiers.pack(
                        visibility = visibility(access),
                        static = isStatic,
                        final = access and Opcodes.ACC_FINAL != 0,
                        abstract = isAbstract,
                        synchronized = access and Opcodes.ACC_SYNCHRONIZED != 0,
                        native = access and Opcodes.ACC_NATIVE != 0,
                        default = isInterface && !isAbstract && !isStatic && access and Opcodes.ACC_PRIVATE == 0 && name != "<clinit>",
                        bridge = access and Opcodes.ACC_BRIDGE != 0,
                        synthetic = access and Opcodes.ACC_SYNTHETIC != 0,
                    ),
                    lineFrom = minLine,
                    lineTo = maxLine,
                    parameters = parameters(isStatic),
                    bridgeTarget = bridgeTarget,
                )

                catches.forEach { emitInternal(key, it.type, DependencyKind.CATCHES, it.line, bodyRefs) }
                // A handler that was never reached in the code (should not happen) still records its catch clauses.
                catchesByHandler.values.flatten().forEach { emitInternal(key, it, DependencyKind.CATCHES, null, bodyRefs) }

                val methodType = Type.getMethodType(descriptor)
                emitType(key, methodType.returnType, DependencyKind.RETURNS, minLine)
                argumentTypes.forEach { emitType(key, it, DependencyKind.PARAMETER_TYPE, minLine) }
                exceptions.forEach { emitInternal(key, it, DependencyKind.THROWS, minLine) }
                emitTypeArguments(key, genericSignature)
                raised.forEach { emit(key, it, it, DependencyKind.RAISES, minLine) }
                annotations.forEach { emitType(key, it, DependencyKind.ANNOTATED_BY, null) }
                parameterAnnotations.values.flatten().forEach { emitType(key, it, DependencyKind.PARAMETER_ANNOTATED_BY, null) }
                dependencies += bodyRefs
            }

            /**
             * Parameter names from the MethodParameters attribute (`javac -parameters`), else from the local
             * variable table, else `argN`. Parameter types are element types, like every reference target.
             */
            private fun parameters(isStatic: Boolean): List<ParameterFacts> {
                var slot = if (isStatic) 0 else 1
                return argumentTypes.mapIndexed { i, type ->
                    val name = declaredNames.getOrNull(i).takeIf { declaredNames.size == argumentTypes.size }
                        ?: localNames[slot]
                        ?: "arg$i"
                    slot += type.size
                    ParameterFacts(
                        index = i, name = shared(name), typeFqn = typeTarget(type),
                        annotations = parameterAnnotations[i].orEmpty().map { typeTarget(it) }.distinct(),
                    )
                }
            }
        }

        /** A catch clause of a method: the caught type and the line its handler's code belongs to. */
        private class Catch(val type: String, var line: Int?)

        /** A type reference target for detail facts: the element type, primitives (and `void`) included. */
        private fun typeTarget(type: Type): String = shared(JavaNames.display(JavaNames.element(type)))
    }

    /**
     * One instance per distinct value for the names the scanner keeps: type names, member keys, call and
     * field targets, signatures, member and parameter names. They repeat across all scanned classes (every
     * call to a method carries its target again), and the scan results stay in memory between builds, so
     * without this most of their memory is copies. `intern()` holds no string alive by itself: an interned
     * string is collected once nothing else references it.
     */
    private fun shared(value: String): String = value.intern()

    private fun kindOf(access: Int, superFqn: String?): JavaNodeKind = when {
        access and Opcodes.ACC_ANNOTATION != 0 -> JavaNodeKind.ANNOTATION
        access and Opcodes.ACC_ENUM != 0 -> JavaNodeKind.ENUM
        superFqn == "java.lang.Record" -> JavaNodeKind.RECORD
        access and Opcodes.ACC_INTERFACE != 0 -> JavaNodeKind.INTERFACE
        else -> JavaNodeKind.CLASS
    }

    private fun visibility(access: Int): String? = when {
        access and Opcodes.ACC_PUBLIC != 0 -> "public"
        access and Opcodes.ACC_PROTECTED != 0 -> "protected"
        access and Opcodes.ACC_PRIVATE != 0 -> "private"
        else -> null
    }
}
