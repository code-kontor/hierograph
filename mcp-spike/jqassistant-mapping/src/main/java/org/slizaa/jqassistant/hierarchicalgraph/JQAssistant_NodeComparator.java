package org.slizaa.jqassistant.hierarchicalgraph;

import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.core.model.spi.INodeComparator;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbNodeSource;

public class JQAssistant_NodeComparator implements INodeComparator {

	@Override
	public int category(Object element) {

		if (!hasGraphDbNodeSource(element)) {
			return 0;
		}

		if (hasLabel(element, "Package")) {
			return 10;
		}

		if (hasLabel(element, "Type")) {
			return 20;
		}

		return 1;
	}

	@Override
	public int compare(Object node1, Object node2) {

		if (!(hasGraphDbNodeSource(node1) && hasGraphDbNodeSource(node2))) {
			return 0;
		}

		if (hasLabel(node1, node2, "Package") || hasLabel(node1, node2, "Type")
				|| hasLabel(node1, node2, "Artifact")) {
			return compareProperties(node1, node2, "name");
		}

		return -1;
	}

	private boolean hasLabel(Object node, String label) {
		return ((GraphDbNodeSource) ((HGNode) node).getNodeSource()).getLabels().contains(label);
	}

	private boolean hasLabel(Object node1, Object node2, String label) {
		return hasLabel(node1, label) && hasLabel(node2, label);
	}

	private int compareProperties(Object node1, Object node2, String property) {
		GraphDbNodeSource source1 = (GraphDbNodeSource) ((HGNode) node1).getNodeSource();
		GraphDbNodeSource source2 = (GraphDbNodeSource) ((HGNode) node2).getNodeSource();

		if (!source1.getProperties().containsKey(property) || !source2.getProperties().containsKey(property)) {
			return 0;
		}

		return source1.getProperties().get(property).compareTo(source2.getProperties().get(property));
	}

	private boolean hasGraphDbNodeSource(Object object) {
		return object instanceof HGNode && ((HGNode) object).getNodeSource() instanceof GraphDbNodeSource;
	}
}
