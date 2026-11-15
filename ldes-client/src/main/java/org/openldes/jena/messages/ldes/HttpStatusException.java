package org.openldes.jena.messages.ldes;

/** A request that was answered with a 4xx or 5xx status code that the client does not handle. */
public final class HttpStatusException extends LdesClientException {

	private static final long serialVersionUID = 1L;
	private final String url;
	private final int status;

	public HttpStatusException(String url, int status) {
		super("Could not dereference " + url + ": HTTP " + status);
		this.url = url;
		this.status = status;
	}

	public String url() {
		return url;
	}

	public int status() {
		return status;
	}
}
