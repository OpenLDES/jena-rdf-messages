package org.openldes.jena.messages;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.util.IsoMatcher;

/** Helpers shared by the tests. */
final class TestData {

	private TestData() {
	}

	/** Parses N-Quads text into a dataset. */
	static DatasetGraph nquads(String text) {
		return RDFParser.create().fromString(text).lang(Lang.NQUADS).toDatasetGraph();
	}

	static boolean isomorphic(RdfMessage message, String nquads) {
		return IsoMatcher.isomorphic(message.dataset(), nquads(nquads));
	}

	/** An input stream that returns one byte per read, so that chunk boundaries fall anywhere. */
	static InputStream byteAtATime(String text) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		return new InputStream() {
			private final ByteArrayInputStream in = new ByteArrayInputStream(bytes);

			@Override
			public int read() {
				return in.read();
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				if (len == 0) {
					return 0;
				}
				int c = in.read();
				if (c < 0) {
					return -1;
				}
				b[off] = (byte) c;
				return 1;
			}

			@Override
			public int available() {
				return 0;
			}
		};
	}

	static List<RdfMessage> parse(String text, Lang lang) {
		return RDFMessages.parse(text, lang);
	}

	static List<RdfMessage> parsePull(String text, Lang lang) {
		try (MessageLog log = RDFMessagesParser.create().source(byteAtATime(text)).lang(lang).bufferedMessages(2)
				.iterator()) {
			return log.toList();
		}
	}
}
