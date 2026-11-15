package org.openldes.jena.messages.ldes.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.apache.jena.riot.Lang;
import org.apache.jena.sparql.util.IsoMatcher;
import org.openldes.jena.messages.RDFMessagesParser;
import org.openldes.jena.messages.RdfMessage;
import org.openldes.jena.messages.ldes.LdesClient;
import org.openldes.jena.messages.ldes.LdesMember;

/**
 * One replication in a fresh JVM, for the cross-client benchmark in {@code ldes-client/perf}. The counterpart of
 * {@code perf/lib/replicate.mjs} of the JavaScript ldes-client, with the same checks and the same JSON output line.
 * <p>
 * Timing starts immediately before the client is constructed and ends when the last member has been taken, as in the
 * JavaScript child. It includes fetching, parsing, membership, ordering keys and the checks; it excludes JVM startup and
 * the full content check. Every member ID must be in range and unique, and every member must have 8 quads.
 *
 * <pre>
 * Replicate &lt;url&gt; &lt;expected members&gt; measure [iterations]
 * Replicate &lt;url&gt; &lt;expected members&gt; verify &lt;expected.trig&gt;
 * </pre>
 *
 * With more than one iteration, the same feed is replicated that many times in this JVM, each time with a fresh client
 * (and so a fresh, empty bookmark), and one JSON line is printed per iteration: the later iterations measure the client
 * after start-up, once the JIT has compiled it.
 *
 * In verify mode, every member is compared by isomorphism with message N of the expected log, which the orchestrator
 * writes from the JavaScript fixture generator.
 */
public final class Replicate {

	private static final String PREFIX = "http://example.org/m";

	private Replicate() {
	}

	public static void main(String[] args) throws IOException {
		String url = args[0];
		int expected = Integer.parseInt(args[1]);
		boolean verify = args.length > 3 && "verify".equals(args[2]);
		List<RdfMessage> reference = verify ? RDFMessagesParser.create().source(Path.of(args[3])).lang(Lang.TRIG).toList()
				: null;
		if (verify && reference.size() != expected) {
			throw new IllegalStateException("The expected log has " + reference.size() + " messages, not " + expected);
		}
		int iterations = !verify && args.length > 3 ? Integer.parseInt(args[3]) : 1;
		for (int iteration = 1; iteration <= iterations; iteration++) {
			replicate(url, expected, reference, iteration);
		}
		System.out.flush();
		// Virtual threads of the HTTP client do not keep the JVM alive, but an idle connection pool may delay exit
		System.exit(0);
	}

	private static void replicate(String url, int expected, List<RdfMessage> reference, int iteration) {
		boolean[] seen = new boolean[expected];
		long members = 0;
		double firstMemberSeconds = -1;

		long started = System.nanoTime();
		try (LdesClient client = LdesClient.create(url).highWaterMark(100).build()) {
			for (LdesMember member : client) {
				if (firstMemberSeconds < 0) {
					firstMemberSeconds = (System.nanoTime() - started) / 1e9;
				}
				String iri = member.id().getURI();
				int id = iri.startsWith(PREFIX) ? parse(iri.substring(PREFIX.length())) : -1;
				if (id < 0 || id >= expected || seen[id]) {
					throw new IllegalStateException("Invalid/duplicate member: " + iri);
				}
				seen[id] = true;
				long quads = member.message().size();
				if (quads != 8) {
					throw new IllegalStateException("Member " + id + ": expected 8 quads, got " + quads);
				}
				if (reference != null && !IsoMatcher.isomorphic(member.message().dataset(), reference.get(id).dataset())) {
					throw new IllegalStateException("Data mismatch for member " + id);
				}
				members++;
			}
		}
		if (members != expected) {
			throw new IllegalStateException("Expected " + expected + " members, got " + members);
		}
		double seconds = (System.nanoTime() - started) / 1e9;
		System.out.println(String.format(Locale.ROOT,
				"{\"iteration\":%d,\"members\":%d,\"seconds\":%.6f,\"firstMemberSeconds\":%.6f,\"peakRss\":%d}", iteration,
				members, seconds, firstMemberSeconds, peakRss()));
	}

	private static int parse(String digits) {
		try {
			return Integer.parseInt(digits);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** The peak resident set size of this process, in bytes (Linux), or 0 when unknown. */
	private static long peakRss() {
		try {
			for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
				if (line.startsWith("VmHWM:")) {
					return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
				}
			}
		} catch (IOException | RuntimeException e) {
			// not on Linux
		}
		return 0;
	}
}
