package org.slizaa.mcp.javaspec;

import java.util.List;
import java.util.Set;

/**
 * Canonical constants for Java-specific node kinds, relationship kinds,
 * and group aliases used throughout Hierograph's tool surface.
 *
 * <p>All string constants match the namespaced vocabulary defined in the
 * tool surface proposal ({@code "java.class"}, {@code "java.method"}, etc.).
 * Use these constants instead of string literals to avoid typos and keep
 * the vocabulary in one place.</p>
 */
public final class JavaKinds {

    private JavaKinds() {}

    // ── node kinds ─────────────────────────────────────────────────────

    public static final String MODULE     = "java.module";
    public static final String PACKAGE    = "java.package";
    public static final String CLASS      = "java.class";
    public static final String INTERFACE  = "java.interface";
    public static final String ENUM       = "java.enum";
    public static final String RECORD     = "java.record";
    public static final String ANNOTATION = "java.annotation";
    public static final String METHOD     = "java.method";
    public static final String FIELD      = "java.field";
    public static final String PRIMITIVE  = "java.primitive";
    public static final String CONSTRUCTOR = "java.constructor";

    // ── kind groups ────────────────────────────────────────────────────

    /** All type-level node kinds. */
    public static final Set<String> TYPE_KINDS = Set.of(
            CLASS, INTERFACE, ENUM, RECORD, ANNOTATION
    );

    /** All member-level node kinds. */
    public static final Set<String> MEMBER_KINDS = Set.of(METHOD, FIELD);

    /** All valid node kinds (excluding pseudo-kinds like primitive and constructor). */
    public static final Set<String> ALL_KINDS = Set.of(
            MODULE, PACKAGE, CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, FIELD
    );

    // ── group aliases (accepted by kind_filter parameters) ─────────────

    public static final String ALIAS_TYPES    = "types";
    public static final String ALIAS_MEMBERS  = "members";
    public static final String ALIAS_PACKAGES = "packages";

    public static final Set<String> ALL_ALIASES = Set.of(
            ALIAS_TYPES, ALIAS_MEMBERS, ALIAS_PACKAGES
    );

    /**
     * Expands a group alias to its constituent kinds.
     * Returns {@code null} if the input is not a recognized alias.
     */
    public static List<String> expandAlias(String alias) {
        return switch (alias) {
            case ALIAS_TYPES    -> List.of(CLASS, INTERFACE, ENUM, RECORD, ANNOTATION);
            case ALIAS_MEMBERS  -> List.of(METHOD, FIELD);
            case ALIAS_PACKAGES -> List.of(PACKAGE);
            default -> null;
        };
    }

    // ── Java primitives ────────────────────────────────────────────────

    /** The set of Java primitive type names. */
    public static final Set<String> JAVA_PRIMITIVES = Set.of(
            "void", "boolean", "byte", "char", "short", "int", "long", "float", "double"
    );
}
