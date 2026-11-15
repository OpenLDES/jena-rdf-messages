package org.openldes.jena.messages;

import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.jena.riot.Lang;
import org.apache.jena.sys.JenaSystem;
import org.openldes.jena.messages.jelly.JellySupport;
import org.openldes.jena.messages.lang.ReaderRDFXMLMessages;
import org.openldes.jena.messages.lang.RiotMessageReaders;

/**
 * The registry of {@link MessageReader}s by serialization. Built in: Turtle, TriG, N-Triples, N-Quads, RDF/XML, and
 * Jelly-RDF when {@code jelly-jena} is on the classpath. Others are found with {@link java.util.ServiceLoader} through
 * {@link MessageReaderProvider}, or registered with {@link #register(Lang, Supplier)}.
 * <p>
 * A serialization without a reader here has no notion of messages: {@link RDFMessagesParser} reads such a document with
 * Jena's parser as one message.
 */
public final class MessageReaders {

	private static final Map<Lang, Supplier<MessageReader>> READERS = new ConcurrentHashMap<>();

	static {
		// Serializations of other modules (Jelly-RDF) are registered with Jena when its subsystems are initialized
		JenaSystem.init();
		register(Lang.TURTLE, RiotMessageReaders::turtle);
		register(Lang.TRIG, RiotMessageReaders::trig);
		register(Lang.NTRIPLES, RiotMessageReaders::ntriples);
		register(Lang.NQUADS, RiotMessageReaders::nquads);
		register(Lang.RDFXML, ReaderRDFXMLMessages::new);
		if (JellySupport.isAvailable()) {
			register(JellySupport.lang(), JellySupport::reader);
		}
		for (MessageReaderProvider provider : ServiceLoader.load(MessageReaderProvider.class)) {
			register(provider.lang(), provider::create);
		}
	}

	private MessageReaders() {
	}

	public static void register(Lang lang, Supplier<MessageReader> factory) {
		READERS.put(lang, factory);
	}

	/** The reader for a serialization, if it has message support. */
	public static Optional<MessageReader> get(Lang lang) {
		if (lang == null) {
			return Optional.empty();
		}
		Supplier<MessageReader> factory = READERS.get(lang);
		return factory == null ? Optional.empty() : Optional.of(factory.get());
	}

	/** Whether a serialization has message support: delimiters, or its own framing. */
	public static boolean supports(Lang lang) {
		return lang != null && READERS.containsKey(lang);
	}
}
