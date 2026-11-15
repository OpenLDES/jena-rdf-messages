package org.openldes.jena.messages;

import java.util.Objects;
import java.util.function.Consumer;

import org.apache.jena.graph.Triple;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.riot.system.PrefixMapFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;

/**
 * Collects the triples and quads of every message in a {@link DatasetGraph}, and hands each complete {@link RdfMessage}
 * to a consumer. Messages are numbered from 0 in the order of the log.
 * <p>
 * The consumer is called from the parsing thread, as soon as the message is complete: the parser does not read ahead.
 * Only one message is held in memory at a time, unless the consumer keeps it.
 */
public final class MessageCollector implements StreamRDFMessages {

	private final Consumer<RdfMessage> consumer;
	private final PrefixMap prefixes = PrefixMapFactory.create();
	private DatasetGraph current = DatasetGraphFactory.create();
	private long index = 0;

	public MessageCollector(Consumer<RdfMessage> consumer) {
		this.consumer = Objects.requireNonNull(consumer, "consumer");
	}

	/** The number of messages that have been collected so far. */
	public long count() {
		return index;
	}

	@Override
	public void start() {
		// nothing to prepare
	}

	@Override
	public void triple(Triple triple) {
		current.getDefaultGraph().add(triple);
	}

	@Override
	public void quad(Quad quad) {
		current.add(quad);
	}

	@Override
	public void base(String base) {
		// A base IRI is resolved by the parser
	}

	@Override
	public void prefix(String prefix, String iri) {
		prefixes.add(prefix, iri);
	}

	@Override
	public void version(String version) {
		// Version labels are handled by the parser
	}

	@Override
	public void message() {
		RdfMessage message = RdfMessage.of(index++, current, PrefixMapFactory.create(prefixes));
		current = DatasetGraphFactory.create();
		consumer.accept(message);
	}

	@Override
	public void finish() {
		// The parser finalizes an open message before finishing
	}
}
