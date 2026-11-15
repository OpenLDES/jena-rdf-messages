package org.openldes.jena.messages;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.jena.atlas.io.IO;
import org.apache.jena.atlas.web.ContentType;
import org.apache.jena.atlas.web.TypedInputStream;
import org.apache.jena.irix.IRIs;
import org.apache.jena.irix.IRIxResolver;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFParserBuilder;
import org.apache.jena.riot.RIOT;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.lang.LabelToNode;
import org.apache.jena.riot.system.ErrorHandler;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.riot.system.FactoryRDF;
import org.apache.jena.riot.system.ParserProfile;
import org.apache.jena.riot.system.ParserProfileStd;
import org.apache.jena.riot.system.PrefixMapFactory;
import org.apache.jena.riot.system.RiotLib;
import org.apache.jena.riot.system.SyntaxLabels;
import org.apache.jena.sparql.util.Context;
import org.openldes.jena.messages.lang.MessageFramer;

/**
 * Parses an RDF Message log, in the style of Jena's {@link RDFParser}:
 *
 * <pre>{@code
 * RDFMessagesParser.create().source(in).lang(Lang.TRIG).forEach(message -> ...);
 * try (MessageLog log = RDFMessagesParser.create().source("page.trig").iterator()) { ... }
 * }</pre>
 *
 * The serialization is the {@link #lang(Lang)}, or is derived from the {@link #contentType(String) media type} or the
 * file name. A log in Turtle, TriG, N-Triples or N-Quads is read as a message log when it announces itself
 * ({@code VERSION "1.2-messages"}), when the media type does ({@code version=1.2-messages}), or when
 * {@link #messages(boolean)} says so. RDF/XML and Jelly-RDF logs have their own framing. Any other serialization that
 * Jena knows is read as one message, which is how the LDES specification reads a page without message delimiters.
 * <p>
 * Parsing is streaming: a message is handed over as soon as its delimiter is read, and nothing is buffered beyond the
 * current message. The pull-based {@link #iterator()} reads ahead by at most {@link #bufferedMessages(int)} messages.
 */
public final class RDFMessagesParser {

	/** The default number of messages that {@link #iterator()} reads ahead. */
	public static final int DEFAULT_BUFFERED_MESSAGES = 512;

	private InputStream inputStream;
	private Reader reader;
	private String location;
	private Lang lang;
	private String baseURI;
	private Boolean messages;
	private ErrorHandler errorHandler;
	private LabelToNode labelToNode;
	private boolean strict;
	private Context context;
	private int bufferedMessages = DEFAULT_BUFFERED_MESSAGES;

	private RDFMessagesParser() {
	}

	public static RDFMessagesParser create() {
		return new RDFMessagesParser();
	}

	/** Reads from a byte stream. The stream is closed when parsing finishes. */
	public RDFMessagesParser source(InputStream input) {
		this.inputStream = Objects.requireNonNull(input);
		return this;
	}

	/** Reads from a character stream (not for binary serializations). The reader is closed when parsing finishes. */
	public RDFMessagesParser source(Reader input) {
		this.reader = Objects.requireNonNull(input);
		return this;
	}

	/** Reads a file (optionally gzip-compressed, by its extension) or an {@code http(s):} or {@code file:} IRI. */
	public RDFMessagesParser source(String fileOrIri) {
		this.location = Objects.requireNonNull(fileOrIri);
		return this;
	}

	public RDFMessagesParser source(Path path) {
		return source(path.toString());
	}

	/** Reads the given text. */
	public RDFMessagesParser fromString(String text) {
		return source(new StringReader(text));
	}

	public RDFMessagesParser lang(Lang lang) {
		this.lang = lang;
		return this;
	}

	/**
	 * Sets the serialization from a media type, and reads the log as a message log when the media type has a
	 * {@code version} parameter that announces messages, e.g. {@code application/trig; version=1.2-messages}.
	 */
	public RDFMessagesParser contentType(String contentType) {
		if (contentType != null && !contentType.isBlank()) {
			Lang fromType = RDFLanguages.contentTypeToLang(ContentType.create(contentType));
			if (fromType != null) {
				this.lang = fromType;
			}
			MessageVersions.versionOf(contentType).ifPresent(version -> {
				if (MessageVersions.announcesMessages(version)) {
					this.messages = true;
				}
			});
		}
		return this;
	}

