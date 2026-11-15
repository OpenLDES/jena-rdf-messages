package org.openldes.jena.messages.jelly;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ParserProfile;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.util.Context;
import org.openldes.jena.messages.MessageReader;
import org.openldes.jena.messages.StreamRDFMessages;

import eu.neverblink.jelly.convert.jena.JenaConverterFactory;
import eu.neverblink.jelly.convert.jena.riot.JellyLanguage;
import eu.neverblink.jelly.core.JellyOptions;
import eu.neverblink.jelly.core.ProtoDecoder;
import eu.neverblink.jelly.core.RdfEncoder;
import eu.neverblink.jelly.core.RdfHandler;
import eu.neverblink.jelly.core.proto.v1.LogicalStreamType;
import eu.neverblink.jelly.core.proto.v1.PhysicalStreamType;
import eu.neverblink.jelly.core.proto.v1.RdfStreamFrame;
import eu.neverblink.jelly.core.proto.v1.RdfStreamOptions;
import eu.neverblink.jelly.core.proto.v1.RdfStreamRow;
import eu.neverblink.jelly.core.proto.v1.RdfStreamType;
import eu.neverblink.jelly.core.utils.IoUtils;

/**
 * RDF Message logs in <a href="https://w3id.org/jelly/">Jelly-RDF</a>, with the same types as the text serializations.
 * <p>
 * Reading: a Jelly stream of type {@code MESSAGES} (Jelly-RDF 1.2) carries its message boundaries, which the decoder
 * reports. In any other stream (the {@code FLAT} streams of Jelly-RDF 1.0 to 1.2), every frame is one message, as the
 * <a href="https://w3c-cg.github.io/rsp/spec/messages#other-formats">RDF Messages specification</a> says. Delimited and
 * non-delimited streams are both accepted.
 * <p>
 * Writing: a delimited stream of type {@code MESSAGES} with quads, one frame per message by default.
 */
final class JellyMessages {

	private JellyMessages() {
	}

	static Lang lang() {
		return JellyLanguage.JELLY;
	}

	/** Reads a Jelly stream as a message log. */
	static final class Reader implements MessageReader {

		@Override
		public void read(InputStream input, String baseURI, ParserProfile profile, boolean announced,
				StreamRDFMessages output, Context context) {
			output.start();
			Handler handler = new Handler(output);
			ProtoDecoder<Node, ?> decoder = JenaConverterFactory.getInstance().anyStatementDecoder(handler,
					JellyOptions.DEFAULT_SUPPORTED_OPTIONS);
			try {
				IoUtils.AutodetectDelimitingResponse detected = IoUtils.autodetectDelimiting(input);
				InputStream in = detected.newInput();
				if (detected.isDelimited()) {
					IoUtils.readStream(in, RdfStreamFrame.getFactory(), frame -> handler.frame(decoder, frame));
				} else {
					handler.frame(decoder, RdfStreamFrame.parseFrom(in));
				}
			} catch (IOException e) {
				throw new RiotException("Failed to read the Jelly stream", e);
			}
			output.finish();
		}
	}

	private static final class Handler implements RdfHandler.AnyStatementHandler<Node> {
		private final StreamRDFMessages output;
		private boolean messagesStream;
		private boolean typeKnown;

		Handler(StreamRDFMessages output) {
			this.output = output;
		}

		void frame(ProtoDecoder<Node, ?> decoder, RdfStreamFrame frame) {
			if (!typeKnown) {
				detectStreamType(frame);
			}
			decoder.ingestFrame(frame);
			if (!messagesStream) {
				// Without explicit message boundaries, a frame is a message
				output.message();
			}
		}

		private void detectStreamType(RdfStreamFrame frame) {
			for (RdfStreamRow row : frame.getRows()) {
				if (row.hasOptions()) {
					RdfStreamOptions options = row.getOptions();
					messagesStream = options.getStreamType() == RdfStreamType.MESSAGES;
					typeKnown = true;
					return;
				}
			}
		}

		@Override
		public void handleTriple(Node subject, Node predicate, Node object) {
			output.triple(Triple.create(subject, predicate, object));
		}

		@Override
		public void handleQuad(Node subject, Node predicate, Node object, Node graph) {
			output.quad(Quad.create(graph == null ? Quad.defaultGraphNodeGenerated : graph, subject, predicate, object));
		}

		@Override
		public void handleNamespace(String prefix, Node iri) {
			output.prefix(prefix, iri.getURI());
		}

		@Override
		public void handleMessageEnd() {
			output.message();
		}
	}

	/** Writes a message log as a delimited Jelly stream of type MESSAGES. */
	static final class Writer implements StreamRDFMessages {
		private final OutputStream out;
		private final boolean framePerMessage;
		private RdfEncoder<Node> encoder;

		Writer(OutputStream out, boolean framePerMessage) {
			this.out = out;
			this.framePerMessage = framePerMessage;
		}

		@Override
		public void start() {
			RdfStreamOptions options = JellyOptions.SMALL_ALL_FEATURES.clone().setPhysicalType(PhysicalStreamType.QUADS)
					.setStreamType(RdfStreamType.MESSAGES).setLogicalType(LogicalStreamType.DATASETS);
			encoder = JenaConverterFactory.getInstance().encoder(RdfEncoder.Params.of(options, 256, frame -> {
				try {
					frame.writeDelimitedTo(out);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}));
		}

		@Override
		public void triple(Triple triple) {
			encoder.handleQuad(triple.getSubject(), triple.getPredicate(), triple.getObject(), Quad.defaultGraphNodeGenerated);
		}

		@Override
		public void quad(Quad quad) {
			encoder.handleQuad(quad.getSubject(), quad.getPredicate(), quad.getObject(), quad.getGraph());
		}

		@Override
		public void base(String base) {
			// Jelly has no base declaration
		}

		@Override
		public void prefix(String prefix, String iri) {
			encoder.handleNamespace(prefix, NodeFactory.createURI(iri));
		}

		@Override
		public void version(String version) {
			// Jelly announces its own version
		}

		@Override
		public void message() {
			encoder.handleMessageEnd();
			if (framePerMessage) {
				encoder.flush();
			}
		}

		@Override
		public void finish() {
			encoder.flush();
			try {
				out.flush();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}
}
