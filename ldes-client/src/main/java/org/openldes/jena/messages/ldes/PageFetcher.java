package org.openldes.jena.messages.ldes;

/** Dereferences the IRI of a page to its message log. */
public interface PageFetcher {

	/**
	 * @param url              the IRI of the page
	 * @param etag             the ETag the page had when it was read completely, to request it with {@code If-None-Match};
	 *                         or null
	 * @param bufferedMessages how many messages the log reads ahead
	 */
	FetchedPage fetch(String url, String etag, int bufferedMessages);
}
