package org.openldes.jena.messages;

import org.apache.jena.riot.Lang;

/**
 * Registers a {@link MessageReader} for a serialization, through {@link java.util.ServiceLoader}: list the implementation
 * in {@code META-INF/services/org.openldes.jena.messages.MessageReaderProvider}.
 */
public interface MessageReaderProvider {

	/** The serialization the reader reads. The {@link Lang} must be registered with Jena's {@code RDFLanguages}. */
	Lang lang();

	MessageReader create();
}
