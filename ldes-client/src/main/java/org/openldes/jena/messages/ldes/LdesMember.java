package org.openldes.jena.messages.ldes;

import java.time.Instant;
import java.util.Optional;

import org.apache.jena.graph.Node;
import org.openldes.jena.messages.RdfMessage;

/**
 * A member of an LDES as the client <a href="https://w3id.org/ldes/specification#members">emits</a> it: the member
 * identifier and all quads of its message, plus the metadata that the context information makes available.
 *
 * @param id                   the member identifier
 * @param message              the member message: all its quads, with the default timezone added to timestamps without
 *                             one when it is known
 * @param key                  the ordering key of the member
 * @param page                 the IRI of the page the member was found in
 * @param timestamp            the value of {@code ldes:timestampPath}, when set
 * @param sequence             the value of {@code ldes:sequencePath}, when set
 * @param isVersionOf          the value of {@code ldes:versionOfPath}, when set and present
 * @param transactionFinalized whether the member finalizes its transaction, when {@code ldes:transactionFinalizedPath} is set
 * @param type                 the {@code rdf:type} of the member, when present
 */
public record LdesMember(Node id, RdfMessage message, OrderingKey key, String page, Optional<Instant> timestamp,
		Optional<Node> sequence, Optional<Node> isVersionOf, Optional<Boolean> transactionFinalized, Optional<Node> type) {
}