	public RDFMessagesParser base(String baseURI) {
		this.baseURI = baseURI;
		return this;
	}

	/**
	 * Reads the log as a message log even without an in-line announcement, for example when the announcement was made
	 * through another channel. An in-line announcement always counts; without any announcement, a delimiter is an error.
	 */
	public RDFMessagesParser messages(boolean messages) {
		this.messages = messages;
		return this;
	}

	public RDFMessagesParser errorHandler(ErrorHandler errorHandler) {
		this.errorHandler = errorHandler;
		return this;
	}

	/** How blank node labels are turned into nodes within a message. The default is Jena's (a hash per document). */
	public RDFMessagesParser labelToNode(LabelToNode labelToNode) {
		this.labelToNode = labelToNode;
		return this;
	}

	public RDFMessagesParser strict(boolean strict) {
		this.strict = strict;
		return this;
	}

	public RDFMessagesParser context(Context context) {
		this.context = context;
		return this;
	}

	/** How many messages {@link #iterator()} reads ahead; at most this many messages are in memory, plus the one in use. */
	public RDFMessagesParser bufferedMessages(int bufferedMessages) {
		this.bufferedMessages = bufferedMessages;
		return this;
	}

	// ---- Running the parser

	/** Parses the log, sending the triples, quads and message boundaries to the sink. */
	public void parse(StreamRDFMessages sink) {
		Objects.requireNonNull(sink, "sink");
		Opened opened = open();
		try {
			Lang language = opened.lang();
			if (language == null) {
				throw new RiotException("The serialization of the message log is unknown: set lang() or contentType()");
			}
			boolean announced = Boolean.TRUE.equals(opened.messages());
			ParserProfile profile = profile(language);
			Context ctx = context == null ? RIOT.getContext() : context;
			Optional<MessageReader> messageReader = MessageReaders.get(language);
			if (messageReader.isPresent()) {
				if (opened.reader() != null) {
					messageReader.get().read(opened.reader(), baseURI, profile, announced, sink, ctx);
				} else {
					messageReader.get().read(opened.input(), baseURI, profile, announced, sink, ctx);
				}
			} else {
				// No notion of messages in this serialization: one message
				if (announced) {
					throw new RiotException("The media type announces an RDF Message log, but " + language.getName()
							+ " has no message delimiters");
				}
				parseAsOneMessage(opened, language, profile, sink, ctx);
			}
		} finally {
			opened.close();
		}
	}

	/** Parses the log, handing every message to the consumer as soon as it is complete. */
	public void forEach(Consumer<RdfMessage> consumer) {
		parse(new MessageCollector(consumer));
	}

	/** Parses the whole log into a list. */
	public List<RdfMessage> toList() {
		List<RdfMessage> result = new java.util.ArrayList<>();
		forEach(result::add);
		return result;
	}

	/**
	 * Reads the log message by message, in a virtual thread that is at most {@link #bufferedMessages(int)} messages
	 * ahead. Close the log when it is not read to the end.
	 */
	public MessageLog iterator() {
		Opened opened = open();
		RDFMessagesParser parser = this;
		return MessageLog.start(bufferedMessages, sink -> parser.parseOpened(opened, sink), opened::close);
	}

	/** The messages as a stream; closing the stream closes the log. */
	public Stream<RdfMessage> stream() {
		return iterator().stream();
	}

	private void parseOpened(Opened opened, StreamRDFMessages sink) {
		Lang language = opened.lang();
		if (language == null) {
			throw new RiotException("The serialization of the message log is unknown: set lang() or contentType()");
		}
		boolean announced = Boolean.TRUE.equals(opened.messages());
		ParserProfile profile = profile(language);
		Context ctx = context == null ? RIOT.getContext() : context;
		Optional<MessageReader> messageReader = MessageReaders.get(language);
		if (messageReader.isPresent()) {
			if (opened.reader() != null) {
				messageReader.get().read(opened.reader(), baseURI, profile, announced, sink, ctx);
			} else {
				messageReader.get().read(opened.input(), baseURI, profile, announced, sink, ctx);
			}
		} else {
			if (announced) {
				throw new RiotException("The media type announces an RDF Message log, but " + language.getName()
						+ " has no message delimiters");
			}
			parseAsOneMessage(opened, language, profile, sink, ctx);
		}
	}

