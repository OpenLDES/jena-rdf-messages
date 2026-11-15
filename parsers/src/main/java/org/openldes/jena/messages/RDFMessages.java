package org.openldes.jena.messages;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.function.Consumer;

import org.apache.jena.riot.Lang;

/**
 * Entry points for RDF Messages on Apache Jena, see the <a href="https://w3c-cg.github.io/rsp/spec/messages">RDF
 * Messages specification</a>.
 *
 * <pre>{@code
 * // Push: one callback per message, as soon as the message is complete
 * RDFMessages.parse(in, Lang.TRIG, message -> store(message.dataset()));
 *
 * // Pull: an iterator that reads ahead a bounded number of messages
 * try (MessageLog log = RDFMessages.read(in, Lang.NQUADS)) {
 *     for (RdfMessage message : log) { ... }
 * }
 *
 * // Low level: triples, quads and message boundaries, without building datasets
 * RDFMessages.parse(in, Lang.TRIG, streamRDFMessages);
 *
 * // Write a message log
 * RDFMessages.write(out, Lang.TRIG, messages);
 * }</pre>
 *
 * For all options (base IRI, media type with version parameter, error handler, ...) use {@link RDFMessagesParser} and
 * {@link RDFMessagesWriter}.
 */
public final class RDFMessages {

	private RDFMessages() {
	}

	/** Parses a log, sending triples, quads and message boundaries to the sink. */
	public static void parse(InputStream in, Lang lang, StreamRDFMessages sink) {
		RDFMessagesParser.create().source(in).lang(lang).parse(sink);
	}

	/** Parses a log, handing every message to the consumer as soon as it is complete. */
	public static void parse(InputStream in, Lang lang, Consumer<RdfMessage> consumer) {
		RDFMessagesParser.create().source(in).lang(lang).forEach(consumer);
	}

	/** Parses a log from a string. */
	public static List<RdfMessage> parse(String text, Lang lang) {
		return RDFMessagesParser.create().fromString(text).lang(lang).toList();
	}

	/** Reads a log message by message. Close the log when it is not read to the end. */
	public static MessageLog read(InputStream in, Lang lang) {
		return RDFMessagesParser.create().source(in).lang(lang).iterator();
	}

	/** Reads a file or IRI message by message, deriving the serialization from the media type or file name. */
	public static MessageLog read(String fileOrIri) {
		return RDFMessagesParser.create().source(fileOrIri).iterator();
	}

	/** Writes messages as a log, announcing {@code 1.2-messages}. */
	public static void write(OutputStream out, Lang lang, Iterable<RdfMessage> messages) {
		RDFMessagesWriter.create().lang(lang).write(out, messages);
	}

	/** Writes messages as a log in a string. */
	public static String writeString(Lang lang, Iterable<RdfMessage> messages) {
		return RDFMessagesWriter.create().lang(lang).writeString(messages);
	}
}
