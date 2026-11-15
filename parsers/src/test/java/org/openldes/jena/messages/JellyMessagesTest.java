package org.openldes.jena.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFWriter;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.core.Quad;
import org.junit.jupiter.api.Test;
import org.openldes.jena.messages.jelly.JellySupport;

import eu.neverblink.jelly.convert.jena.riot.JellyLanguage;

class JellyMessagesTest {

	private static final String EX = "http://example.org/";

	private static Quad quad(String s, String o, String g) {
		return Quad.create(g == null ? Quad.defaultGraphNodeGenerated : NodeFactory.createURI(EX + g),
				NodeFactory.createURI(EX + s), NodeFactory.createURI(EX + "p"), NodeFactory.createURI(EX + o));
	}

	@Test
	void jellyIsAvailableAndRegistered() {
		assertThat(JellySupport.isAvailable()).isTrue();
		assertThat(MessageReaders.supports(JellyLanguage.JELLY)).isTrue();
	}

	@Test
	void messagesStreamRoundTrip() {
		List<RdfMessage> messages = List.of(RdfMessage.of(List.of(quad("s1", "o1", null), quad("s2", "o2", "g"))),
				RdfMessage.empty(), RdfMessage.of(List.of(quad("s3", "o3", "g"))));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RDFMessages.write(out, JellyLanguage.JELLY, messages);
		List<RdfMessage> back = RDFMessagesParser.create().source(new ByteArrayInputStream(out.toByteArray()))
				.lang(JellyLanguage.JELLY).toList();
		assertThat(back).hasSize(3);
		assertThat(back.get(0).size()).isEqualTo(2);
		assertThat(back.get(1).isEmpty()).isTrue();
		assertThat(back.get(2).graphNames()).containsExactly(NodeFactory.createURI(EX + "g"));
		// Frames are not filled up across messages: one frame per message, as the specification describes
		List<RdfMessage> again = RDFMessagesParser.create().source(new ByteArrayInputStream(out.toByteArray()))
				.lang(JellyLanguage.JELLY).bufferedMessages(1).iterator().toList();
		assertThat(again).hasSize(3);
	}

	@Test
	void everyFrameOfAFlatStreamIsAMessage() {
		// Jena's Jelly stream writer writes a FLAT stream, in frames: each frame is one message
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Context context = new Context();
		context.set(JellyLanguage.SYMBOL_FRAME_SIZE, 100);
		StreamRDF writer = StreamRDFWriter.getWriterStream(out, JellyLanguage.JELLY, context);
		writer.start();
		for (int i = 0; i < 600; i++) {
			writer.quad(quad("s" + i, "o" + i, "g" + i));
		}
		writer.finish();
		List<RdfMessage> messages = RDFMessagesParser.create().source(new ByteArrayInputStream(out.toByteArray()))
				.lang(JellyLanguage.JELLY).toList();
		assertThat(messages.size()).isGreaterThan(1);
		assertThat(messages.stream().mapToLong(RdfMessage::size).sum()).isEqualTo(600);
	}
}
