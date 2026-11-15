package org.openldes.jena.messages.perf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDFBase;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.util.IsoMatcher;
import org.openldes.jena.messages.RDFMessagesParser;
import org.openldes.jena.messages.RdfMessage;
import org.openldes.jena.messages.StreamRDFMessages;
import org.openldes.jena.messages.jelly.JellySupport;

import eu.neverblink.jelly.core.proto.v1.RdfStreamFrame;
import eu.neverblink.jelly.core.utils.IoUtils;

/**
 * CityPulse 10K parsing audit: verifies message equivalence and compares message parsing with plain Jena.
 * Pass the downloaded {@code flat_10K.nt.gz} and {@code jelly_10K.2.jelly.gz} paths, in that order.
 * Uses five warmups and nine measured runs by default; override with {@code -Dwarmups=} and {@code -Druns=}.
 * Downloads, disk reads, input preparation, and equivalence checks are outside timing.
 */
public final class CityPulseBenchmark {

	private CityPulseBenchmark() {
	}

	private record Sample(String name, Lang lang, byte[] gzip, byte[] raw) {
		InputStream open(boolean compressed) throws IOException {
			return compressed ? new GZIPInputStream(new ByteArrayInputStream(gzip), 64 * 1024)
					: new ByteArrayInputStream(raw);
		}

		RDFMessagesParser parser(boolean compressed) throws IOException {
			return RDFMessagesParser.create().source(open(compressed)).lang(lang);
		}
	}

	private static class Counts extends StreamRDFBase implements StreamRDFMessages {
		private final long statementsPerMessage;
		long messages;
		long statements;
		long pending;

		Counts(long statementsPerMessage) {
			this.statementsPerMessage = statementsPerMessage;
		}

		@Override
		public void triple(Triple triple) {
			statements++;
			pending++;
		}

		@Override
		public void quad(Quad quad) {
			statements++;
			pending++;
		}

		@Override
		public void message() {
			require(pending == statementsPerMessage, "Message " + messages + " has " + pending + " statements");
			messages++;
			pending = 0;
		}

		void accept(RdfMessage message) {
			require(message.index() == messages, "Unexpected message index");
			pending = message.size();
			statements += pending;
			message();
		}

		void verify(long expectedMessages) {
			require(messages == expectedMessages && statements == 360_000
					&& (expectedMessages == 0 || pending == 0),
					"Incorrect totals: messages=" + messages + ", statements=" + statements);
		}
	}

