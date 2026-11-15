package org.openldes.jena.messages;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RIOT;
import org.apache.jena.atlas.lib.CharSpace;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.riot.system.PrefixMapFactory;
import org.apache.jena.riot.writer.DirectiveStyle;
import org.apache.jena.sparql.util.Context;
import org.openldes.jena.messages.jelly.JellySupport;
import org.openldes.jena.messages.writer.MessageLogWriter;

/**
 * Writes RDF Message logs, in the style of Jena's {@code RDFWriter}:
 *
 * <pre>{@code
 * StreamRDFMessages out = RDFMessagesWriter.create().lang(Lang.TRIG).prefixes(prefixes).build(outputStream);
 * out.start();
 * out.quad(...); out.message();
 * out.finish();
 *
 * RDFMessagesWriter.create().lang(Lang.NQUADS).write(outputStream, messages);
 * }</pre>
 *
 * Turtle, TriG, N-Triples and N-Quads get a version announcement ({@code 1.2-messages} by default) and a delimiter after
 * every message. Jelly-RDF (when {@code jelly-jena} is on the classpath) uses its own framing: a stream of type
 * {@code MESSAGES}, with one frame per message by default.
 */
public final class RDFMessagesWriter {

	private Lang lang = Lang.TRIG;
	private String version = MessageVersions.DEFAULT;
	private boolean writeVersion = true;
	private DirectiveStyle style = DirectiveStyle.KEYWORD;
	private PrefixMap prefixes = PrefixMapFactory.create();
	private String base;
	private CharSpace charSpace = CharSpace.UTF8;
	private Context context;
	private boolean framePerMessage = true;

	private RDFMessagesWriter() {
	}

	public static RDFMessagesWriter create() {
		return new RDFMessagesWriter();
	}

	/** Turtle, TriG, N-Triples, N-Quads, or Jelly-RDF. TriG is the default. */
	public RDFMessagesWriter lang(Lang lang) {
		this.lang = lang;
		return this;
	}

	/** The version label to announce; it must end with {@code -messages}. The default is {@code 1.2-messages}. */
	public RDFMessagesWriter version(String version) {
		if (!MessageVersions.announcesMessages(version)) {
			throw new IllegalArgumentException("Not a version label of RDF Messages: " + version);
		}
		this.version = version;
		return this;
	}

	/** Whether to write the in-line version announcement; turn it off when the version is announced by the media type. */
	public RDFMessagesWriter writeVersion(boolean writeVersion) {
		this.writeVersion = writeVersion;
		return this;
	}

	/** {@code MESSAGE} and {@code PREFIX} ({@link DirectiveStyle#KEYWORD}, the default), or {@code @message .} and {@code @prefix}. */
	public RDFMessagesWriter directiveStyle(DirectiveStyle style) {
		this.style = style;
		return this;
	}

	/** The prefixes to declare once, at the start of the log (Turtle and TriG). */
	public RDFMessagesWriter prefixes(PrefixMap prefixes) {
		this.prefixes = prefixes;
		return this;
	}

	public RDFMessagesWriter base(String base) {
		this.base = base;
		return this;
	}

	public RDFMessagesWriter charSpace(CharSpace charSpace) {
		this.charSpace = charSpace;
		return this;
	}

	public RDFMessagesWriter context(Context context) {
		this.context = context;
		return this;
	}

	/** Jelly-RDF: whether every message is written as its own frame (the default), or frames are filled up. */
	public RDFMessagesWriter framePerMessage(boolean framePerMessage) {
		this.framePerMessage = framePerMessage;
		return this;
	}

	/** The writer, as a {@link StreamRDFMessages}. Call {@code start()} first and {@code finish()} at the end. */
	public StreamRDFMessages build(OutputStream out) {
		Context ctx = context == null ? contextFor(style) : context;
		if (JellySupport.isAvailable() && JellySupport.lang().equals(lang)) {
			return JellySupport.writer(out, framePerMessage);
		}
		return MessageLogWriter.create(out, lang, writeVersion ? version : null, style, prefixes, base, charSpace, ctx);
	}

	private static Context contextFor(DirectiveStyle style) {
		Context ctx = RIOT.getContext().copy();
		ctx.set(RIOT.symTurtleDirectiveStyle, style == DirectiveStyle.AT ? "at" : "sparql");
		return ctx;
	}

	/** Writes the messages as one log. */
	public void write(OutputStream out, Iterable<RdfMessage> messages) {
		StreamRDFMessages writer = build(out);
		writer.start();
		for (RdfMessage message : messages) {
			message.quads().forEachRemaining(writer::quad);
			writer.message();
		}
		writer.finish();
	}

	/** Writes the messages as one log in a string (not for Jelly-RDF, which is binary). */
	public String writeString(Iterable<RdfMessage> messages) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		write(bytes, messages);
		return bytes.toString(StandardCharsets.UTF_8);
	}
}
