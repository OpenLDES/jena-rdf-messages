package org.openldes.jena.messages.lang;

import java.io.InputStream;
import java.io.Reader;

import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RIOT;
import org.apache.jena.riot.lang.LangRIOT;
import org.apache.jena.riot.system.ParserProfile;
import org.apache.jena.riot.tokens.Tokenizer;
import org.apache.jena.riot.tokens.TokenizerText;
import org.apache.jena.sparql.util.Context;
import org.openldes.jena.messages.MessageReader;
import org.openldes.jena.messages.StreamRDFMessages;

/** The {@link MessageReader}s for the serializations that Jena's text tokenizer handles: Turtle, TriG, N-Triples and N-Quads. */
public final class RiotMessageReaders {

	private RiotMessageReaders() {
	}

	@FunctionalInterface
	private interface ParserFactory {
		LangRIOT create(Tokenizer tokenizer, ParserProfile profile, MessageFramer framer, Context context);
	}

	public static MessageReader turtle() {
		return new TokenReader(Lang.TURTLE, LangTurtleMessages::new);
	}

	public static MessageReader trig() {
		return new TokenReader(Lang.TRIG, LangTriGMessages::new);
	}

	public static MessageReader ntriples() {
		return new TokenReader(Lang.NTRIPLES, LangNTriplesMessages::new);
	}

	public static MessageReader nquads() {
		return new TokenReader(Lang.NQUADS, LangNQuadsMessages::new);
	}

	private record TokenReader(Lang lang, ParserFactory factory) implements MessageReader {

		@Override
		public void read(InputStream input, String baseURI, ParserProfile profile, boolean announced,
				StreamRDFMessages output, Context context) {
			Tokenizer tokenizer = TokenizerText.create().source(input).errorHandler(profile.getErrorHandler()).build();
			parse(tokenizer, profile, announced, output, context);
		}

		@Override
		public void read(Reader reader, String baseURI, ParserProfile profile, boolean announced,
				StreamRDFMessages output, Context context) {
			Tokenizer tokenizer = TokenizerText.create().source(reader).errorHandler(profile.getErrorHandler()).build();
			parse(tokenizer, profile, announced, output, context);
		}

		private void parse(Tokenizer tokenizer, ParserProfile profile, boolean announced, StreamRDFMessages output,
				Context context) {
			MessageFramer framer = new MessageFramer(output, profile, announced);
			LangRIOT parser = factory.create(tokenizer, profile, framer, context == null ? RIOT.getContext() : context);
			parser.parse();
		}
	}
}
