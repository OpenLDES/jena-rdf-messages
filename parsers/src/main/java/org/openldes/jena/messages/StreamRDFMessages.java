package org.openldes.jena.messages;

import org.apache.jena.graph.Triple;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.sparql.core.Quad;

/**
 * A {@link StreamRDF} that knows about message boundaries: the parsers of RDF Message logs emit the triples and quads of a
 * message as usual, and call {@link #message()} when the message is complete.
 * <p>
 * The contract follows the <a href="https://w3c-cg.github.io/rsp/spec/messages#message-delimiters">RDF Messages
 * specification</a>: every triple or quad belongs to the current message; {@code message()} finalizes it (the message is
 * empty when no triple or quad was emitted since the previous boundary); at the end of the input, a message that is open
 * is finalized before {@link #finish()}. A trailing delimiter does not create an extra empty message.
 * <p>
 * Prefix and base declarations are not scoped to a message and arrive through {@link #prefix(String, String)} and
 * {@link #base(String)} as they are read. Blank nodes are scoped to their message: the same label in two messages gives
 * two different nodes.
 * <p>
 * This is the Java sub-interface of {@code StreamRDF} that was suggested in
 * <a href="https://github.com/apache/jena/issues/4363">apache/jena#4363</a>, so that the parsers of Apache Jena itself
 * stay untouched.
 */
public interface StreamRDFMessages extends StreamRDF {

	/**
	 * The current message is complete: all triples and quads since {@link #start()} or the previous call form one RDF
	 * Message.
	 */
	void message();

	/**
	 * Adapts a plain {@link StreamRDF} to this interface: message boundaries are ignored, so all messages are merged.
	 */
	static StreamRDFMessages ignoringMessages(StreamRDF base) {
		return new StreamRDFMessages() {
			@Override
			public void start() {
				base.start();
			}

			@Override
			public void triple(Triple triple) {
				base.triple(triple);
			}

			@Override
			public void quad(Quad quad) {
				base.quad(quad);
			}

			@Override
			public void base(String baseIri) {
				base.base(baseIri);
			}

			@Override
			public void prefix(String prefix, String iri) {
				base.prefix(prefix, iri);
			}

			@Override
			public void version(String version) {
				base.version(version);
			}

			@Override
			public void message() {
				// no-op: the messages are merged
			}

			@Override
			public void finish() {
				base.finish();
			}
		};
	}
}
