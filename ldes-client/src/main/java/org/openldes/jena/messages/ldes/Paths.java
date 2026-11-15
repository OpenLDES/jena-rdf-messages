package org.openldes.jena.messages.ldes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.shacl.engine.ShaclPaths;
import org.apache.jena.sparql.path.P_Link;
import org.apache.jena.sparql.path.Path;
import org.openldes.jena.messages.RdfMessage;

/**
 * SHACL property paths as the LDES uses them: {@code ldes:timestampPath}, {@code ldes:memberPath} and the others are
 * evaluated with the member identifier as the focus node and the union graph of the member message as the data graph.
 */
final class Paths {

	private Paths() {
	}

	/** Parses the path that a node in the description graph stands for: an IRI, or a SHACL path structure. */
	static Path parse(Graph description, Node node) {
		try {
			return ShaclPaths.parsePath(description, node);
		} catch (RuntimeException e) {
			throw new LdesClientException("Not a SHACL property path: " + node + " (" + e.getMessage() + ")", e);
		}
	}

	/** A plain predicate path, which can be evaluated by scanning the quads of a message. */
	static boolean isPredicate(Path path) {
		return path instanceof P_Link;
	}

	/** The values of a path from a focus node, in the union graph of a message. */
	static List<Node> values(Path path, Node focus, RdfMessage message, Graph unionGraph) {
		if (path instanceof P_Link link) {
			List<Node> values = new ArrayList<>();
			message.quads().forEachRemaining(quad -> {
				if (quad.getSubject().equals(focus) && quad.getPredicate().equals(link.getNode())) {
					values.add(quad.getObject());
				}
			});
			return values;
		}
		return new ArrayList<>(ShaclPaths.valueNodes(unionGraph, focus, path));
	}

	/** The subjects of a message from which the path leads to a value. */
	static Set<Node> subjectsWithValue(Path path, RdfMessage message, Graph unionGraph) {
		Set<Node> subjects = new LinkedHashSet<>();
		if (path instanceof P_Link link) {
			message.quads().forEachRemaining(quad -> {
				if (quad.getPredicate().equals(link.getNode())) {
					subjects.add(quad.getSubject());
				}
			});
			return subjects;
		}
		Set<Node> candidates = new LinkedHashSet<>();
		message.quads().forEachRemaining(quad -> candidates.add(quad.getSubject()));
		for (Node candidate : candidates) {
			if (!ShaclPaths.valueNodes(unionGraph, candidate, path).isEmpty()) {
				subjects.add(candidate);
			}
		}
		return subjects;
	}

	static String string(Path path) {
		return path == null ? "none" : path.toString();
	}

}
