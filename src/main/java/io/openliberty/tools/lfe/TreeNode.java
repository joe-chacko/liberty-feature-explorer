package io.openliberty.tools.lfe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

class TreeNode<V> {
    final V value;
    final Map<V, TreeNode<V>> children = new LinkedHashMap<>();

    TreeNode() {
        this(null);
    } // root node

    TreeNode(V value) {
        this.value = value;
    }

    private TreeNode<V> getChild(V value) {
        return children.computeIfAbsent(value, TreeNode::new);
    }

    void addPath(List<V> path) {
        TreeNode<V> n = this;
        for (V elem : path) n = n.getChild(elem);
    }

    void combine(TreeNode<V> that) {
        throw new UnsupportedOperationException("Parallelism not supported here");
    }

    void traverseDepthFirst(String prefix, Consumer<V> rootAction, Function<String, Consumer<V>> action) {
        final Consumer<V> fmt = action.apply(prefix);
        children.values()
                .stream()
                .peek(n -> rootAction.accept(n.value))
                .peek(n -> fmt.accept(n.value))
                .forEach(n -> n.traverseDepthFirst(prefix, action));
    }

    void traverseDepthFirst(String prefix, Function<String, Consumer<V>> formatter) {
        if (children.isEmpty()) return;
        final Consumer<V> printChild = formatter.apply(prefix + "\u2560\u2550");
        children.values()
                .stream()
                .limit(children.size() - 1L)
                .peek(n -> printChild.accept(n.value))
                .forEach(n -> n.traverseDepthFirst(prefix + "\u2551 ", formatter));
        // now format the last child: not efficient, but accurate and terse(ish)
        children.values()
                .stream()
                .skip(children.size() - 1L)
                .peek(n -> formatter.apply(prefix + "\u255A\u2550").accept(n.value))
                .forEach(n -> n.traverseDepthFirst(prefix + "  ", formatter));
    }
}
