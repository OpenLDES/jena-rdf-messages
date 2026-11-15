package org.openldes.jena.messages.ldes;

/** The order of an LDES: by {@code ldes:timestampPath} and/or {@code ldes:sequencePath}, or implicit (page numbers). */
public enum OrderMode {
	IMPLICIT, TIMESTAMP, SEQUENCE, TIMESTAMP_SEQUENCE
}
