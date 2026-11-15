package org.openldes.jena.messages.ldes;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.system.G;
import org.openldes.jena.messages.RdfMessage;

/**
 * How the member identifier is found in a member message, see
 * <a href="https://w3id.org/ldes/specification#membership">members and membership</a>: directly, with the triple
 * {@code <ES> ldes:member ?m}, or indirectly as declared by the {@code ldes:EventStream} with {@code ldes:memberGraph},
 * {@code ldes:memberType} or {@code ldes:memberPath}.
 */
public sealed interface Membership {

	/** {@code <ES> ldes:member ?m}: the message contains exactly one such triple. */
	record Direct(Node stream) implements Membership {
	}

	/** {@code ldes:memberGraph true}: the member is the only named graph of the message. */
	record ByGraph() implements Membership {
	}

	/** {@code ldes:memberType <C>}: the member is the only subject with that type. */
	record ByType(Node type) implements Membership {
	}

	/** {@code ldes:memberPath <P>}: the member is the only subject from which the path leads to a value. */
	record ByPath(Path path) implements Membership {
	}

	/** Reads the rule that the {@code ldes:EventStream} declares. At most one of the indirect properties may be set. */
	static Membership of(Graph description, Node stream) {
		List<Node> graphs = G.listSP(description, stream, LdesVocab.MEMBER_GRAPH).stream()
				.filter(node -> node.isLiteral() && ("true".equals(node.getLiteralLexicalForm()) || "1".equals(node.getLiteralLexicalForm())))
				.toList();
		List<Node> types = G.listSP(description, stream, LdesVocab.MEMBER_TYPE);
		List<Node> paths = G.listSP(description, stream, LdesVocab.MEMBER_PATH);
		int set = (graphs.isEmpty() ? 0 : 1) + (types.isEmpty() ? 0 : 1) + (paths.isEmpty() ? 0 : 1);
		if (set > 1 || types.size() > 1 || paths.size() > 1) {
			throw new LdesClientException("The ldes:EventStream sets more than one of ldes:memberGraph, ldes:memberType"
					+ " and ldes:memberPath, or sets one of them more than once");
		}
		if (!graphs.isEmpty()) {
			return new ByGraph();
		}
		if (!types.isEmpty()) {
			return new ByType(types.get(0));
		}
		if (!paths.isEmpty()) {
			return new ByPath(Paths.parse(description, paths.get(0)));
		}
		return new Direct(stream);
	}

	/** Finds the member identifier of a member message, or fails when the message does not satisfy the rule. */
	default Node findMemberId(RdfMessage message, Graph unionGraph) {
		Set<Node> candidates = new LinkedHashSet<>();
		String what;
		switch (this) {
			case Direct direct -> {
				what = "<ES> ldes:member triple";
				message.quads().forEachRemaining(quad -> {
					if (quad.getSubject().equals(direct.stream()) && quad.getPredicate().equals(LdesVocab.MEMBER)) {
						candidates.add(quad.getObject());
					}
				});
			}
			case ByGraph ignored -> {
				what = "named graph";
				message.quads().forEachRemaining(quad -> {
					if (!Quad.isDefaultGraph(quad.getGraph())) {
						candidates.add(quad.getGraph());
					}
				});
			}
			case ByType byType -> {
				what = "subject with rdf:type " + byType.type();
				message.quads().forEachRemaining(quad -> {
					if (quad.getPredicate().equals(LdesVocab.TYPE) && quad.getObject().equals(byType.type())) {
						candidates.add(quad.getSubject());
					}
				});
			}
			case ByPath byPath -> {
				what = "subject from which ldes:memberPath leads to a value";
				candidates.addAll(Paths.subjectsWithValue(byPath.path(), message, unionGraph));
			}
		}
		if (candidates.size() != 1) {
			throw new LdesClientException("Expected exactly one " + what + " in the member message, found "
					+ candidates.size());
		}
		Node id = candidates.iterator().next();
		if (!id.isURI()) {
			throw new LdesClientException("The member identifier found using " + what + " is not an IRI: " + id);
		}
		return id;
	}
}
