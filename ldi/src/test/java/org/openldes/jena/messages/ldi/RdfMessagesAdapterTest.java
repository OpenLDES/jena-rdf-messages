package org.openldes.jena.messages.ldi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.system.PrefixMapFactory;
import org.junit.jupiter.api.Test;
import org.openldes.ldi.types.LdiAdapter.Content;

class RdfMessagesAdapterTest {

	private static final String LOG = """
			VERSION "1.2-messages"
			PREFIX ex: <http://example.org/>
			ex:s1 ex:p ex:o1 .
			ex:g { ex:s1 ex:q ex:o2 . }
			MESSAGE
			MESSAGE
			ex:s2 ex:p ex:o2 .
			""";

	@Test
	void everyMessageBecomesOneModel() {
		List<Model> models = new RdfMessagesAdapter().apply(Content.of(LOG, "application/trig")).toList();
		assertThat(models).hasSize(3);
		assertThat(models.get(0).size()).isEqualTo(2);
		assertThat(models.get(1).isEmpty()).isTrue();
		assertThat(models.get(2).getNsPrefixURI("ex")).isEqualTo("http://example.org/");
	}

	@Test
	void theVersionParameterOfTheMediaTypeAnnouncesMessages() {
		String nquads = "<http://example.org/s1> <http://example.org/p> <http://example.org/o1> .\nMESSAGE\n"
				+ "<http://example.org/s2> <http://example.org/p> <http://example.org/o2> .\n";
		List<Model> models = new RdfMessagesAdapter().apply(Content.of(nquads, "application/n-quads; version=1.2-messages")).toList();
		assertThat(models).hasSize(2);
		// Without the announcement, a plain document is one model, as with the LDI RdfAdapter
		assertThat(new RdfMessagesAdapter().apply(Content.of("<http://example.org/s1> <http://example.org/p> <http://example.org/o1> .",
				"application/n-quads")).toList()).hasSize(1);
	}

	@Test
	void theOutputWritesALogThatTheAdapterReadsBack() {
		List<Model> models = new RdfMessagesAdapter().apply(Content.of(LOG, "application/trig")).toList();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (RdfMessageLogOutput output = new RdfMessageLogOutput(out, Lang.TRIG, PrefixMapFactory.create())) {
			models.forEach(output);
		}
		String log = out.toString(StandardCharsets.UTF_8);
		assertThat(log).startsWith("VERSION \"1.2-messages\"");
		assertThat(log).contains("PREFIX ex: <http://example.org/>");
		List<Model> back = new RdfMessagesAdapter().apply(Content.of(log, "application/trig")).toList();
		assertThat(back).hasSize(3);
		assertThat(back.get(0).isIsomorphicWith(models.get(0))).isTrue();
		assertThat(back.get(1).isEmpty()).isTrue();
	}
}
