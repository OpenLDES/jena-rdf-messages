package org.openldes.jena.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.util.IsoMatcher;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openldes.jena.messages.jelly.JellySupport;

class RdfMessagesSourceTest {

	@TempDir
	Path directory;

	static Stream<Arguments> sources() {
		return Stream.of(Lang.NTRIPLES, Lang.NQUADS, Lang.TURTLE, Lang.TRIG, JellySupport.lang())
				.flatMap(lang -> Stream.of(false, true).map(gzip -> Arguments.of(lang, gzip)));
	}

	@ParameterizedTest
	@MethodSource("sources")
	void localSourcesInferLanguageAndDecompressOnce(Lang lang, boolean gzip) throws IOException {
		List<RdfMessage> expected = List.of(message("first", "café"), RdfMessage.empty(), message("last", "last"));
		Path file = directory.resolve("messages." + lang.getFileExtensions().getFirst() + (gzip ? ".gz" : ""));
		try (OutputStream out = gzip ? new GZIPOutputStream(Files.newOutputStream(file))
				: Files.newOutputStream(file)) {
			RDFMessages.write(out, lang, expected);
		}

		// Exercise both opening paths (push/pull), all location representations, and the convenience reader.
		assertMessages(RDFMessagesParser.create().source(file).toList(), expected);
		assertMessages(RDFMessagesParser.create().source(file.toString()).toList(), expected);
		assertMessages(RDFMessagesParser.create().source(file.toUri().toString()).toList(), expected);
		for (String location : List.of(file.toString(), file.toUri().toString())) {
			try (MessageLog log = RDFMessagesParser.create().source(location).bufferedMessages(1).iterator()) {
				assertMessages(log.toList(), expected);
			}
		}
		assertMessages(RDFMessagesParser.readAll(file), expected);
	}

	private static RdfMessage message(String subject, String value) {
		return RdfMessage.of(List.of(Quad.create(Quad.defaultGraphNodeGenerated,
				NodeFactory.createURI("http://example.org/" + subject), NodeFactory.createURI("http://example.org/p"),
				NodeFactory.createLiteralString(value))));
	}

	private static void assertMessages(List<RdfMessage> actual, List<RdfMessage> expected) {
		assertThat(actual).hasSize(expected.size());
		for (int i = 0; i < expected.size(); i++) {
			assertThat(actual.get(i).index()).isEqualTo(i);
			assertThat(IsoMatcher.isomorphic(actual.get(i).dataset(), expected.get(i).dataset())).isTrue();
		}
	}
}
