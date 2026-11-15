package org.openldes.jena.messages.ldes;

import org.openldes.jena.messages.MessageLog;

/**
 * A page as it was received: the IRI after redirects, the status, the headers the client uses, and the message log of
 * the body. A {@code 304 Not Modified} or {@code 410 Gone} answer has an empty log.
 *
 * @param url         the IRI of the page, after following redirects
 * @param status      the HTTP status (200 for a local file)
 * @param etag        the ETag of the response, or null
 * @param contentType the media type of the response, or null
 * @param framed      whether the response announced itself as an LDES page or a message log (media type profile or
 *                    version, or an {@code .ldes.} file name infix)
 * @param immutable   whether the response had {@code Cache-Control: immutable}
 * @param log         the messages of the body
 */
public record FetchedPage(String url, int status, String etag, String contentType, boolean framed, boolean immutable,
		MessageLog log) {

	public static FetchedPage empty(String url, int status, String etag) {
		return new FetchedPage(url, status, etag, null, false, false, MessageLog.empty());
	}
}
