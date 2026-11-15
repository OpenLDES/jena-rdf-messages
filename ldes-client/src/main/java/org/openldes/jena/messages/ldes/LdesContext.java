package org.openldes.jena.messages.ldes;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.path.Path;
import org.apache.jena.system.G;
import org.openldes.jena.messages.RdfMessage;

/**
 * The <a href="https://w3id.org/ldes/specification#context-information">context information</a> of an LDES, read from
 * the description message of the root page: what a client needs to read members, and what it communicates to the
 * processors further in the pipeline.
 *
 * @param streamId                  the IRI of the {@code ldes:EventStream}
 * @param rootPage                  the IRI of the root page, after redirects
 * @param description               the description message of the root page
 * @param descriptionGraph          the union graph of that message
 * @param mode                      the order of the LDES
 * @param membership                how the member identifier is found
 * @param timestampPath             {@code ldes:timestampPath}, or null
 * @param sequencePath              {@code ldes:sequencePath}, or null
 * @param versionOfPath             {@code ldes:versionOfPath}, or null
 * @param versionTimestampPath      {@code ldes:versionTimestampPath}, or null
 * @param versionSequencePath       {@code ldes:versionSequencePath}, or null
 * @param transactionPath           {@code ldes:transactionPath}, or null
 * @param transactionFinalizedPath  {@code ldes:transactionFinalizedPath}, or null
 * @param transactionFinalizedObject {@code ldes:transactionFinalizedObject}, or null (which means {@code true})
 * @param defaultOffset             the timezone of timestamps without a timezone, or null when unknown
 * @param pollingInterval           {@code ldes:pollingInterval}, or empty
 * @param shapes                    the {@code ldes:shape}s of the LDES
 * @param retentionPolicies         the {@code ldes:retentionPolicy} nodes of the LDES
 */
public record LdesContext(Node streamId, String rootPage, RdfMessage description, Graph descriptionGraph, OrderMode mode,
		Membership membership, Path timestampPath, Path sequencePath, Path versionOfPath, Path versionTimestampPath,
		Path versionSequencePath, Path transactionPath, Path transactionFinalizedPath, Node transactionFinalizedObject,
		ZoneOffset defaultOffset, Optional<Duration> pollingInterval, List<Node> shapes, List<Node> retentionPolicies) {

	/** The LDES version this client implements. */
	public int ldesVersion() {
		return 2;
	}

	/**
	 * Reads the context information from the description message of the root page.
	 *
	 * @param configuredOffset the default timezone the client was configured with, or null. The {@code ldes:defaultTimezone}
	 *                         of the LDES takes precedence, as the specification says.
	 */
	public static LdesContext read(RdfMessage first, String rootPage, ZoneOffset configuredOffset) {
		if (first == null || !PageDescription.isEventStreamDescription(first)) {
			throw new LdesClientException("The first message of the root page does not describe an ldes:EventStream:"
					+ " a page without a description of the LDES is not a root page");
		}
		Graph graph = first.unionGraph();
		List<Node> streams = graph.find(Node.ANY, LdesVocab.TYPE, LdesVocab.EVENT_STREAM).mapWith(t -> t.getSubject())
				.toSet().stream().toList();
		if (streams.size() != 1) {
			throw new LdesClientException("Expected exactly one ldes:EventStream in the root page, found " + streams.size());
		}
		Node stream = streams.get(0);

		Path timestampPath = path(graph, stream, LdesVocab.TIMESTAMP_PATH, "ldes:timestampPath");
		Path sequencePath = path(graph, stream, LdesVocab.SEQUENCE_PATH, "ldes:sequencePath");
		OrderMode mode = timestampPath != null ? (sequencePath != null ? OrderMode.TIMESTAMP_SEQUENCE : OrderMode.TIMESTAMP)
				: sequencePath != null ? OrderMode.SEQUENCE : OrderMode.IMPLICIT;

		Node timezone = PageDescription.single(graph, stream, LdesVocab.DEFAULT_TIMEZONE, "ldes:defaultTimezone");
		ZoneOffset defaultOffset = configuredOffset;
		if (timezone != null) {
			String value = timezone.isLiteral() ? timezone.getLiteralLexicalForm() : timezone.toString();
			defaultOffset = Timestamps.offset(value).orElseThrow(() -> new LdesClientException(
					"ldes:defaultTimezone must be Z or an offset between -14:00 and +14:00, found " + value));
		}

		Node interval = PageDescription.single(graph, stream, LdesVocab.POLLING_INTERVAL, "ldes:pollingInterval");
		Optional<Duration> pollingInterval = Optional.empty();
		if (interval != null && interval.isLiteral()) {
			try {
				pollingInterval = Optional.of(Duration.ofSeconds(Long.parseLong(interval.getLiteralLexicalForm().trim())));
			} catch (NumberFormatException e) {
				throw new LdesClientException("ldes:pollingInterval must be an xsd:integer, found " + interval);
			}
		}

		return new LdesContext(stream, rootPage, first, graph, mode, Membership.of(graph, stream), timestampPath,
				sequencePath, path(graph, stream, LdesVocab.VERSION_OF_PATH, "ldes:versionOfPath"),
				path(graph, stream, LdesVocab.VERSION_TIMESTAMP_PATH, "ldes:versionTimestampPath"),
				path(graph, stream, LdesVocab.VERSION_SEQUENCE_PATH, "ldes:versionSequencePath"),
				path(graph, stream, LdesVocab.TRANSACTION_PATH, "ldes:transactionPath"),
				path(graph, stream, LdesVocab.TRANSACTION_FINALIZED_PATH, "ldes:transactionFinalizedPath"),
				PageDescription.single(graph, stream, LdesVocab.TRANSACTION_FINALIZED_OBJECT, "ldes:transactionFinalizedObject"),
				defaultOffset, pollingInterval, G.listSP(graph, stream, LdesVocab.SHAPE),
				G.listSP(graph, stream, LdesVocab.RETENTION_POLICY));
	}

	private static Path path(Graph graph, Node stream, Node property, String what) {
		Node node = PageDescription.single(graph, stream, property, what);
		return node == null ? null : Paths.parse(graph, node);
	}
}
