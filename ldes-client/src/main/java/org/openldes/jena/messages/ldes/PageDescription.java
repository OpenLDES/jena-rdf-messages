package org.openldes.jena.messages.ldes;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.system.G;
import org.openldes.jena.messages.RdfMessage;
import org.openldes.jena.messages.ldes.Sequences.Sequence;

/**
 * The <a href="https://w3id.org/ldes/specification#log-structure">description message</a> of a page: its relations,
 * whether it (or a page it links to) is immutable, and its page number.
 */
public final class PageDescription {

	/** One {@code ldes:relation}: the page to follow and the interval of the order that can be found there. */
	public record Relation(String page, OrderingKey lower, OrderingKey upper, Long pageNumber) {
	}

	private final Graph graph;
	private final List<Relation> relations;
	private final boolean immutable;
	private final Set<String> immutablePages;
	private final Long pageNumber;

	private PageDescription(Graph graph, List<Relation> relations, boolean immutable, Set<String> immutablePages,
			Long pageNumber) {
		this.graph = graph;
		this.relations = List.copyOf(relations);
		this.immutable = immutable;
		this.immutablePages = Set.copyOf(immutablePages);
		this.pageNumber = pageNumber;
	}

	/** The description message, as a graph (the union of its graphs). */
	public Graph graph() {
		return graph;
	}

	public List<Relation> relations() {
		return relations;
	}

	/** {@code <> ldes:immutable true} */
	public boolean immutable() {
		return immutable;
	}

	/** The pages marked immutable by this page: {@code <P> ldes:immutable true}. */
	public Set<String> immutablePages() {
		return immutablePages;
	}

	/** {@code <> ldes:pageNumber N}, in implicit order. */
	public Optional<Long> pageNumber() {
		return Optional.ofNullable(pageNumber);
	}

	/** Whether a message describes an {@code ldes:EventStream}. */
	public static boolean isEventStreamDescription(RdfMessage message) {
		return message.stream().anyMatch(quad -> quad.getPredicate().equals(LdesVocab.TYPE)
				&& quad.getObject().equals(LdesVocab.EVENT_STREAM));
	}

	/**
	 * A message is the description message when it has the page IRI as the subject of a triple, or when it describes
	 * the LDES using a subject with {@code rdf:type ldes:EventStream}.
	 */
	public static boolean isDescriptionMessage(RdfMessage message, String pageUrl) {
		if (isEventStreamDescription(message)) {
			return true;
		}
		Node page = NodeFactory.createURI(pageUrl);
		return message.stream().anyMatch(quad -> quad.getSubject().equals(page));
	}

	/** Whether the first statements of a page are those of an LDES 1.0 (TREE) page. */
	public static boolean looksLikeLdes1(RdfMessage first) {
		return first.stream().anyMatch(quad -> {
			String predicate = quad.getPredicate().getURI();
			for (String tree : LdesVocab.LDES1_PREDICATES) {
				if (tree.equals(predicate)) {
					return true;
				}
			}
			return false;
		});
	}

	/**
	 * Reads the relations and the page-level flags of a description message.
	 *
	 * @param pageUrl       the IRI of the page {@code <>}, after following redirects
	 * @param mode          the order of the LDES, which determines what the bounds of a relation mean
	 * @param defaultOffset the timezone of timestamps without one, or null
	 */
	public static PageDescription read(RdfMessage message, String pageUrl, OrderMode mode, ZoneOffset defaultOffset) {
		Graph graph = message.unionGraph();
		Node page = NodeFactory.createURI(pageUrl);
		Set<String> immutablePages = new LinkedHashSet<>();
		graph.find(Node.ANY, LdesVocab.IMMUTABLE, Node.ANY).forEachRemaining(triple -> {
			if (triple.getSubject().isURI() && isTrue(triple.getObject())) {
				immutablePages.add(triple.getSubject().getURI());
			}
		});
		boolean immutable = immutablePages.contains(pageUrl);
		Long pageNumber = nonNegativeInteger(single(graph, page, LdesVocab.PAGE_NUMBER, "ldes:pageNumber of the page"),
				"ldes:pageNumber");
		List<Relation> relations = new ArrayList<>();
		for (Node relation : G.listSP(graph, page, LdesVocab.RELATION)) {
			relations.add(readRelation(graph, relation, mode, defaultOffset));
		}
		return new PageDescription(graph, relations, immutable, immutablePages, pageNumber);
	}

