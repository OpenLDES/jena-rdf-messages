package org.openldes.jena.messages.lang;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFParserBuilder;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.RiotParseException;
import org.apache.jena.riot.system.ParserProfile;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.util.Context;
import org.openldes.jena.messages.MessageReader;
import org.openldes.jena.messages.StreamRDFMessages;

/**
 * Reads an RDF Message log in RDF/XML, see <a href="https://w3c-cg.github.io/rsp/spec/messages#rdf-xml">section 2.5 of
 * the specification</a>: an {@code <rdfm:MessageLog>} root element with zero or more {@code <rdf:RDF>} children, each one
 * message. Namespaces, {@code xml:base}, {@code xml:lang} and {@code rdf:version} declared on the root apply to every
 * message, and a message can override them.
 * <p>
 * The log is read with a streaming XML parser (StAX), one {@code <rdf:RDF>} element at a time: the element is copied
 * into a self-contained RDF/XML document, with the inherited declarations added to it, which is parsed by Jena's RDF/XML
 * parser. Each message is parsed by a fresh parser, which is what scopes {@code rdf:nodeID} labels to the message.
 */
public final class ReaderRDFXMLMessages implements MessageReader {

	/** The namespace of the message log vocabulary. */
	public static final String RDFM_NS = "https://w3c-cg.github.io/rsp/spec/messages#";
	public static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
	private static final String MESSAGE_LOG = "MessageLog";
	private static final String RDF = "RDF";

	private static final XMLInputFactory INPUT_FACTORY = createInputFactory();
	private static final XMLOutputFactory OUTPUT_FACTORY = XMLOutputFactory.newFactory();

	private static XMLInputFactory createInputFactory() {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		// No DTDs, no external entities: a message log is data, not a document to process
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		factory.setProperty(XMLInputFactory.IS_COALESCING, true);
		return factory;
	}

	@Override
	public void read(InputStream input, String baseURI, ParserProfile profile, boolean announced,
			StreamRDFMessages output, Context context) {
		try {
			read(INPUT_FACTORY.createXMLStreamReader(input), baseURI, profile, output, context);
		} catch (XMLStreamException e) {
			throw new RiotException("Failed to create the XML stream reader", e);
		}
	}

	@Override
	public void read(Reader reader, String baseURI, ParserProfile profile, boolean announced, StreamRDFMessages output,
			Context context) {
		try {
			read(INPUT_FACTORY.createXMLStreamReader(reader), baseURI, profile, output, context);
		} catch (XMLStreamException e) {
			throw new RiotException("Failed to create the XML stream reader", e);
		}
	}

	private void read(XMLStreamReader xml, String baseURI, ParserProfile profile, StreamRDFMessages output,
			Context context) {
		output.start();
		try {
			int event = nextTag(xml);
			if (event != XMLStreamConstants.START_ELEMENT || !is(xml, RDFM_NS, MESSAGE_LOG)) {
				throw error(xml, "An RDF/XML message log starts with <rdfm:MessageLog>, found "
						+ (event == XMLStreamConstants.START_ELEMENT ? "<" + xml.getName() + ">" : "no element"));
			}
			Inherited inherited = Inherited.of(xml);
			while (xml.hasNext()) {
				event = xml.next();
				switch (event) {
					case XMLStreamConstants.START_ELEMENT -> {
						if (!is(xml, RDF_NS, RDF)) {
							throw error(xml, "Only <rdf:RDF> elements are allowed in <rdfm:MessageLog>, found <"
									+ xml.getName() + ">");
						}
						byte[] document = copyMessage(xml, inherited);
						parseMessage(document, baseURI, profile, output, context);
						output.message();
					}
					case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.ENTITY_REFERENCE -> {
						if (!xml.isWhiteSpace()) {
							throw error(xml, "Text is not allowed between the messages of <rdfm:MessageLog>");
						}
					}
					case XMLStreamConstants.END_ELEMENT, XMLStreamConstants.END_DOCUMENT -> {
						output.finish();
						return;
					}
					default -> {
						// comments, processing instructions and whitespace are ignored
					}
				}
			}
			output.finish();
		} catch (XMLStreamException e) {
			throw new RiotParseException(e.getMessage(), line(xml), column(xml));
		} finally {
			try {
				xml.close();
			} catch (XMLStreamException e) {
				// nothing to release
			}
		}
	}

	private static int nextTag(XMLStreamReader xml) throws XMLStreamException {
		while (xml.hasNext()) {
			int event = xml.next();
			if (event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.END_ELEMENT) {
				return event;
			}
			if (event == XMLStreamConstants.END_DOCUMENT) {
				return event;
			}
		}
		return XMLStreamConstants.END_DOCUMENT;
	}

	private static boolean is(XMLStreamReader xml, String namespace, String localName) {
		return namespace.equals(xml.getNamespaceURI()) && localName.equals(xml.getLocalName());
	}

	/** The declarations on the root element that every message inherits. */
	private record Inherited(Map<String, String> namespaces, String base, String lang, String version) {

		static Inherited of(XMLStreamReader xml) {
			Map<String, String> namespaces = new LinkedHashMap<>();
			for (int i = 0; i < xml.getNamespaceCount(); i++) {
				namespaces.put(xml.getNamespacePrefix(i) == null ? "" : xml.getNamespacePrefix(i), xml.getNamespaceURI(i));
			}
			return new Inherited(namespaces, xml.getAttributeValue(XMLConstants.XML_NS_URI, "base"),
					xml.getAttributeValue(XMLConstants.XML_NS_URI, "lang"), xml.getAttributeValue(RDF_NS, "version"));
		}
	}

