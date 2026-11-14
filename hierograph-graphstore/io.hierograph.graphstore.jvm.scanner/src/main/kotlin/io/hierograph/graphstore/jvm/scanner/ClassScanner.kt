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
import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.model.MemberFacts
import io.hierograph.graphstore.jvm.model.MemberModifiers
import io.hierograph.graphstore.jvm.model.ParameterFacts
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
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
    val detailRefs: List<DetailRef>,
)

/**
 * Scans one class file — its declarations, signatures and method bodies — into a [ScannedClass].
 *
 * Type-level [Ref]s are aggregated per (target, kind) with a **count** of individual references, so the
 * dependency weight is "number of references". Member-level [DetailRef]s use the snapshot's detail
 * vocabulary (`calls`, `reads_field`, `writes_field`, `throws`, `raises`, `returns`, `has_type`,
 * `parameter_type`, `annotated_by`) — the **direct** edges only; `overrides` and virtual/resolved calls need the type
 * hierarchy and are derived at load (see `HierarchyResolution` in the graph service). Annotation *values*
 * (enum constants, class values, nested annotations) count as [RefKind.ANNOTATION_VALUE].
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
        private val detailRefs = ArrayList<DetailRef>()
        private var generic = false
        private val refCounts = LinkedHashMap<Pair<String, RefKind>, Int>()

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
                refs = refCounts.map { (k, count) -> Ref(fromKey = fqn, toKey = k.first, kind = k.second, count = count) },
                classHash = classHash,
                sourceFile = sourceFile,
            )
            return ScannedClass(facts, detailRefs)
        }

        // ── type-level refs ──────────────────────────────────────────

        /** Counts one reference to [type]'s element type; primitives, `void` and self-references are skipped. */
        fun ref(type: Type, kind: RefKind) {
            val element = JavaNames.element(type)
            if (JavaNames.isPrimitive(element)) return
            val target = JavaNames.display(element)
            if (target == fqn) return
            refCounts.merge(target to kind, 1, Int::plus)
        }

        fun refInternal(internalNameOrDescriptor: String, kind: RefKind) = ref(JavaNames.operandType(internalNameOrDescriptor), kind)

        /** Type arguments and type-parameter bounds of a generic signature (the erased types are counted elsewhere). */
        fun genericRefs(signature: String?, isFieldSignature: Boolean = false) {
            if (signature == null) return
            val reader = SignatureReader(signature)
            // A field's signature is a type signature; class and method signatures are read with accept().
            if (isFieldSignature) reader.acceptType(TypeArgumentCollector(inArgument = false))
            else reader.accept(TypeArgumentCollector(inArgument = false))
        }

        /**
         * Counts the class types that appear *inside* type arguments or bounds. ASM hands out a child visitor
         * per type argument/bound; every other position (superclass, parameter, return, array, …) keeps the
         * current visitor, so erased top-level types are not counted twice.
         */
        private inner class TypeArgumentCollector(private val inArgument: Boolean) : SignatureVisitor(Opcodes.ASM9) {
            override fun visitClassType(name: String) { if (inArgument) refInternal(name, RefKind.TYPE_ARGUMENT) }
            override fun visitTypeArgument(wildcard: Char): SignatureVisitor = TypeArgumentCollector(inArgument = true)
            override fun visitClassBound(): SignatureVisitor = TypeArgumentCollector(inArgument = true)
            override fun visitInterfaceBound(): SignatureVisitor = TypeArgumentCollector(inArgument = true)
        }

        // ── class ────────────────────────────────────────────────────

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
            this.internalName = name
            this.fqn = JavaNames.fqn(name)
            this.access = access
            this.superFqn = superName?.let { JavaNames.fqn(it) }
            // A class signature opens with its formal type parameters, `<T:…>`, exactly when it declares any.
            this.generic = signature?.startsWith('<') == true
            superName?.let { refInternal(it, RefKind.EXTENDS) }
            interfaces.orEmpty().forEach { refInternal(it, RefKind.IMPLEMENTS) }
            genericRefs(signature)
        }

        override fun visitSource(source: String?, debug: String?) { sourceFile = source }

        /** A nested type's real modifiers (static, private, protected) live in its own InnerClasses entry. */
        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
            if (name == internalName) innerAccess = access
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
            val annotation = Type.getType(descriptor)
            ref(annotation, RefKind.ANNOTATED_BY)
            // Class-level annotation: also emit a member-level detail edge from the type to the annotation
            // (mirroring member annotations below), so `detail_dependencies(annotated_by)` surfaces
            // class-level annotations. The annotated element IS the type, so from_key == the type fqn.
            val target = JavaNames.display(JavaNames.element(annotation))
            detailRefs += DetailRef(fqn, fqn, target, target, "annotated_by", null)
            return AnnotationValues()
        }

        /** Walks an annotation's values: enum constants, class values and nested annotations are type references. */
        inner class AnnotationValues : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(name: String?, value: Any?) {
                if (value is Type && (value.sort == Type.OBJECT || value.sort == Type.ARRAY)) ref(value, RefKind.ANNOTATION_VALUE)
            }
            override fun visitEnum(name: String?, descriptor: String, value: String) = ref(Type.getType(descriptor), RefKind.ANNOTATION_VALUE)
            override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor {
                ref(Type.getType(descriptor), RefKind.ANNOTATION_VALUE)
                return this
            }
            override fun visitArray(name: String?): AnnotationVisitor = this
        }

        // ── fields ───────────────────────────────────────────────────

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
            val key = "$fqn#${JavaNames.fieldSignature(name, descriptor)}"
            members += MemberFacts(
                key = key,
                kind = JavaNodeKind.FIELD,
                signature = JavaNames.fieldSignature(name, descriptor),
                name = name,
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
            val type = Type.getType(descriptor)
            ref(type, RefKind.FIELD_TYPE)
            genericRefs(signature, isFieldSignature = true)
            detail(key, typeTarget(type), "has_type", null)
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                    val annotation = Type.getType(descriptor)
                    ref(annotation, RefKind.MEMBER_ANNOTATED_BY)
                    detail(key, typeTarget(annotation), "annotated_by", null)
                    return AnnotationValues()
                }
            }
        }

        // ── methods ──────────────────────────────────────────────────

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
            val methodType = Type.getMethodType(descriptor)
            ref(methodType.returnType, RefKind.RETURN_TYPE)
            methodType.argumentTypes.forEach { ref(it, RefKind.PARAM_TYPE) }
            exceptions.orEmpty().forEach { refInternal(it, RefKind.THROWS) }
            genericRefs(signature)
            return MethodScanner(access, name, descriptor, exceptions?.toList().orEmpty())
        }

        private inner class MethodScanner(
            private val access: Int,
            private val name: String,
            private val descriptor: String,
            private val exceptions: List<String>,
        ) : MethodVisitor(Opcodes.ASM9) {
            private val signature = JavaNames.methodSignature(name, descriptor)
            private val key = "$fqn#$signature"
            private val argumentTypes = Type.getArgumentTypes(descriptor)
            private var line: Int? = null
            private var minLine: Int? = null
            private var maxLine: Int? = null
            private val declaredNames = ArrayList<String?>()
            private val localNames = HashMap<Int, String>()
            private val annotations = ArrayList<Type>()
            private val parameterAnnotations = HashMap<Int, MutableList<Type>>()
            private val bodyRefs = ArrayList<DetailRef>()
            private var bridgeTarget: String? = null
            // The type whose constructor was the last real bytecode instruction — i.e. a freshly-built
            // object sitting on the stack top. `throw new X(...)` ends `...; invokespecial X.<init>; athrow`,
            // so an ATHROW seen while this is set is throwing that new object. Any other instruction clears
            // it, so an exception that is stored, returned, or rethrown is not mistaken for a `throw new`.
            private var constructed: String? = null
            private val raised = LinkedHashSet<String>()

            override fun visitLineNumber(line: Int, start: Label) {
                this.line = line
                minLine = minOf(minLine ?: line, line)
                maxLine = maxOf(maxLine ?: line, line)
            }

            override fun visitParameter(name: String?, access: Int) { declaredNames += name }

            override fun visitLocalVariable(name: String, descriptor: String, signature: String?, start: Label, end: Label, index: Int) {
                localNames.putIfAbsent(index, name)
            }

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                val annotation = Type.getType(descriptor)
                ref(annotation, RefKind.MEMBER_ANNOTATED_BY)
                annotations += annotation
                return AnnotationValues()
            }

            override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean): AnnotationVisitor {
                ref(Type.getType(descriptor), RefKind.MEMBER_ANNOTATED_BY)
                parameterAnnotations.getOrPut(parameter) { ArrayList() } += Type.getType(descriptor)
                return AnnotationValues()
            }

            /** The default value of an annotation type's element. */
            override fun visitAnnotationDefault(): AnnotationVisitor = AnnotationValues()

            override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                call(owner, name, descriptor)
                // A constructor call leaves the built object on the stack; anything else consumes the stack.
                constructed = if (opcode == Opcodes.INVOKESPECIAL && name == "<init>") JavaNames.fqn(owner) else null
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
                refInternal(owner, RefKind.INVOKES)
                val ownerFqn = JavaNames.fqn(owner)
                bodyRefs += DetailRef(key, fqn, "$ownerFqn#${JavaNames.methodSignature(name, descriptor)}", ownerFqn, "calls", line)
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                constructed = null
                val write = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC
                refInternal(owner, if (write) RefKind.WRITES else RefKind.READS)
                val ownerFqn = JavaNames.fqn(owner)
                bodyRefs += DetailRef(
                    key, fqn, "$ownerFqn#${JavaNames.fieldSignature(name, descriptor)}", ownerFqn,
                    if (write) "writes_field" else "reads_field", line,
                )
            }

            override fun visitTypeInsn(opcode: Int, type: String) {
                constructed = null // NEW leaves an *uninitialized* ref; it becomes a candidate only after its <init>
                when (opcode) {
                    Opcodes.NEW, Opcodes.ANEWARRAY -> refInternal(type, RefKind.INSTANTIATES)
                    Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> refInternal(type, RefKind.CAST)
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
                ref(Type.getType(descriptor), RefKind.INSTANTIATES)
            }

            override fun visitLdcInsn(value: Any?) {
                constructed = null
                if (value is Type && (value.sort == Type.OBJECT || value.sort == Type.ARRAY)) ref(value, RefKind.CLASS_LITERAL)
            }

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                type?.let { refInternal(it, RefKind.CATCHES) }
            }

            override fun visitEnd() {
                val isStatic = access and Opcodes.ACC_STATIC != 0
                val isInterface = this@Visitor.access and Opcodes.ACC_INTERFACE != 0
                val isAbstract = access and Opcodes.ACC_ABSTRACT != 0
                val params = parameters(isStatic)
                members += MemberFacts(
                    key = key,
                    kind = if (name == "<init>") JavaNodeKind.CONSTRUCTOR else JavaNodeKind.METHOD,
                    signature = signature,
                    name = name,
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
                    parameters = params,
                    bridgeTarget = bridgeTarget,
                )

                val methodType = Type.getMethodType(descriptor)
                detail(key, typeTarget(methodType.returnType), "returns", minLine)
                // One row per parameter (not per distinct type), each carrying its position and name, so two
                // parameters of one type are two rows and a caller can ask for a parameter by position. Both
                // rows come from the same ParameterFacts as `parameters.parquet`, so the tables agree.
                params.forEach { p ->
                    detailRefs += DetailRef(key, fqn, p.typeFqn, p.typeFqn, "parameter_type", minLine,
                        parameterIndex = p.index, parameterName = p.name)
                }
                exceptions.forEach { detail(key, JavaNames.fqn(it), "throws", minLine) }
                raised.forEach { detail(key, it, "raises", minLine) }
                annotations.forEach { detail(key, typeTarget(it), "annotated_by", null) }
                params.forEach { p ->
                    p.annotations.forEach { a ->
                        detailRefs += DetailRef(key, fqn, a, a, "parameter_annotated_by", null,
                            parameterIndex = p.index, parameterName = p.name)
                    }
                }
                detailRefs += bodyRefs
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
                        index = i, name = name, typeFqn = typeTarget(type),
                        annotations = parameterAnnotations[i].orEmpty().map { typeTarget(it) }.distinct(),
                    )
                }
            }
        }

        /** A type reference target for detail refs: the element type, primitives (and `void`) included. */
        private fun typeTarget(type: Type): String = JavaNames.display(JavaNames.element(type))

        private fun detail(fromKey: String, toType: String, relationship: String, line: Int?) {
            detailRefs += DetailRef(fromKey, fqn, toType, toType, relationship, line)
        }
    }

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