	private record Case(Sample sample, boolean compressed, String api, double[] seconds) {
		void parse() throws IOException {
			Counts counts = new Counts(api.equals("flat") ? 360_000 : 36);
			if (api.equals("jena")) {
				try (InputStream input = sample.open(compressed)) {
					RDFParser.create().source(input).lang(Lang.NTRIPLES).parse(counts);
				}
				counts.verify(0);
				return;
			}
			var parser = sample.parser(compressed);
			switch (api) {
				case "events", "flat" -> parser.parse(counts);
				case "forEach" -> parser.forEach(counts::accept);
				case "iterator" -> {
					try (var log = parser.iterator()) {
						while (log.hasNext()) {
							counts.accept(log.next());
						}
					}
				}
				default -> throw new IllegalArgumentException(api);
			}
			counts.verify(api.equals("flat") ? 1 : 10_000);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	public static void main(String[] args) throws Exception {
		Locale.setDefault(Locale.ROOT);
		int warmups = Integer.getInteger("warmups", 5);
		int runs = Integer.getInteger("runs", 9);
		require(args.length == 2 && warmups > 0 && runs > 0 && runs % 2 == 1,
				"Pass NT and Jelly gzip paths; warmups > 0 and runs must be positive and odd");
		List<Sample> samples = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			byte[] gzip = Files.readAllBytes(Path.of(args[i]));
			byte[] raw;
			try (var in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
				raw = in.readAllBytes();
			}
			Sample sample = new Sample(i == 0 ? "N-Triples" : "Jelly", i == 0 ? Lang.NTRIPLES : JellySupport.lang(),
					gzip, raw);
			samples.add(sample);
			System.out.printf("%s: gzip=%,d bytes; raw=%,d bytes%n", sample.name(), gzip.length, raw.length);
		}
		System.out.printf("Java %s; warmups=%d; measured runs=%d; max heap=%,d bytes%n",
				Runtime.version(), warmups, runs, Runtime.getRuntime().maxMemory());
		inspectJelly(samples.get(1));
		compare(samples.get(0), samples.get(1));
		List<Case> cases = new ArrayList<>();
		for (String api : List.of("events", "forEach", "iterator")) {
			for (boolean gzip : List.of(false, true)) {
				for (Sample sample : samples) {
					cases.add(new Case(sample, gzip, api, new double[runs]));
				}
			}
		}
		// Strip only the known declaration and delimiter lines, preserving the CityPulse triples.
		// Plain Jena cannot consume MESSAGE directives. Both baselines parse the same unframed bytes.
		byte[] flat = new String(samples.getFirst().raw(), StandardCharsets.UTF_8)
				.replaceAll("(?m)^VERSION \"1\\.1-messages\"\\r?\\n|^MESSAGE\\r?\\n", "")
				.getBytes(StandardCharsets.UTF_8);
		Sample unframed = new Sample("N-Triples (unframed)", Lang.NTRIPLES, null, flat);
		cases.add(new Case(unframed, false, "jena", new double[runs]));
		cases.add(new Case(unframed, false, "flat", new double[runs]));
		// Interleave formats/APIs so later cases do not systematically benefit from more JIT warmup.
		for (int round = 0; round < warmups + runs; round++) {
			List<Case> order = new ArrayList<>(cases);
			if (round % 2 == 1) {
				Collections.reverse(order);
			}
			for (Case c : order) {
				long start = System.nanoTime();
				c.parse();
				double elapsed = (System.nanoTime() - start) / 1e9;
				if (round >= warmups) {
					c.seconds()[round - warmups] = elapsed;
				}
			}
			System.out.println("Completed " + (round < warmups ? "warmup " + (round + 1)
					: "measurement " + (round - warmups + 1)));
		}
		System.out.println("| API | Input | Format | Median ms | Min ms | Max ms | Messages/s | Triples/s |");
		System.out.println("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |");
		for (Case c : cases) {
			double[] sorted = c.seconds().clone();
			Arrays.sort(sorted);
			double median = sorted[runs / 2];
			System.out.printf("| %s | %s | %s | %.2f | %.2f | %.2f | %,.0f | %,.0f |%n", c.api(),
					c.compressed() ? "gzip" : "raw", c.sample().name(), median * 1000,
					sorted[0] * 1000, sorted[runs - 1] * 1000, 10_000 / median, 360_000 / median);
			System.out.println("Samples " + c.api() + "/" + c.compressed() + "/" + c.sample().name()
					+ ": " + Arrays.toString(c.seconds()));
		}
	}

	private static void inspectJelly(Sample jelly) throws IOException {
		long[] frames = { 0 };
		IoUtils.readStream(new ByteArrayInputStream(jelly.raw()), RdfStreamFrame.getFactory(), frame -> {
			frames[0]++;
			for (var row : frame.getRows()) {
				if (row.hasOptions()) {
					var options = row.getOptions();
					System.out.printf("Jelly options: version=%d, stream=%s, physical=%s, logical=%s, rdf=%s%n",
							options.getVersion(), options.getStreamType(), options.getPhysicalType(),
							options.getLogicalType(), options.getRdfVersion());
				}
			}
		});
		System.out.println("Jelly frames: " + frames[0]);
	}

	private static void compare(Sample nt, Sample jelly) throws IOException {
		Counts counts = new Counts(36);
		try (var left = nt.parser(true).iterator(); var right = jelly.parser(true).iterator()) {
			while (left.hasNext()) {
				require(right.hasNext(), "Jelly ends before NT");
				var a = left.next();
				var b = right.next();
				require(a.index() == b.index(), "Message order differs");
				require(IsoMatcher.isomorphic(a.dataset(), b.dataset()), "Non-isomorphic message " + a.index());
				counts.accept(a);
			}
			require(!right.hasNext(), "Extra Jelly messages");
		}
		counts.verify(10_000);
		System.out.println("Equivalent in order: 10,000 dataset-isomorphic messages, "
				+ "each with 36 triples; total 360,000");
	}
}
