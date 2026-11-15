package org.openldes.jena.messages;

import java.io.InputStream;
import java.io.Reader;

import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ParserProfile;
import org.apache.jena.sparql.util.Context;

/**
 * Reads an RDF Message log in one serialization and sends its messages to a {@link StreamRDFMessages}.
 * <p>
 * Implementations exist for Turtle, TriG, N-Triples, N-Quads (the RIOT parsers of Apache Jena with the message
 * delimiters), RDF/XML and Jelly-RDF. Other serializations can be added with a {@link MessageReaderProvider}.
 */
public interface MessageReader {

	/**
	 * Reads a log.
	 *
	 * @param input     the log
	 * @param baseURI   the base IRI, or null
	 * @param profile   the parser profile (error handler, blank node allocation, IRI resolution)
	 * @param announced whether the log was announced as an RDF Message log externally, e.g. by the {@code version}
	 *                  parameter of its media type. A log that announces itself in-line is read as a message log in any
	 *                  case; a serialization with its own framing (such as Jelly-RDF) ignores this.
	 * @param output    the destination of the messages
	 * @param context   the RIOT context
	 */
	void read(InputStream input, String baseURI, ParserProfile profile, boolean announced, StreamRDFMessages output,
			Context context);

	/** Reads a log from a character stream. Binary serializations do not support this. */
	default void read(Reader reader, String baseURI, ParserProfile profile, boolean announced, StreamRDFMessages output,
			Context context) {
		throw new RiotException("Reading from a java.io.Reader is not supported for this serialization; use an InputStream");
	}
}
