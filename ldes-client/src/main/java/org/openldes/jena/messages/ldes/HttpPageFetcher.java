package org.openldes.jena.messages.ldes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.apache.jena.atlas.web.ContentType;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.openldes.jena.messages.MessageLog;
import org.openldes.jena.messages.MessageVersions;
import org.openldes.jena.messages.RDFMessagesParser;
import org.openldes.jena.messages.jelly.JellySupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches pages over HTTP as the <a href="https://w3id.org/ldes/specification#http-requests-responses">specification
 * requires</a>: with an {@code Accept} header that prefers message serializations, following redirects, retrying
 * {@code 408}, {@code 425}, {@code 429}, {@code 500}, {@code 502}, {@code 503} and {@code 504} with exponential
 * back-off, treating {@code 410 Gone} as a page without members and relations, and revalidating with
 * {@code If-None-Match}. Any other 4xx or 5xx status is an error. Local files and {@code file:} IRIs are read directly.
 * <p>
 * The body is parsed while it is received: the page's messages are available before the page is complete.
 */
public final class HttpPageFetcher implements PageFetcher {

	private static final Logger LOG = LoggerFactory.getLogger(HttpPageFetcher.class);

	/** The status codes that are retried with back-off. */
	public static final Set<Integer> RETRY_CODES = Set.of(408, 425, 429, 500, 502, 503, 504);

	/** Serializations that carry named graphs and RDF Messages come first. */
	public static final String DEFAULT_ACCEPT = String.join(", ", List.of("application/trig", "application/n-quads;q=0.95",
			"application/x-jelly-rdf;q=0.9", "text/turtle;q=0.8", "application/n-triples;q=0.8", "application/ld+json;q=0.7",
			"application/rdf+xml;q=0.6"));

	private final HttpClient client;
	private final String accept;
	private final int maxRetries;
	private final Duration retryBase;
	private final Set<Integer> retryCodes;

	public HttpPageFetcher() {
		this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build(),
				DEFAULT_ACCEPT, 3, Duration.ofMillis(500), RETRY_CODES);
	}

	/**
	 * @param client     the HTTP client; it should follow redirects
	 * @param accept     the {@code Accept} header
	 * @param maxRetries how many times a request with a retryable status is repeated; 0 is unlimited
	 * @param retryBase  the time before the first retry, which doubles every time
	 * @param retryCodes the status codes that are retried
	 */
	public HttpPageFetcher(HttpClient client, String accept, int maxRetries, Duration retryBase, Set<Integer> retryCodes) {
		this.client = Objects.requireNonNull(client);
		this.accept = accept;
		this.maxRetries = maxRetries;
		this.retryBase = retryBase;
		this.retryCodes = retryCodes;
	}

	@Override
	public FetchedPage fetch(String url, String etag, int bufferedMessages) {
		if (!url.startsWith("http://") && !url.startsWith("https://")) {
			return fetchLocal(url, bufferedMessages);
		}
		int attempt = 0;
		while (true) {
			HttpResponse<InputStream> response = send(url, etag);
			int status = response.statusCode();
			if (retryCodes.contains(status) && (maxRetries == 0 || attempt < maxRetries)) {
				closeQuietly(response.body());
				Duration delay = retryBase.multipliedBy(1L << Math.min(attempt, 20));
				if (delay.compareTo(Duration.ofSeconds(30)) > 0) {
					delay = Duration.ofSeconds(30);
				}
				LOG.warn("{} answered HTTP {}, retrying in {} ms", url, status, delay.toMillis());
				sleep(delay);
				attempt++;
				continue;
			}
			return open(url, response, bufferedMessages);
		}
	}

	private HttpResponse<InputStream> send(String url, String etag) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET().header("Accept", accept)
				.header("Accept-Encoding", "gzip");
		if (etag != null) {
			request.header("If-None-Match", etag);
		}
		try {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
		} catch (IOException e) {
			throw new LdesClientException("Could not dereference " + url + ": " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LdesClientException("Interrupted while requesting " + url, e);
		}
	}

	private FetchedPage open(String requested, HttpResponse<InputStream> response, int bufferedMessages) {
		int status = response.statusCode();
		String url = response.uri().toString();
		String etag = response.headers().firstValue("etag").orElse(null);
		if (status == 304 || status == 410) {
			closeQuietly(response.body());
			return FetchedPage.empty(url, status, etag);
		}
		if (status >= 400) {
			closeQuietly(response.body());
			throw new HttpStatusException(requested, status);
		}
		String contentType = response.headers().firstValue("content-type").orElse(null);
		boolean immutable = response.headers().firstValue("cache-control").map(v -> v.toLowerCase().contains("immutable"))
				.orElse(false);
		boolean gzip = response.headers().firstValue("content-encoding").map(v -> v.toLowerCase().contains("gzip"))
				.orElse(false);
		InputStream body = response.body();
		if (gzip || (url.endsWith(".gz") && isGenericType(contentType))) {
			try {
				body = new GZIPInputStream(body);
			} catch (IOException e) {
				closeQuietly(body);
				throw new LdesClientException("Could not decompress " + url, e);
			}
		}
		return page(url, status, etag, contentType, immutable, body, bufferedMessages);
	}

	/** Opens the body of a response as a page: the serialization is derived from the media type, or else the file name. */
	static FetchedPage page(String url, int status, String etag, String contentType, boolean immutable, InputStream body,
			int bufferedMessages) {
		Lang lang = null;
		if (contentType != null && !isGenericType(contentType)) {
			lang = RDFLanguages.contentTypeToLang(ContentType.create(contentType));
		}
		String name = url.endsWith(".gz") ? url.substring(0, url.length() - 3) : url;
		if (lang == null) {
			lang = RDFLanguages.filenameToLang(name);
		}
		if (lang == null && JellySupport.isAvailable() && name.endsWith(".jelly")) {
			lang = JellySupport.lang();
		}
		if (lang == null) {
			closeQuietly(body);
			throw new LdesClientException("Cannot determine the serialization of " + url + " (media type " + contentType + ")");
		}
		boolean announced = MessageVersions.versionOf(contentType).filter(MessageVersions::announcesMessages).isPresent();
		boolean profile = contentType != null && contentType.contains(LdesVocab.PROFILE);
		boolean framed = announced || profile || name.matches(".*\\.ldes\\.[a-z]+$");
		MessageLog log = RDFMessagesParser.create().source(body).lang(lang).base(url).messages(announced)
				.bufferedMessages(bufferedMessages).iterator();
		return new FetchedPage(url, status, etag, contentType, framed, immutable, log);
	}

	private FetchedPage fetchLocal(String location, int bufferedMessages) {
		try {
			java.nio.file.Path path = location.startsWith("file:") ? java.nio.file.Path.of(URI.create(location))
					: java.nio.file.Path.of(location);
			String url = path.toUri().toString();
			InputStream in = java.nio.file.Files.newInputStream(path);
			return page(url, 200, null, null, false, in, bufferedMessages);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static boolean isGenericType(String contentType) {
		return contentType == null || contentType.startsWith("text/plain") || contentType.startsWith("application/octet-stream");
	}

	private static void closeQuietly(InputStream in) {
		try {
			in.close();
		} catch (IOException e) {
			// nothing to release
		}
	}

	private static void sleep(Duration delay) {
		try {
			Thread.sleep(delay.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LdesClientException("Interrupted while waiting to retry", e);
		}
	}

}