	/** Copies the {@code <rdf:RDF>} element at the cursor into a document of its own. */
	private static byte[] copyMessage(XMLStreamReader xml, Inherited inherited) throws XMLStreamException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		XMLStreamWriter out = OUTPUT_FACTORY.createXMLStreamWriter(bytes, StandardCharsets.UTF_8.name());
		out.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
		int depth = 0;
		boolean root = true;
		do {
			switch (xml.getEventType()) {
				case XMLStreamConstants.START_ELEMENT -> {
					writeStartElement(xml, out, root ? inherited : null);
					root = false;
					depth++;
				}
				case XMLStreamConstants.END_ELEMENT -> {
					out.writeEndElement();
					depth--;
				}
				case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> out.writeCharacters(xml.getText());
				case XMLStreamConstants.CDATA -> out.writeCData(xml.getText());
				case XMLStreamConstants.COMMENT -> out.writeComment(xml.getText());
				case XMLStreamConstants.PROCESSING_INSTRUCTION -> out.writeProcessingInstruction(xml.getPITarget(),
						xml.getPIData());
				case XMLStreamConstants.ENTITY_REFERENCE -> out.writeEntityRef(xml.getLocalName());
				default -> {
					// nothing else can occur inside an element
				}
			}
			if (depth > 0) {
				xml.next();
			}
		} while (depth > 0);
		out.writeEndDocument();
		out.close();
		return bytes.toByteArray();
	}

	private static void writeStartElement(XMLStreamReader xml, XMLStreamWriter out, Inherited inherited)
			throws XMLStreamException {
		String prefix = xml.getPrefix() == null ? "" : xml.getPrefix();
		String namespace = xml.getNamespaceURI();
		if (namespace == null || namespace.isEmpty()) {
			out.writeStartElement(xml.getLocalName());
		} else {
			out.writeStartElement(prefix, xml.getLocalName(), namespace);
		}
		Map<String, String> declared = new LinkedHashMap<>();
		for (int i = 0; i < xml.getNamespaceCount(); i++) {
			String p = xml.getNamespacePrefix(i) == null ? "" : xml.getNamespacePrefix(i);
			declared.put(p, xml.getNamespaceURI(i));
		}
		if (inherited != null) {
			// The namespaces of the log element apply to the message, unless the message redeclares them
			inherited.namespaces().forEach(declared::putIfAbsent);
		}
		for (Map.Entry<String, String> entry : declared.entrySet()) {
			if (entry.getKey().isEmpty()) {
				out.writeDefaultNamespace(entry.getValue());
			} else {
				out.writeNamespace(entry.getKey(), entry.getValue());
			}
		}
		boolean hasBase = false;
		boolean hasLang = false;
		boolean hasVersion = false;
		for (int i = 0; i < xml.getAttributeCount(); i++) {
			String attributeNamespace = xml.getAttributeNamespace(i);
			String local = xml.getAttributeLocalName(i);
			String value = xml.getAttributeValue(i);
			if (attributeNamespace == null || attributeNamespace.isEmpty()) {
				out.writeAttribute(local, value);
			} else {
				String attributePrefix = xml.getAttributePrefix(i);
				out.writeAttribute(attributePrefix == null ? "" : attributePrefix, attributeNamespace, local, value);
				if (XMLConstants.XML_NS_URI.equals(attributeNamespace)) {
					hasBase |= "base".equals(local);
					hasLang |= "lang".equals(local);
				} else if (RDF_NS.equals(attributeNamespace)) {
					hasVersion |= "version".equals(local);
				}
			}
		}
		if (inherited != null) {
			if (!hasBase && inherited.base() != null) {
				out.writeAttribute(XMLConstants.XML_NS_PREFIX, XMLConstants.XML_NS_URI, "base", inherited.base());
			}
			if (!hasLang && inherited.lang() != null) {
				out.writeAttribute(XMLConstants.XML_NS_PREFIX, XMLConstants.XML_NS_URI, "lang", inherited.lang());
			}
			if (!hasVersion && inherited.version() != null) {
				String rdfPrefix = declared.entrySet().stream().filter(e -> RDF_NS.equals(e.getValue())).map(Map.Entry::getKey)
						.findFirst().orElse("rdf");
				out.writeAttribute(rdfPrefix, RDF_NS, "version", inherited.version());
			}
		}
	}

	private static void parseMessage(byte[] document, String baseURI, ParserProfile profile, StreamRDFMessages output,
			Context context) {
		RDFParserBuilder parser = RDFParser.create().source(new ByteArrayInputStream(document)).lang(Lang.RDFXML)
				.errorHandler(profile.getErrorHandler());
		if (baseURI != null) {
			parser.base(baseURI);
		}
		if (context != null) {
			parser.context(context);
		}
		// Each message is parsed by a fresh parser: rdf:nodeID labels are scoped to the message
		parser.parse(new StreamRDF() {
			@Override
			public void start() {
				// the log has started
			}

			@Override
			public void triple(Triple triple) {
				output.triple(triple);
			}

			@Override
			public void quad(Quad quad) {
				output.quad(quad);
			}

			@Override
			public void base(String base) {
				output.base(base);
			}

			@Override
			public void prefix(String prefix, String iri) {
				output.prefix(prefix, iri);
			}

			@Override
			public void version(String version) {
				output.version(version);
			}

			@Override
			public void finish() {
				// the message is finalized by the log reader
			}
		});
	}

	private static RiotParseException error(XMLStreamReader xml, String message) {
		return new RiotParseException(message, line(xml), column(xml));
	}

	private static long line(XMLStreamReader xml) {
		return xml.getLocation() == null ? -1 : xml.getLocation().getLineNumber();
	}

	private static long column(XMLStreamReader xml) {
		return xml.getLocation() == null ? -1 : xml.getLocation().getColumnNumber();
	}
}
