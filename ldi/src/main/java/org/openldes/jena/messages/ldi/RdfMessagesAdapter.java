package org.openldes.jena.messages.ldi;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.openldes.jena.messages.MessageLog;
import org.openldes.jena.messages.RDFMessagesParser;
import org.openldes.jena.messages.RdfMessage;
import org.openldes.ldi.types.LdiAdapter;

/**
 * A <a href="https://github.com/OpenLDES/Linked-Data-Interactions">Linked Data Interactions</a> adapter that turns an
 * RDF Message log into one linked data model per message: the LDI counterpart of the plain {@code RdfAdapter}, which
 * turns a document into one model.
 * <p>
 * LDI pipelines carry {@link Model}s, which have no named graphs: a message is handed over as the union of all its
 * graphs. The serialization is taken from the media type of the content, including a {@code version} parameter that
 * announces messages; a document without message delimiters is one model, as it is for the {@code RdfAdapter}.
 */
public final class RdfMessagesAdapter implements LdiAdapter {

	private final Lang defaultLang;

	public RdfMessagesAdapter() {
		this(Lang.TRIG);
	}

	/** @param defaultLang the serialization for content whose media type is unknown */
	public RdfMessagesAdapter(Lang defaultLang) {
		this.defaultLang = Objects.requireNonNull(defaultLang);
	}

	@Override
	public Stream<Model> apply(Content content) {
		RDFMessagesParser parser = RDFMessagesParser.create()
				.source(new ByteArrayInputStream(content.content().getBytes(StandardCharsets.UTF_8)))
				.lang(defaultLang);
		String mimeType = content.mimeType();
		if (mimeType != null && !mimeType.isBlank()) {
			Lang lang = RDFLanguages.nameToLang(mimeType);
			if (lang != null) {
				parser.lang(lang);
			}
			parser.contentType(mimeType);
		}
		MessageLog log = parser.iterator();
		return log.stream().map(RdfMessagesAdapter::toModel);
	}

	/** The union of all graphs of a message, as a model. */
	public static Model toModel(RdfMessage message) {
		Model model = ModelFactory.createModelForGraph(message.unionGraph());
		message.prefixes().getMapping().forEach(model::setNsPrefix);
		return model;
	}
}
