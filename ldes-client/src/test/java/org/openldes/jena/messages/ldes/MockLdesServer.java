package org.openldes.jena.messages.ldes;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * A mock LDES server that serves in-memory pages, and keeps a log of the requests it receives. A page in a message
 * serialization has to announce it: {@code VERSION "1.2-messages"}.
 */
final class MockLdesServer implements AutoCloseable {

	static final String PREFIXES = """
			PREFIX ldes: <https://w3id.org/ldes#>
			PREFIX tree: <https://w3id.org/tree#>
			PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
			PREFIX ex: <http://example.org/>
			PREFIX dcterms: <http://purl.org/dc/terms/>
			PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
			""";

	record Page(byte[] body, String type, int status, Map<String, String> headers, long delayMillis) {
		static Page of(String body, String type) {
			return new Page(body.getBytes(StandardCharsets.UTF_8), type, 200, Map.of(), 0);
		}

		Page withDelay(long millis) {
			return new Page(body, type, status, headers, millis);
		}

		Page withStatus(int code) {
			return new Page(body, type, code, headers, delayMillis);
		}

		Page withHeaders(Map<String, String> extra) {
			return new Page(body, type, status, extra, delayMillis);
		}
	}

	final Map<String, Page> pages = new ConcurrentHashMap<>();
	final List<String> requests = Collections.synchronizedList(new ArrayList<>());
	final AtomicInteger inFlight = new AtomicInteger();
	volatile int maxInFlight = 0;
	private final HttpServer server;

	MockLdesServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			requests.add(path);
			Page page = pages.get(path);
			if (page == null) {
				byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(404, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
				return;
			}
			int now = inFlight.incrementAndGet();
			synchronized (this) {
				maxInFlight = Math.max(maxInFlight, now);
			}
			try {
				if (page.delayMillis() > 0) {
					Thread.sleep(page.delayMillis());
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				inFlight.decrementAndGet();
			}
			String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
			String etag = page.headers().get("ETag");
			exchange.getResponseHeaders().add("Content-Type", page.type());
			page.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
			if (etag != null && etag.equals(ifNoneMatch) && page.status() == 200) {
				exchange.sendResponseHeaders(304, -1);
				exchange.close();
				return;
			}
			exchange.sendResponseHeaders(page.status(), page.body().length == 0 ? -1 : page.body().length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(page.body());
			}
		});
		server.start();
	}

	String base() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	String url(String path) {
		return base() + path;
	}

	/** Serves a TriG message log, in which messages are separated by MESSAGE. {@code <>} is replaced by the page IRI. */
	void trig(String path, String... messages) {
		StringBuilder body = new StringBuilder("VERSION \"1.2-messages\"\n").append(PREFIXES).append('\n');
		for (int i = 0; i < messages.length; i++) {
			if (i > 0) {
				body.append("\nMESSAGE\n");
			}
			body.append(messages[i].replace("<>", "<" + url(path) + ">"));
		}
		body.append('\n');
		pages.put(path, Page.of(body.toString(), "application/trig"));
	}

	void set(String path, Page page) {
		pages.put(path, page);
	}

	void reset() {
		pages.clear();
		requests.clear();
		inFlight.set(0);
		maxInFlight = 0;
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
