package org.openldes.jena.messages.ldes.perf.ldi;

import static org.apache.jena.rdf.model.ResourceFactory.createProperty;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.openldes.ldi.requestexecutor.executor.RequestExecutor;
import org.openldes.ldi.requestexecutor.executor.retry.RetryConfig;
import org.openldes.ldi.requestexecutor.services.RequestExecutorDecorator;
import org.openldes.ldi.requestexecutor.services.RequestExecutorFactory;
import org.openldes.ldi.timestampextractor.TimestampFromPathExtractor;

import ldes.client.eventstreamproperties.EventStreamPropertiesFetcher;
import ldes.client.eventstreamproperties.valueobjects.EventStreamProperties;
import ldes.client.eventstreamproperties.valueobjects.PropertiesRequest;
import ldes.client.treenodesupplier.TreeNodeProcessor;
import ldes.client.treenodesupplier.domain.valueobject.LdesClientRepositories;
import ldes.client.treenodesupplier.domain.valueobject.LdesMetaData;
import ldes.client.treenodesupplier.domain.valueobject.SuppliedMember;
import ldes.client.treenodesupplier.filters.ExactlyOnceFilter;
import ldes.client.treenodesupplier.membersuppliers.FilteredMemberSupplier;
import ldes.client.treenodesupplier.membersuppliers.MemberSupplier;
import ldes.client.treenodesupplier.membersuppliers.MemberSupplierImpl;

/**
 * One replication of an LDES 1.0 feed with the LDES client of Linked Data Interactions (LDI), for the cross-client
 * benchmark in {@code ldes-client/perf}. The counterpart of {@code Replicate.java} (this repository's LDES 2.0 client)
 * and of {@code perf/lib/replicate.mjs} of the JavaScript ldes-client, with the same checks and the same JSON line.
 * <p>
 * The client is wired as LDIO's {@code Ldio:LdesClient} input wires it with its default configuration
 * ({@code MemberSupplierFactory} and {@code LdioLdesClientConfigurator} of LDI 3.2.2): Turtle as source format, state in
 * memory, the exactly-once filter on, no version materialisation, a request executor without authentication and with
 * 5 retries, no rate limit. LDIO hands every member to its pipeline from one thread; here the loop takes the members
 * itself.
 * <p>
 * Timing starts immediately before the client is constructed (which fetches the event stream properties) and ends
 * when the last expected member has been taken. Every member ID must be in range and unique, and every member must
 * have 8 triples.
 *
 * <pre>
 * ReplicateLdi &lt;url&gt; &lt;expected members&gt; measure [iterations]
 * ReplicateLdi &lt;url&gt; &lt;expected members&gt; verify &lt;expected-members.ttl&gt;
 * </pre>
 *
 * In verify mode, every member is compared by isomorphism with member N of the expected file, which the orchestrator
 * writes from the JavaScript fixture generator: its prefix lines, then one member per line.
 */
public final class ReplicateLdi {

	private static final String PREFIX = "http://example.org/m";

	private ReplicateLdi() {
	}

	public static void main(String[] args) throws IOException {
		String url = args[0];
		int expected = Integer.parseInt(args[1]);
		boolean verify = args.length > 3 && "verify".equals(args[2]);
		List<String> reference = verify ? Files.readAllLines(Path.of(args[3])) : null;
		String prefixes = verify ? String.join("\n", reference.stream().filter(l -> l.startsWith("@prefix")).toList())
				: null;
		List<String> members = verify ? reference.stream().filter(l -> !l.startsWith("@prefix") && !l.isBlank()).toList()
				: null;
		if (verify && members.size() != expected) {
			throw new IllegalStateException("The expected file has " + members.size() + " members, not " + expected);
		}
		int iterations = !verify && args.length > 3 ? Integer.parseInt(args[3]) : 1;
		for (int iteration = 1; iteration <= iterations; iteration++) {
			replicate(url, expected, prefixes, members, iteration);
		}
		System.out.flush();
		System.exit(0);
	}

	/** The member supplier of LDIO's Ldio:LdesClient with its default configuration. */
	private static MemberSupplier client(String url) {
		Lang lang = Lang.TURTLE;
		RequestExecutor base = new RequestExecutorFactory(false).createNoAuthExecutor();
		RequestExecutor requestExecutor = RequestExecutorDecorator.decorate(base)
				.with(RetryConfig.of(5, new ArrayList<>()).getRetry())
				.get();
		EventStreamProperties properties = new EventStreamPropertiesFetcher(requestExecutor)
				.fetchEventStreamProperties(new PropertiesRequest(url, lang));
		TreeNodeProcessor processor = new TreeNodeProcessor(new LdesMetaData(List.of(url), lang),
				LdesClientRepositories.memoryBased(), requestExecutor,
				new TimestampFromPathExtractor(createProperty(properties.getTimestampPath())), status -> {
				});
		MemberSupplier supplier = new MemberSupplierImpl(processor, false);
		return new FilteredMemberSupplier(supplier,
				new ExactlyOnceFilter(LdesClientRepositories.memoryBased().memberIdRepository(), false));
	}

	private static void replicate(String url, int expected, String prefixes, List<String> reference, int iteration) {
		boolean[] seen = new boolean[expected];
		long members = 0;
		double firstMemberSeconds = -1;

		long started = System.nanoTime();
		MemberSupplier client = client(url);
		client.init();
		// The feed's pages are immutable, so the client would end after the last member; stop as soon as it arrives
		while (members < expected) {
			SuppliedMember member = client.get();
			if (firstMemberSeconds < 0) {
				firstMemberSeconds = (System.nanoTime() - started) / 1e9;
			}
			String iri = member.getId();
			int id = iri.startsWith(PREFIX) ? parse(iri.substring(PREFIX.length())) : -1;
			if (id < 0 || id >= expected || seen[id]) {
				throw new IllegalStateException("Invalid/duplicate member: " + iri);
			}
			seen[id] = true;
			Model model = member.getModel();
			long triples = model.size();
			if (triples != 8) {
				throw new IllegalStateException("Member " + id + ": expected 8 triples, got " + triples);
			}
			if (reference != null && !model.isIsomorphicWith(turtle(prefixes + "\n" + reference.get(id)))) {
				throw new IllegalStateException("Data mismatch for member " + id);
			}
			members++;
		}
		double seconds = (System.nanoTime() - started) / 1e9;
		client.destroyState();
		System.out.println(String.format(Locale.ROOT,
				"{\"iteration\":%d,\"members\":%d,\"seconds\":%.6f,\"firstMemberSeconds\":%.6f,\"peakRss\":%d}", iteration,
				members, seconds, firstMemberSeconds, peakRss()));
	}

	private static Model turtle(String turtle) {
		Model model = ModelFactory.createDefaultModel();
		RDFParser.create().source(new StringReader(turtle)).lang(Lang.TURTLE).parse(model);
		return model;
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
