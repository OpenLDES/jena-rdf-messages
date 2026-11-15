package org.openldes.jena.messages.ldes;

/** A problem with an LDES: a page that does not follow the specification, or a request that failed. */
public class LdesClientException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public LdesClientException(String message) {
		super(message);
	}

	public LdesClientException(String message, Throwable cause) {
		super(message, cause);
	}
}
