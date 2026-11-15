package org.openldes.jena.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.lang.LabelToNode;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.sparql.core.Quad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RdfMessagesParserTest {

	private static final String S1 = "<http://example.org/s1> <http://example.org/p> <http://example.org/o1> .";
	private static final String S2 = "<http://example.org/s2> <http://example.org/p> <http://example.org/o2> .";

	private static Lang lang(String name) {
		return switch (name) {
			case "Turtle" -> Lang.TURTLE;
			case "TriG" -> Lang.TRIG;
			case "N-Triples" -> Lang.NTRIPLES;
			case "N-Quads" -> Lang.NQUADS;
			default -> throw new IllegalArgumentException(name);
		};
	}

	@ParameterizedTest
	@ValueSource(strings = { "Turtle", "TriG", "N-Triples", "N-Quads" })
	void messagesAreDelimitedByMessage(String format) {
		String log = "VERSION \"1.2-messages\"\n" + S1 + "\nMESSAGE\nMESSAGE\n" + S2 + "\nMESSAGE\n";
		for (List<RdfMessage> messages : List.of(TestData.parse(log, lang(format)), TestData.parsePull(log, lang(format)))) {
			assertThat(messages).hasSize(3);
			assertThat(TestData.isomorphic(messages.get(0), S1)).isTrue();
			assertThat(messages.get(1).isEmpty()).isTrue();
			assertThat(TestData.isomorphic(messages.get(2), S2)).isTrue();
			assertThat(messages.get(2).index()).isEqualTo(2);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "Turtle", "TriG" })
	void atMessageDirectiveAndOldStyleVersion(String format) {
		String log = "@version \"1.2-messages\" .\n@prefix ex: <http://example.org/> .\nex:s1 ex:p ex:o1 .\n@message .\n"
				+ "ex:s2 ex:p ex:o2 .\n";
		List<RdfMessage> messages = TestData.parse(log, lang(format));
		assertThat(messages).hasSize(2);
		assertThat(TestData.isomorphic(messages.get(0), S1)).isTrue();
		assertThat(TestData.isomorphic(messages.get(1), S2)).isTrue();
	}

	@Test
	void versionKeywordDirectlyFollowedByAtMessage() {
		// Jena tokenizes "1.2-messages"\n@message as a literal with a language tag: the delimiter is split off
		String log = "VERSION \"1.2-messages\"\n@message .\n" + S1 + "\n";
		List<RdfMessage> messages = TestData.parse(log, Lang.TURTLE);
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0).isEmpty()).isTrue();
		assertThat(TestData.isomorphic(messages.get(1), S1)).isTrue();
	}

	@Test
	void documentWithoutAnnouncementIsOneMessage() {
		assertThat(TestData.parse(S1 + "\n" + S2 + "\n", Lang.TRIG)).hasSize(1);
		assertThat(TestData.parse("", Lang.TRIG)).isEmpty();
		assertThat(TestData.parse("VERSION \"1.2-messages\"\n", Lang.NQUADS)).isEmpty();
	}

	@Test
	void externalAnnouncementThroughContentType() {
		String log = S1 + "\nMESSAGE\n" + S2 + "\n";
		List<RdfMessage> messages = RDFMessagesParser.create().fromString(log)
				.contentType("application/n-quads; version=1.2-messages; charset=utf-8").toList();
		assertThat(messages).hasSize(2);
		assertThat(RDFMessagesParser.create().fromString(log).lang(Lang.NQUADS).messages(true).toList()).hasSize(2);
		assertThatThrownBy(() -> RDFMessagesParser.create().fromString(log).contentType("application/n-quads").toList())
				.isInstanceOf(RiotException.class).hasMessageContaining("not an RDF Message log");
	}

	@Test
	void delimiterInDocumentWithoutAnnouncementIsAnError() {
		for (Lang lang : List.of(Lang.TURTLE, Lang.TRIG, Lang.NTRIPLES, Lang.NQUADS)) {
			assertThatThrownBy(() -> TestData.parse(S1 + "\nMESSAGE\n", lang)).isInstanceOf(RiotException.class);
			assertThatThrownBy(() -> TestData.parse("VERSION \"1.2\"\n" + S1 + "\nMESSAGE\n", lang))
					.isInstanceOf(RiotException.class);
		}
	}

	@Test
	void announcementAfterTheFirstStatementIsAnError() {
		assertThatThrownBy(() -> TestData.parse(S1 + "\nVERSION \"1.2-messages\"\nMESSAGE\n", Lang.TURTLE))
				.isInstanceOf(RiotException.class).hasMessageContaining("announcement must come first");
		// A repeated announcement is allowed: logs can be concatenated
		assertThat(TestData.parse("VERSION \"1.2-messages\"\n" + S1 + "\nMESSAGE\nVERSION \"1.2-messages\"\n" + S2 + "\n",
				Lang.TURTLE)).hasSize(2);
	}

	@Test
	void delimiterInsideGraphBlockOrStatementIsAnError() {
		assertThatThrownBy(() -> TestData.parse("VERSION \"1.2-messages\"\n<http://example.org/g> {\n" + S1
				+ "\nMESSAGE\n" + S2 + "\n}\n", Lang.TRIG)).isInstanceOf(RiotException.class);
		assertThatThrownBy(() -> TestData.parse("VERSION \"1.2-messages\"\n<http://example.org/s> <http://example.org/p>"
				+ " MESSAGE .\n", Lang.TURTLE)).isInstanceOf(RiotException.class);
		assertThatThrownBy(() -> TestData.parse("VERSION \"1.2-messages\"\n" + S1 + "\n@message <http://example.org/x>\n",
				Lang.TURTLE)).isInstanceOf(RiotException.class);
	}

	@Test
	void messageMustBeOnItsOwnLineInLineBasedSyntaxes() {
		assertThatThrownBy(() -> TestData.parse("VERSION \"1.2-messages\"\n" + S1 + " MESSAGE\n" + S2 + "\n", Lang.NTRIPLES))
				.isInstanceOf(RiotException.class).hasMessageContaining("line of its own");
		assertThatThrownBy(() -> TestData.parse("VERSION \"1.2-messages\"\n" + S1 + "\nMESSAGE " + S2 + "\n", Lang.NQUADS))
				.isInstanceOf(RiotException.class).hasMessageContaining("line of its own");
		// A comment may follow the delimiter
		assertThat(TestData.parse("VERSION \"1.2-messages\"\n" + S1 + "\nMESSAGE # next\n" + S2 + "\n", Lang.NTRIPLES))
				.hasSize(2);
	}

	@Test
	void delimiterIsCaseSensitive() {
		assertThatThrownBy(() -> TestData.parse("VERSION \"1.2-messages\"\n" + S1 + "\nmessage\n", Lang.TURTLE))
				.isInstanceOf(RiotException.class);
	}

	@Test
	void blankNodesAreScopedToTheirMessage() {
		String log = "VERSION \"1.2-messages\"\n_:b0 <http://example.org/p> <http://example.org/o1> .\n"
				+ "_:b0 <http://example.org/q> _:b1 .\nMESSAGE\n_:b0 <http://example.org/p> <http://example.org/o2> .\n";
		for (Lang lang : List.of(Lang.TURTLE, Lang.TRIG, Lang.NTRIPLES, Lang.NQUADS)) {
			List<RdfMessage> messages = TestData.parse(log, lang);
			Node first = messages.get(0).stream().filter(q -> q.getPredicate().getURI().endsWith("/p")).findFirst()
					.orElseThrow().getSubject();
			Node same = messages.get(0).stream().filter(q -> q.getPredicate().getURI().endsWith("/q")).findFirst()
					.orElseThrow().getSubject();
			Node second = messages.get(1).quads().next().getSubject();
			assertThat(first.isBlank() && second.isBlank()).isTrue();
			assertThat(first).isEqualTo(same);
			assertThat(first).isNotEqualTo(second);
		}
		// Also with an incremental label policy, which would otherwise reuse the label
		List<RdfMessage> messages = RDFMessagesParser.create().fromString(log).lang(Lang.NTRIPLES)
				.labelToNode(LabelToNode.createScopeByDocumentHash()).toList();
		assertThat(messages.get(0).quads().next().getSubject()).isNotEqualTo(messages.get(1).quads().next().getSubject());
	}

	@Test
	void prefixesPersistAcrossMessagesAndCanBeRedefined() {
		String log = "VERSION \"1.2-messages\"\nPREFIX ex: <http://example.org/one/>\nex:s ex:p ex:o .\nMESSAGE\n"
				+ "ex:s ex:p ex:o .\nMESSAGE\nPREFIX ex: <http://example.org/two/>\nex:s ex:p ex:o .\n";
		List<RdfMessage> messages = TestData.parse(log, Lang.TRIG);
		assertThat(messages).hasSize(3);
		assertThat(messages.get(1).quads().next().getSubject().getURI()).isEqualTo("http://example.org/one/s");
		assertThat(messages.get(2).quads().next().getSubject().getURI()).isEqualTo("http://example.org/two/s");
		assertThat(messages.get(0).prefixes().get("ex")).isEqualTo("http://example.org/one/");
		assertThat(messages.get(2).prefixes().get("ex")).isEqualTo("http://example.org/two/");
	}

	@Test
	void namedGraphsStayInTheMessage() {
		String log = "VERSION \"1.2-messages\"\nPREFIX ex: <http://example.org/>\nex:s1 ex:p ex:o1 .\n"
				+ "ex:g { ex:s2 ex:p ex:o2 . ex:s3 ex:p ex:o3 . }\nMESSAGE\nex:s4 ex:p ex:o4 .\n";
		List<RdfMessage> messages = TestData.parse(log, Lang.TRIG);
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0).size()).isEqualTo(3);
		assertThat(messages.get(0).graphNames()).hasSize(1);
		assertThat(messages.get(0).unionGraph().size()).isEqualTo(3);
		assertThat(messages.get(1).hasNamedGraphs()).isFalse();
	}

	@Test
	void lowLevelStreamReceivesBoundariesInOrder() {
		List<String> events = new ArrayList<>();
		RDFMessages.parse(new ByteArrayInputStream(("VERSION \"1.2-messages\"\n" + S1 + "\nMESSAGE\nMESSAGE\n" + S2 + "\n")
				.getBytes(StandardCharsets.UTF_8)), Lang.NQUADS, new StreamRDFMessages() {
					@Override
					public void start() {
						events.add("start");
					}

					@Override
					public void triple(Triple triple) {
						events.add("triple");
					}

					@Override
					public void quad(Quad quad) {
						events.add("quad");
					}

					@Override
					public void base(String base) {
						events.add("base");
					}

					@Override
					public void prefix(String prefix, String iri) {
						events.add("prefix");
					}

					@Override
					public void version(String version) {
						events.add("version " + version);
					}

					@Override
					public void message() {
						events.add("message");
					}

					@Override
					public void finish() {
						events.add("finish");
					}
				});
		assertThat(events).containsExactly("start", "version 1.2-messages", "quad", "message", "message", "quad", "message",
				"finish");
	}

	@Test
	void pullParserPropagatesErrorsAndCanBeClosedEarly() {
		String log = "VERSION \"1.2-messages\"\n" + S1 + "\nMESSAGE\n" + S2 + "\nMESSAGE\nthis is not n-quads\n";
		try (MessageLog messages = RDFMessagesParser.create().fromString(log).lang(Lang.NQUADS)
				.errorHandler(ErrorHandlerFactory.errorHandlerNoLogging).iterator()) {
			assertThat(messages.next().isEmpty()).isFalse();
			assertThat(messages.next().isEmpty()).isFalse();
			assertThatThrownBy(messages::hasNext).isInstanceOf(RiotException.class);
		}

		StringBuilder big = new StringBuilder("VERSION \"1.2-messages\"\n");
		for (int i = 0; i < 10_000; i++) {
			big.append("<http://example.org/s").append(i).append("> <http://example.org/p> \"").append(i).append("\" .\nMESSAGE\n");
		}
		AtomicBoolean closed = new AtomicBoolean();
		MessageLog log2 = MessageLog.start(4, sink -> RDFMessagesParser.create().fromString(big.toString()).lang(Lang.NQUADS)
				.parse(sink), () -> closed.set(true));
		assertThat(log2.next().index()).isZero();
		assertThat(log2.next().index()).isEqualTo(1);
		log2.close();
		assertThat(closed).isTrue();
		assertThat(log2.hasNext()).isFalse();
	}

	@Test
	void serializationWithoutMessageSupportIsReadAsOneMessage() {
		String jsonld = "{\"@id\": \"http://example.org/s1\", \"http://example.org/p\": {\"@id\": \"http://example.org/o1\"}}";
		List<RdfMessage> messages = RDFMessagesParser.create().fromString(jsonld).lang(Lang.JSONLD).toList();
		assertThat(messages).hasSize(1);
		assertThat(TestData.isomorphic(messages.get(0), S1)).isTrue();
		assertThatThrownBy(() -> RDFMessagesParser.create().fromString(jsonld)
				.contentType("application/ld+json; version=1.2-messages").toList()).isInstanceOf(RiotException.class);
	}

	@Test
	void writerAndParserRoundTrip() {
		List<RdfMessage> messages = List.of(RdfMessage.of(TestData.nquads(S1)), RdfMessage.empty(),
				RdfMessage.of(TestData.nquads("<http://example.org/a> <http://example.org/b> <http://example.org/c> <http://example.org/g> .")),
				RdfMessage.empty());
		for (Lang lang : List.of(Lang.TRIG, Lang.NQUADS)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			RDFMessages.write(out, lang, messages);
			String text = out.toString(StandardCharsets.UTF_8);
			assertThat(text).startsWith("VERSION \"1.2-messages\"");
			List<RdfMessage> back = TestData.parse(text, lang);
			assertThat(back).hasSize(4);
			assertThat(back.get(1).isEmpty()).isTrue();
			assertThat(back.get(3).isEmpty()).isTrue();
			assertThat(back.get(2).graphNames()).hasSize(1);
		}
	}
}
