package org.openldes.jena.messages.lang;

import org.apache.jena.riot.tokens.Token;
import org.apache.jena.riot.tokens.TokenType;
import org.apache.jena.riot.tokens.Tokenizer;

/**
 * A {@link Tokenizer} that makes the message delimiters of Turtle and TriG visible to a parser that extends Jena's
 * {@code LangTurtleBase} without changing Jena.
 * <p>
 * Jena's tokenizer gives {@code @message} as a {@code DIRECTIVE} token, which the (final) main loop of
 * {@code LangTurtleBase} handles itself and rejects as an unknown directive. This tokenizer turns it into a
 * {@code KEYWORD} token with the image {@value #AT_MESSAGE}, which reaches the extension point
 * {@code oneTopLevelElement()} like the keyword {@code MESSAGE} does. Inside a graph block or a statement, a keyword is a
 * syntax error, as a delimiter must be.
 * <p>
 * A version string that is directly followed by {@code @message} is tokenized by Jena as one literal with a language
 * tag; it is split here into the string and the delimiter, as Jena does itself for {@code @prefix} and {@code @base}.
 */
public final class MessageTokenizer implements Tokenizer {

	/** The image of the keyword token that stands for {@code @message}. */
	public static final String AT_MESSAGE = "@message";

	/** The delimiter keyword. It is case-sensitive, unlike {@code PREFIX}, {@code BASE} and {@code VERSION}. */
	public static final String MESSAGE = "MESSAGE";

	private static final String MESSAGE_DIRECTIVE = "message";

	private final Tokenizer inner;
	private Token pending;
	private Token pendingAfter;
	private Token previous;

	public MessageTokenizer(Tokenizer inner) {
		this.inner = inner;
	}

	/** Whether a token is a message delimiter: the keyword {@code MESSAGE} or the rewritten {@code @message}. */
	public static boolean isDelimiter(Token token) {
		return token != null && token.hasType(TokenType.KEYWORD)
				&& (MESSAGE.equals(token.getImage()) || AT_MESSAGE.equals(token.getImage()));
	}

	/** Whether a delimiter token came from {@code @message}, which must be followed by a dot. */
	public static boolean isAtMessage(Token token) {
		return token != null && token.hasType(TokenType.KEYWORD) && AT_MESSAGE.equals(token.getImage());
	}

	@Override
	public boolean hasNext() {
		return pending != null || pendingAfter != null || inner.hasNext();
	}

	@Override
	public Token next() {
		Token token = fetch();
		previous = token;
		return token;
	}

	@Override
	public Token peek() {
		if (pending == null) {
			pending = fetch();
		}
		return pending;
	}

	private Token fetch() {
		if (pending != null) {
			Token token = pending;
			pending = null;
			return token;
		}
		if (pendingAfter != null) {
			Token token = pendingAfter;
			pendingAfter = null;
			return token;
		}
		if (!inner.hasNext()) {
			return null;
		}
		return convert(inner.next());
	}

	private Token convert(Token token) {
		if (token.hasType(TokenType.DIRECTIVE) && MESSAGE_DIRECTIVE.equals(token.getImage())) {
			return token.setType(TokenType.KEYWORD).setImage(AT_MESSAGE);
		}
		if (token.hasType(TokenType.LITERAL_LANG) && MESSAGE_DIRECTIVE.equals(token.getImage2())
				&& afterVersionKeyword()) {
			// VERSION "1.2-messages"\n@message .  tokenized as a lang-tagged literal: split in string and delimiter
			Token delimiter = new Token(token.getLine(), token.getColumn());
			delimiter.setType(TokenType.KEYWORD);
			delimiter.setImage(AT_MESSAGE);
			pendingAfter = delimiter;
			return token.getSubToken1();
		}
		return token;
	}

	private boolean afterVersionKeyword() {
		if (previous == null) {
			return false;
		}
		if (previous.hasType(TokenType.KEYWORD)) {
			return "VERSION".equalsIgnoreCase(previous.getImage());
		}
		return previous.hasType(TokenType.DIRECTIVE) && "version".equals(previous.getImage());
	}

	@Override
	public boolean eof() {
		return !hasNext();
	}

	@Override
	public long getLine() {
		return inner.getLine();
	}

	@Override
	public long getColumn() {
		return inner.getColumn();
	}

	@Override
	public void close() {
		inner.close();
	}
}
