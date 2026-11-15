package org.openldes.jena.messages.perf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.sparql.core.Quad;
import org.openldes.jena.messages.RDFMessages;
import org.openldes.jena.messages.RDFMessagesParser;
import org.openldes.jena.messages.RdfMessage;
import org.openldes.jena.messages.StreamRDFMessages;
import org.openldes.jena.messages.jelly.JellySupport;

import org.apache.jena.graph.Triple;

/**
 * Throughput of the message log parsers and writers: TriG, N-Quads and Jelly-RDF, for a log of many small messages and
 * a log of a few large messages. Median of five runs after a warm-up. Run with:
 *
 * <pre>mvn -pl parsers -Pperf exec:java</pre>
 *
 * System properties: {@code messages} (default 20000), {@code quads} per message (default 10), {@code runs} (5).
 */
public final class Benchmark {

	private Benchmark() {
	}

	public static void main(String[] args) {
		int messages = Integer.getInteger("messages", 20_000);
		int quadsPerMessage = Integer.getInteger("quads", 10);
		int runs = Integer.getInteger("runs", 5);
		List<Lang> langs = new ArrayList<>(List.of(Lang.TRIG, Lang.NQUADS));
		if (JellySupport.isAvailable()) {
			langs.add(JellySupport.lang());
		}
		System.out.printf(Locale.ROOT, "Java %s, %d messages x %d quads, median of %d runs%n%n", Runtime.version(),
				messages, quadsPerMessage, runs);
		System.out.println("| Serialization | Size | Write (quads/s) | Parse, push (quads/s) | Parse, pull (quads/s) |");
		System.out.println("| --- | ---: | ---: | ---: | ---: |");
		List<RdfMessage> data = generate(messages, quadsPerMessage);
		long quads = (long) messages * quadsPerMessage;
		for (Lang lang : langs) {
			byte[] log = write(lang, data);
			double write = median(runs, () -> write(lang, data).length);
			double push = median(runs, () -> parsePush(lang, log));
			double pull = median(runs, () -> parsePull(lang, log));
			System.out.printf(Locale.ROOT, "| %s | %.1f MB | %s | %s | %s |%n", lang.getName(), log.length / 1e6,
					rate(quads, write), rate(quads, push), rate(quads, pull));
		}
	}

	private static String rate(long quads, double seconds) {
		return String.format(Locale.ROOT, "%,.0f", quads / seconds);
	}

	private static List<RdfMessage> generate(int messages, int quadsPerMessage) {
		List<RdfMessage> result = new ArrayList<>(messages);
		for (int m = 0; m < messages; m++) {
			List<Quad> quads = new ArrayList<>(quadsPerMessage);
			var graph = NodeFactory.createURI("http://example.org/message/" + m);
			for (int q = 0; q < quadsPerMessage; q++) {
				quads.add(Quad.create(graph, NodeFactory.createURI("http://example.org/observation/" + m),
						NodeFactory.createURI("http://example.org/property/" + q),
						q % 2 == 0 ? NodeFactory.createLiteralString("value " + m + "-" + q)
								: NodeFactory.createLiteralDT(String.valueOf(m * 31 + q), org.apache.jena.datatypes.xsd.XSDDatatype.XSDinteger)));
			}
			result.add(RdfMessage.of(quads));
		}
		return result;
	}

	private static byte[] write(Lang lang, List<RdfMessage> data) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RDFMessages.write(out, lang, data);
		return out.toByteArray();
	}

	private static long parsePush(Lang lang, byte[] log) {
		long[] count = { 0 };
		RDFMessagesParser.create().source(new ByteArrayInputStream(log)).lang(lang).parse(new StreamRDFMessages() {
			@Override
			public void start() {
			}

			@Override
			public void triple(Triple triple) {
				count[0]++;
			}

			@Override
			public void quad(Quad quad) {
				count[0]++;
			}

			@Override
			public void base(String base) {
			}

			@Override
			public void prefix(String prefix, String iri) {
			}

			@Override
			public void version(String version) {
			}

			@Override
			public void message() {
			}

			@Override
			public void finish() {
			}
		});
		return count[0];
	}

	private static long parsePull(Lang lang, byte[] log) {
		long count = 0;
		try (var messages = RDFMessagesParser.create().source(new ByteArrayInputStream(log)).lang(lang).iterator()) {
			while (messages.hasNext()) {
				count += messages.next().size();
			}
		}
		return count;
	}

	/** Median duration in seconds of {@code runs} runs, after one warm-up run. */
	private static double median(int runs, Runnable task) {
		task.run();
		double[] times = new double[runs];
		for (int i = 0; i < runs; i++) {
			long start = System.nanoTime();
			task.run();
			times[i] = (System.nanoTime() - start) / 1e9;
		}
		Arrays.sort(times);
		return times[runs / 2];
	}

	private static double median(int runs, java.util.function.LongSupplier task) {
		return median(runs, (Runnable) task::getAsLong);
	}
}
