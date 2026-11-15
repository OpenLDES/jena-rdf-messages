package org.openldes.jena.messages.lang;

import org.apache.jena.riot.tokens.Token;
import org.apache.jena.riot.tokens.TokenType;

/**
 * The delimiter logic shared by the N-Triples and N-Quads message log parsers. The grammar of these syntaxes is
 * line-based, so the {@code MESSAGE} directive must be alone on its line (a comment may follow it).
 */
final class LangNTupleMessages {

	private LangNTupleMessages() {
	}

	static boolean possibleDelimiter(LangNTriplesMessages parser, MessageFramer framer) {
		Token token = parser.peekTokenForDelimiter();
		if (!isMessage(token)) {
			return false;
		}
		long previousLine = parser.currentLine();
		parser.nextTokenForDelimiter();
		checkOwnLine(token, previousLine, parser.peekTokenForDelimiter(), parser::delimiterError);
		framer.delimiter(token.getLine(), token.getColumn());
		return true;
	}

	static boolean possibleDelimiter(LangNQuadsMessages parser, MessageFramer framer) {
		Token token = parser.peekTokenForDelimiter();
		if (!isMessage(token)) {
			return false;
		}
		long previousLine = parser.currentLine();
		parser.nextTokenForDelimiter();
		checkOwnLine(token, previousLine, parser.peekTokenForDelimiter(), parser::delimiterError);
		framer.delimiter(token.getLine(), token.getColumn());
		return true;
	}

	private static boolean isMessage(Token token) {
		return token != null && !token.isEOF() && token.hasType(TokenType.KEYWORD)
				&& MessageTokenizer.MESSAGE.equals(token.getImage());
	}

	private interface ErrorReporter {
		void error(Token token, String message);
	}

	/**
	 * @param token        the MESSAGE token
	 * @param previousLine the line of the token before MESSAGE (the line of MESSAGE itself before it was consumed)
	 * @param next         the token after MESSAGE
	 */
	private static void checkOwnLine(Token token, long previousLine, Token next, ErrorReporter reporter) {
		if (previousLine == token.getLine()) {
			reporter.error(token, "MESSAGE must be on a line of its own");
		}
		if (next != null && !next.isEOF() && next.getLine() == token.getLine()) {
			reporter.error(next, "MESSAGE must be on a line of its own: found %s on the same line".formatted(next.text()));
		}
	}
}
