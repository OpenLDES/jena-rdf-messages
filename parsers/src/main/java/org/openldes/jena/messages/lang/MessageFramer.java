package org.openldes.jena.messages.lang;

import org.apache.jena.graph.Triple;
import org.apache.jena.riot.RiotParseException;
import org.apache.jena.riot.system.ErrorHandler;
import org.apache.jena.riot.system.ParserProfile;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.sparql.core.Quad;
import org.openldes.jena.messages.MessageVersions;
import org.openldes.jena.messages.StreamRDFMessages;

/**
 * The message bookkeeping that the parsers share, sitting between a RIOT parser and the {@link StreamRDFMessages} output:
 * whether the document is announced as a message log, whether a message is open, and the per-message scope of blank node
 * labels.
 * <p>
 * Implements the rules of <a href="https://w3c-cg.github.io/rsp/spec/messages#message-delimiters">section 2.1.2</a>:
 * <ol>
 * <li>every triple or quad is part of the current message;</li>
 * <li>a delimiter finalizes the current message, which is empty when nothing was emitted since the previous delimiter;</li>
 * <li>a delimiter before any statement gives an empty first message;</li>
 * <li>at the end of the input, an open message is finalized;</li>
 * <li>a trailing delimiter followed by the end of the input does not create an extra message.</li>
 * </ol>
 * A document that is not announced as a message log (in-line, or externally) is one message, and a delimiter in it is a
 * parse error. A {@code -messages} announcement after the first statement is an error too, because blank node scoping
 * has to be known from the start; a repeated announcement is allowed, so that logs can be concatenated.
 */
public final class MessageFramer implements StreamRDF {

	private final StreamRDFMessages output;
	private final ParserProfile profile;
	private final ErrorHandler errorHandler;
	private boolean messagesMode;
	private boolean open;
	private boolean sawStatement;
	private boolean started;
	private boolean finished;
	private long count;

	/**
	 * @param announced whether the document is announced as a message log externally (media type parameter, or the
	 *                  caller knows)
	 */
	public MessageFramer(StreamRDFMessages output, ParserProfile profile, boolean announced) {
		this.output = output;
		this.profile = profile;
		this.errorHandler = profile.getErrorHandler();
		this.messagesMode = announced;
	}

	/** Whether the document is read as an RDF Message log. */
	public boolean isMessagesMode() {
		return messagesMode;
	}

	/** The number of messages that have been finalized. */
	public long count() {
		return count;
	}

	/** Whether {@link #finish()} has been called. Not every Jena parser calls {@code start()} and {@code finish()} on its output. */
	public boolean isFinished() {
		return finished;
	}

	@Override
	public void start() {
		if (started) {
			return;
		}
		started = true;
		output.start();
	}

	@Override
	public void triple(Triple triple) {
		open = true;
		sawStatement = true;
		output.triple(triple);
	}

	@Override
	public void quad(Quad quad) {
		open = true;
		sawStatement = true;
		output.quad(quad);
	}

	@Override
	public void base(String base) {
		output.base(base);
	}

	@Override
	public void prefix(String prefix, String iri) {
		output.prefix(prefix, iri);
	}

	@Override
	public void version(String version) {
		if (MessageVersions.announcesMessages(version)) {
			if (!messagesMode && sawStatement) {
				error("The version " + version + " announces an RDF Message log after the first statement:"
						+ " the announcement must come first", -1, -1);
			}
			messagesMode = true;
		}
		output.version(version);
	}

	/** A message delimiter was read at the given position. */
	public void delimiter(long line, long col) {
		if (!messagesMode) {
			error("Message delimiter in a document that is not an RDF Message log:"
					+ " announce the log with VERSION \"" + MessageVersions.DEFAULT + "\"", line, col);
		}
		endMessage();
	}

	private void endMessage() {
		open = false;
		count++;
		output.message();
		// Blank node labels are scoped to the message: the same label in the next message is another node
		profile.getFactorRDF().reset();
	}

	@Override
	public void finish() {
		if (finished) {
			return;
		}
		finished = true;
		if (open) {
			endMessage();
		}
		output.finish();
	}

	private void error(String message, long line, long col) {
		if (errorHandler != null) {
			errorHandler.fatal(message, line, col);
		}
		throw new RiotParseException(message, line, col);
	}
}