	private static boolean isTrue(Node node) {
		return node.isLiteral() && ("true".equals(node.getLiteralLexicalForm()) || "1".equals(node.getLiteralLexicalForm()));
	}

	static Node single(Graph graph, Node subject, Node predicate, String what) {
		List<Node> objects = G.listSP(graph, subject, predicate);
		if (objects.size() > 1) {
			throw new LdesClientException("Expected at most one " + what + ", found " + objects.size());
		}
		return objects.isEmpty() ? null : objects.get(0);
	}

	private static Long nonNegativeInteger(Node node, String what) {
		if (node == null) {
			return null;
		}
		try {
			long value = Long.parseLong(node.getLiteralLexicalForm().trim());
			if (value < 0) {
				throw new NumberFormatException();
			}
			return value;
		} catch (RuntimeException e) {
			throw new LdesClientException(what + " must be a non-negative integer, found " + node);
		}
	}

	static Timestamps.Parsed timestamp(Node node, String what, ZoneOffset defaultOffset) {
		if (node == null) {
			return null;
		}
		if (!node.isLiteral()) {
			throw new LdesClientException(what + " must be an xsd:dateTime, found " + node);
		}
		return Timestamps.parse(node.getLiteralLexicalForm(), defaultOffset)
				.orElseThrow(() -> new LdesClientException(what + " must be an xsd:dateTime, found " + node));
	}

	private static Relation readRelation(Graph graph, Node relation, OrderMode mode, ZoneOffset defaultOffset) {
		List<Node> pages = G.listSP(graph, relation, LdesVocab.PAGE);
		if (pages.size() != 1 || !pages.get(0).isURI()) {
			throw new LdesClientException("A relation must have exactly one ldes:page that is an IRI, found " + pages.size());
		}
		String page = pages.get(0).getURI();
		if (mode == OrderMode.IMPLICIT) {
			Long pageNumber = nonNegativeInteger(single(graph, relation, LdesVocab.PAGE_NUMBER, "ldes:pageNumber"),
					"ldes:pageNumber");
			Long until = nonNegativeInteger(single(graph, relation, LdesVocab.PAGE_NUMBER_UNTIL, "ldes:pageNumberUntil"),
					"ldes:pageNumberUntil");
			if (pageNumber == null) {
				return new Relation(page, null, null, null);
			}
			return new Relation(page, OrderingKey.ofPage(pageNumber, 0), OrderingKey.ofPage(until == null ? pageNumber + 1 : until, 0),
					pageNumber);
		}
		Timestamps.Parsed timeFrom = timestamp(single(graph, relation, LdesVocab.TIME_FROM, "ldes:timeFrom"), "ldes:timeFrom",
				defaultOffset);
		Timestamps.Parsed timeUntil = timestamp(single(graph, relation, LdesVocab.TIME_UNTIL, "ldes:timeUntil"),
				"ldes:timeUntil", defaultOffset);
		Node sequenceFrom = single(graph, relation, LdesVocab.SEQUENCE_FROM, "ldes:sequenceFrom");
		Node sequenceUntil = single(graph, relation, LdesVocab.SEQUENCE_UNTIL, "ldes:sequenceUntil");
		if (mode != OrderMode.SEQUENCE) {
			if (sequenceFrom != null && timeFrom == null) {
				throw new LdesClientException("ldes:sequenceFrom must be accompanied by ldes:timeFrom");
			}
			if (sequenceUntil != null && timeUntil == null) {
				throw new LdesClientException("ldes:sequenceUntil must be accompanied by ldes:timeUntil");
			}
		}
		return new Relation(page, bound(mode, timeFrom, sequenceFrom), bound(mode, timeUntil, sequenceUntil), null);
	}

	private static OrderingKey bound(OrderMode mode, Timestamps.Parsed instant, Node sequenceNode) {
		Sequence sequence = sequenceNode != null && sequenceNode.isLiteral() ? Sequence.of(sequenceNode) : null;
		if (mode == OrderMode.SEQUENCE) {
			return sequence == null ? null : OrderingKey.ofSequence(sequence);
		}
		if (instant == null) {
			return null;
		}
		return OrderingKey.ofTime(instant.instant(), instant.local(), mode == OrderMode.TIMESTAMP_SEQUENCE ? sequence : null);
	}
}
