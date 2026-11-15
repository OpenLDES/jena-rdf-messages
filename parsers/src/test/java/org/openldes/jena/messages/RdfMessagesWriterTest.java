package org.openldes.jena.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.system.PrefixMapFactory;
import org.apache.jena.riot.writer.DirectiveStyle;
import org.apache.jena.sparql.core.Quad;
import org.junit.jupiter.api.Test;

class RdfMessagesWriterTest {

	private static final String EX = "http://example.org/";

	private static Quad quad(String s, String p, String o, String g) {
		return Quad.create(g == null ? Quad.defaultGraphNodeGenerated : NodeFactory.createURI(EX + g),
				NodeFactory.createURI(EX + s), NodeFactory.createURI(EX + p), NodeFactory.createURI(EX + o));
	}

	@Test
	void trigWithPrefixesAndKeywordDelimiters() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamRDFMessages writer = RDFMessagesWriter.create().lang(Lang.TRIG)
				.prefixes(PrefixMapFactory.create(java.util.Map.of("ex", EX))).build(out);
		writer.start();
		writer.quad(quad("s1", "p", "o1", null));
		writer.quad(quad("s2", "p", "o2", "g"));
		writer.message();
		writer.message();
		writer.quad(quad("s3", "p", "o3", "g"));
		writer.message();
		writer.finish();
		String text = out.toString(StandardCharsets.UTF_8);
		assertThat(text).startsWith("VERSION \"1.2-messages\"\nPREFIX ex: <http://example.org/>");
		assertThat(text).contains("MESSAGE\nMESSAGE\n");
		assertThat(text.split("MESSAGE")).hasSize(4);
		List<RdfMessage> back = RDFMessages.parse(text, Lang.TRIG);
		assertThat(back).hasSize(3);
		assertThat(back.get(0).size()).isEqualTo(2);
		assertThat(back.get(1).isEmpty()).isTrue();
		assertThat(back.get(2).graphNames()).containsExactly(NodeFactory.createURI(EX + "g"));
	}

	@Test
	void defaultGraphQuadsOfADatasetAreWrittenAsTriples() {
		// A dataset returns its default graph quads with Quad.defaultGraphIRI, not Quad.defaultGraphNodeGenerated
		String text = RDFMessagesWriter.create().lang(Lang.TRIG).writeString(List.of(RdfMessage.of(List.of(quad("s1", "p", "o1", null)))));
		assertThat(text).doesNotContain("{").doesNotContain("urn:x-arq");
		assertThat(RDFMessages.parse(text, Lang.TRIG).get(0).hasNamedGraphs()).isFalse();
	}

	@Test
	void turtleWithAtStyleDirectives() {
		String text = RDFMessagesWriter.create().lang(Lang.TURTLE).directiveStyle(DirectiveStyle.AT)
				.prefixes(PrefixMapFactory.create(java.util.Map.of("ex", EX)))
				.writeString(List.of(RdfMessage.of(List.of(quad("s1", "p", "o1", null))),
						RdfMessage.of(List.of(quad("s2", "p", "o2", null)))));
		assertThat(text).startsWith("@version \"1.2-messages\" .\n@prefix ex: <http://example.org/> .");
		assertThat(text).contains("@message .");
		assertThat(RDFMessages.parse(text, Lang.TURTLE)).hasSize(2);
	}

	@Test
	void versionCanBeOmittedForExternalAnnouncement() {
		String text = RDFMessagesWriter.create().lang(Lang.NQUADS).writeVersion(false)
				.writeString(List.of(RdfMessage.of(List.of(quad("s1", "p", "o1", "g")))));
		assertThat(text).doesNotContain("VERSION");
		assertThat(text).isEqualTo("<http://example.org/s1> <http://example.org/p> <http://example.org/o1> <http://example.org/g> .\nMESSAGE\n");
		assertThat(RDFMessagesParser.create().fromString(text).contentType("application/n-quads; version=1.2-messages")
				.toList()).hasSize(1);
	}

	@Test
	void versionLabelIsValidated() {
		assertThatThrownBy(() -> RDFMessagesWriter.create().version("1.2")).isInstanceOf(IllegalArgumentException.class);
		String text = RDFMessagesWriter.create().lang(Lang.NTRIPLES).version(MessageVersions.RDF11_MESSAGES)
				.writeString(List.of(RdfMessage.of(List.of(quad("s1", "p", "o1", null)))));
		assertThat(text).startsWith("VERSION \"1.1-messages\"");
	}

	@Test
	void versionDeclarationsOfTheSourceAreNotRepeated() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamRDFMessages writer = RDFMessagesWriter.create().lang(Lang.NTRIPLES).build(out);
		writer.start();
		writer.version("1.2");
		writer.triple(quad("s1", "p", "o1", null).asTriple());
		writer.message();
		writer.finish();
		assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(
				"VERSION \"1.2-messages\"\n<http://example.org/s1> <http://example.org/p> <http://example.org/o1> .\nMESSAGE\n");
	}
}
