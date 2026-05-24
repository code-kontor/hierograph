package org.slizaa.hierarchicalgraph.core.model;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Traverses a hierarchical graph in depth-first pre-order, executing an action on each visited node.
 *
 * <p>Usage examples:</p>
 * <pre>{@code
 * // Count all nodes
 * var counter = new int[]{0};
 * HGNodeTraverser.traverse(rootNode, node -> counter[0]++);
 *
 * // Collect nodes of a specific kind
 * var classes = new ArrayList<HGNode>();
 * HGNodeTraverser.traverse(rootNode, node -> {
 *     if ("java.class".equals(node.getKind())) classes.add(node);
 * });
 *
 * // Traverse only packages, skip everything else
 * HGNodeTraverser.traverse(rootNode,
 *     node -> process(node),
 *     node -> "java.package".equals(node.getKind()));
 * }</pre>
 */
public final class HGNodeTraverser {

    private HGNodeTraverser() {}

    /**
     * Traverses the subtree rooted at {@code node} in depth-first pre-order,
     * executing {@code action} on every node (including {@code node} itself).
     *
     * @param node   the root of the subtree to traverse
     * @param action the action to execute on each visited node
     */
    public static void traverse(HGNode node, Consumer<HGNode> action) {
        action.accept(node);
        for (HGNode child : node.getChildren()) {
            traverse(child, action);
        }
    }

    /**
     * Traverses the subtree rooted at {@code node} in depth-first pre-order,
     * executing {@code action} on every node that matches {@code filter}.
     * Traversal continues into children regardless of whether the parent matched
     * the filter — the filter controls which nodes receive the action, not
     * which subtrees are visited.
     *
     * @param node   the root of the subtree to traverse
     * @param action the action to execute on each matching node
     * @param filter predicate that determines which nodes receive the action
     */
    public static void traverse(HGNode node, Consumer<HGNode> action, Predicate<HGNode> filter) {
        if (filter.test(node)) {
            action.accept(node);
        }
        for (HGNode child : node.getChildren()) {
            traverse(child, action, filter);
        }
    }

    /**
     * Traverses the subtree rooted at {@code node} in depth-first pre-order,
     * executing {@code action} on every node. If {@code descendInto} returns
     * {@code false} for a node, its children are not visited (the subtree is pruned).
     * The action is still executed on the node itself before the descent check.
     *
     * @param node        the root of the subtree to traverse
     * @param action      the action to execute on each visited node
     * @param descendInto predicate that controls whether to visit a node's children
     */
    public static void traverseWithPruning(HGNode node, Consumer<HGNode> action, Predicate<HGNode> descendInto) {
        action.accept(node);
        if (descendInto.test(node)) {
            for (HGNode child : node.getChildren()) {
                traverseWithPruning(child, action, descendInto);
            }
        }
    }
}