	// Jena deprecates parsing from a Reader, but it is the only way to keep a Reader source streaming
	@SuppressWarnings("deprecation")
	private void parseAsOneMessage(Opened opened, Lang language, ParserProfile profile, StreamRDFMessages sink,
			Context ctx) {
		MessageFramer framer = new MessageFramer(sink, profile, false);
		RDFParserBuilder builder = RDFParser.create().lang(language).errorHandler(profile.getErrorHandler()).context(ctx)
				.strict(strict);
		if (labelToNode != null) {
			builder.labelToNode(labelToNode);
		}
		if (baseURI != null) {
			builder.base(baseURI);
		}
		if (opened.reader() != null) {
			builder.source(opened.reader());
		} else {
			builder.source(opened.input());
		}
		// Not every Jena parser calls start() and finish() on its output (the JSON-LD parser does not)
		framer.start();
		builder.parse(framer);
		framer.finish();
	}

	// ---- Opening the source

	private record Opened(InputStream input, Reader reader, Lang lang, Boolean messages) {
		void close() {
			if (input != null) {
				IO.close(input);
			}
			if (reader != null) {
				IO.close(reader);
			}
		}
	}

	private Opened open() {
		if (inputStream != null) {
			return new Opened(inputStream, null, lang, messages);
		}
		if (reader != null) {
			return new Opened(null, reader, lang, messages);
		}
		if (location == null) {
			throw new RiotException("No source: set source(...) on the parser");
		}
		Lang language = lang;
		Boolean announced = messages;
		TypedInputStream in = RDFDataMgr.open(location);
		if (in == null) {
			throw new RiotException("Not found: " + location);
		}
		if (language == null) {
			String contentType = in.getContentType();
			if (contentType != null && !isGenericType(contentType)) {
				language = RDFLanguages.contentTypeToLang(ContentType.create(contentType));
			}
			if (language == null) {
				language = RDFLanguages.filenameToLang(stripGzip(location));
			}
		}
		if (announced == null && MessageVersions.versionOf(in.getContentType()).filter(MessageVersions::announcesMessages).isPresent()) {
			announced = true;
		}
		if (baseURI == null && !location.startsWith("http:") && !location.startsWith("https:")) {
			baseURI = IRIs.toBase(location);
		} else if (baseURI == null) {
			baseURI = location;
		}
		// RDFDataMgr.open already decompresses gzip sources, including local files.
		return new Opened(in, null, language, announced);
	}

	private static boolean isGenericType(String contentType) {
		return contentType.startsWith("text/plain") || contentType.startsWith("application/octet-stream");
	}

	private static String stripGzip(String location) {
		return location.endsWith(".gz") ? location.substring(0, location.length() - 3) : location;
	}

	// ---- The parser profile, as RDFParser creates it

	private ParserProfile profile(Lang language) {
		ErrorHandler handler = errorHandler != null ? errorHandler : ErrorHandlerFactory.errorHandlerStd;
		LabelToNode labels = labelToNode != null ? labelToNode : SyntaxLabels.createLabelToNode();
		FactoryRDF factory = RiotLib.factoryRDF(labels);
		boolean resolve = true;
		boolean allowRelative = false;
		boolean checking = true;
		String baseStr = baseURI;
		if (Lang.NTRIPLES.equals(language) || Lang.NQUADS.equals(language)) {
			baseStr = null;
			if (!strict) {
				allowRelative = true;
				checking = false;
			} else {
				resolve = false;
			}
		} else if (baseStr == null) {
			baseStr = IRIs.getBaseStr();
		}
		IRIxResolver resolver = IRIxResolver.create().base(baseStr).resolve(resolve).allowRelative(allowRelative).build();
		Context ctx = context == null ? RIOT.getContext().copy() : context;
		return new ParserProfileStd(factory, handler, resolver, PrefixMapFactory.create(), ctx, checking, strict);
	}

	/** Reads a whole file into memory; convenience for small logs. */
	public static List<RdfMessage> readAll(Path path) {
		return create().source(path).toList();
	}
}
