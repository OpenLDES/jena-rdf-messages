package org.openldes.jena.messages.ldi;

import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.riot.system.PrefixMapFactory;
import org.apache.jena.sparql.core.Quad;
import org.openldes.jena.messages.RDFMessagesWriter;
import org.openldes.jena.messages.StreamRDFMessages;
import org.openldes.ldi.types.LdiOutput;

/**
 * A Linked Data Interactions output that appends every model it receives as one message to an RDF Message log: the
 * end of a pipeline becomes a log that {@link RdfMessagesAdapter}, or any RDF Messages parser, reads back as the same
 * sequence of models.
 * <p>
 * The log is written in TriG by default; the models land in the default graph. The output must be {@link #close()}d to
 * end the log.
 */
public final class RdfMessageLogOutput implements LdiOutput, AutoCloseable {

	private final StreamRDFMessages writer;
	private final PrefixMap declared = PrefixMapFactory.create();
	private boolean started;

	public RdfMessageLogOutput(OutputStream out) {
		this(out, Lang.TRIG, PrefixMapFactory.create());
	}

	/**
	 * @param prefixes the prefixes to declare at the start of the log; prefixes of the models are declared as they come
	 */
	public RdfMessageLogOutput(OutputStream out, Lang lang, PrefixMap prefixes) {
		Objects.requireNonNull(out);
		this.writer = RDFMessagesWriter.create().lang(lang).prefixes(prefixes).build(out);
		this.declared.putAll(prefixes);
	}

	@Override
	public synchronized void accept(Model model) {
		if (!started) {
			writer.start();
			started = true;
		}
		for (Map.Entry<String, String> prefix : model.getNsPrefixMap().entrySet()) {
			if (!declared.containsPrefix(prefix.getKey())) {
				declared.add(prefix.getKey(), prefix.getValue());
				writer.prefix(prefix.getKey(), prefix.getValue());
			}
		}
		model.getGraph().find().forEachRemaining(triple -> writer.quad(Quad.create(Quad.defaultGraphNodeGenerated, triple)));
		writer.message();
	}

	@Override
	public synchronized void close() {
		if (!started) {
			writer.start();
			started = true;
		}
		writer.finish();
	}
}
