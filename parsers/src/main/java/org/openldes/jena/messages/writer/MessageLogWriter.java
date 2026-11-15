package org.openldes.jena.messages.writer;

import java.io.OutputStream;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.jena.atlas.io.IO;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.atlas.lib.CharSpace;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.writer.DirectiveStyle;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.util.Context;
import org.openldes.jena.messages.StreamRDFMessages;

/**
 * A {@link StreamRDFMessages} that writes a message log in Turtle, TriG, N-Triples or N-Quads: the version announcement
 * first, then the prefixes, and a delimiter after every message. The delimiter is written after every message, and not
 * between messages, so that a consumer receives a message as soon as it is written, and so that a trailing empty message
 * survives a round trip.
 * <p>
 * Turtle and N-Triples have no named graphs: a quad written to such a log loses its graph.
 * <p>
 * The version announcement is controlled by the writer: version declarations in the input stream are not repeated,
 * since a label without the {@code -messages} suffix would stop a parser from reading messages.
 */
public final class MessageLogWriter implements StreamRDFMessages {

	private final StreamRDF inner;
	private final Consumer<StreamRDF> versionWriter;
	private final Runnable delimiter;
	private final PrefixMap prefixes;
	private final String base;
	private final Runnable close;
	private final boolean triplesOnly;

	private MessageLogWriter(StreamRDF inner, Consumer<StreamRDF> versionWriter, Runnable delimiter, PrefixMap prefixes,
			String base, Runnable close, boolean triplesOnly) {
		this.inner = inner;
		this.triplesOnly = triplesOnly;
		this.versionWriter = versionWriter;
		this.delimiter = delimiter;
		this.prefixes = prefixes;
		this.base = base;
		this.close = close;
	}

	/**
	 * @param lang      Turtle, TriG, N-Triples or N-Quads
	 * @param version   the version label to announce, or null to announce nothing (the announcement is made elsewhere)
	 * @param style     {@code MESSAGE} ({@link DirectiveStyle#KEYWORD}) or {@code @message .} ({@link DirectiveStyle#AT});
	 *                  only N-Triples and N-Quads always use {@code MESSAGE}
	 * @param prefixes  the prefixes to declare at the start (Turtle and TriG)
	 * @param base      the base IRI to declare at the start, or null
	 * @param charSpace UTF-8 or ASCII output for N-Triples and N-Quads
	 */
	public static MessageLogWriter create(OutputStream out, Lang lang, String version, DirectiveStyle style,
			PrefixMap prefixes, String base, CharSpace charSpace, Context context) {
		if (Lang.NTRIPLES.equals(lang) || Lang.NQUADS.equals(lang)) {
			WriterStreamRDFMessagesPlain writer = new WriterStreamRDFMessagesPlain(IO.wrapUTF8(out), charSpace);
			return new MessageLogWriter(writer, w -> {
				if (version != null) {
					writer.version(version);
				}
			}, writer::delimiter, null, null, writer::flushOutput, Lang.NTRIPLES.equals(lang));
		}
		if (Lang.TURTLE.equals(lang) || Lang.TRIG.equals(lang)) {
			WriterStreamRDFMessagesBlocks writer = new WriterStreamRDFMessagesBlocks(out, context);
			return new MessageLogWriter(writer, w -> {
				if (version != null) {
					writer.version(version);
				}
			}, () -> writer.delimiter(style), prefixes, base, () -> {
			}, Lang.TURTLE.equals(lang));
		}
		throw new IllegalArgumentException("No message log writer for " + lang.getName());
	}

	@Override
	public void start() {
		inner.start();
		versionWriter.accept(inner);
		if (base != null) {
			inner.base(base);
		}
		if (prefixes != null) {
			for (Map.Entry<String, String> entry : prefixes.getMapping().entrySet()) {
				inner.prefix(entry.getKey(), entry.getValue());
			}
		}
	}

	@Override
	public void triple(Triple triple) {
		inner.triple(triple);
	}

	@Override
	public void quad(Quad quad) {
		if (triplesOnly || Quad.isDefaultGraph(quad.getGraph())) {
			// Turtle and N-Triples have no graphs: the graph of the quad is dropped. A quad of the default graph is a
			// triple for every writer: Jena's TriG writer only recognizes one of the two default graph nodes.
			inner.triple(quad.asTriple());
		} else {
			inner.quad(quad);
		}
	}

	@Override
	public void base(String baseIri) {
		inner.base(baseIri);
	}

	@Override
	public void prefix(String prefix, String iri) {
		inner.prefix(prefix, iri);
	}

	@Override
	public void version(String version) {
		// The writer announces the version itself
	}

	@Override
	public void message() {
		delimiter.run();
	}

	@Override
	public void finish() {
		inner.finish();
		close.run();
	}
}
