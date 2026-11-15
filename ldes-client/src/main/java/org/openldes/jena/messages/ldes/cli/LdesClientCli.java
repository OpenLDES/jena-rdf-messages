package org.openldes.jena.messages.ldes.cli;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.riot.Lang;
import org.openldes.jena.messages.RDFMessagesWriter;
import org.openldes.jena.messages.StreamRDFMessages;
import org.openldes.jena.messages.jelly.JellySupport;
import org.openldes.jena.messages.ldes.FileBookmarkStore;
import org.openldes.jena.messages.ldes.LdesClient;
import org.openldes.jena.messages.ldes.LdesContext;
import org.openldes.jena.messages.ldes.LdesListener;
import org.openldes.jena.messages.ldes.LdesMember;
import org.openldes.jena.messages.ldes.Timestamps;

/**
 * The command line client: replicates an LDES to standard output as an RDF Message log, one message per member.
 *
 * <pre>
 * ldes-client &lt;url&gt; [--follow] [--save &lt;dir&gt;] [--start-fresh] [--after &lt;dateTime&gt;] [--before &lt;dateTime&gt;]
 *             [--poll-interval &lt;ms&gt;] [--concurrent &lt;n&gt;] [--default-timezone &lt;offset&gt;]
 *             [--format trig|nquads|jelly] [--quiet]
 * </pre>
 */
public final class LdesClientCli {

	private LdesClientCli() {
	}

	public static void main(String[] args) {
		PrintStream err = System.err;
		String url = null;
		boolean follow = false;
		Path save = null;
		boolean startFresh = false;
		Instant after = null;
		Instant before = null;
		Duration pollInterval = Duration.ofSeconds(1);
		int concurrent = 10;
		ZoneOffset timezone = null;
		String format = "trig";
		boolean quiet = false;
		List<String> arguments = new ArrayList<>(List.of(args));
		try {
			while (!arguments.isEmpty()) {
				String argument = arguments.remove(0);
				switch (argument) {
					case "-f", "--follow" -> follow = true;
					case "-s", "--save" -> save = Path.of(arguments.remove(0));
					case "--start-fresh" -> startFresh = true;
					case "--after" -> after = instant(arguments.remove(0));
					case "--before" -> before = instant(arguments.remove(0));
					case "--poll-interval" -> pollInterval = Duration.ofMillis(Long.parseLong(arguments.remove(0)));
					case "--concurrent" -> concurrent = Integer.parseInt(arguments.remove(0));
					case "-t", "--default-timezone" -> {
						String value = arguments.remove(0);
						timezone = Timestamps.offset(value).orElseThrow(() -> new IllegalArgumentException(
								"--default-timezone must be Z or an offset between -14:00 and +14:00, found " + value));
					}
					case "--format" -> format = arguments.remove(0);
					case "-q", "--quiet" -> quiet = true;
					case "-h", "--help" -> {
						usage(System.out);
						return;
					}
					default -> {
						if (argument.startsWith("-") || url != null) {
							throw new IllegalArgumentException("Unknown argument: " + argument);
						}
						url = argument;
					}
				}
			}
			if (url == null) {
				throw new IllegalArgumentException("The URL of the LDES is required");
			}
		} catch (RuntimeException e) {
			err.println(e.getMessage());
			usage(err);
			System.exit(2);
			return;
		}

		Lang lang = switch (format) {
			case "trig", "trig-messages" -> Lang.TRIG;
			case "nquads", "n-quads" -> Lang.NQUADS;
			case "jelly" -> JellySupport.lang();
			default -> {
				err.println("Unknown format: " + format + " (use trig, nquads or jelly)");
				System.exit(2);
				throw new IllegalStateException();
			}
		};

		LdesClient.Builder builder = LdesClient.create(url).follow(follow).startFresh(startFresh).after(after).before(before)
				.pollInterval(pollInterval).concurrentFetches(concurrent).defaultTimezone(timezone).listener(new LdesListener() {
					@Override
					public void onContext(LdesContext context) {
						err.println("LDES " + context.streamId() + " (root page " + context.rootPage() + ", order " + context.mode()
								+ ")");
					}

					@Override
					public void onRunFinished(long emitted) {
						err.println("Synchronization run finished: " + emitted + " new members");
					}
				});
		if (save != null) {
			builder.bookmarks(new FileBookmarkStore(save));
		}
		OutputStream out = new BufferedOutputStream(System.out, 1 << 16);
		StreamRDFMessages writer = quiet ? null : RDFMessagesWriter.create().lang(lang).build(out);
		if (writer != null) {
			writer.start();
		}
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			// Ctrl-C: the bookmark is saved by close(), the output is flushed
			if (writer != null) {
				try {
					writer.finish();
				} catch (RuntimeException e) {
					// the output may be gone
				}
			}
		}));
		long count = 0;
		try (LdesClient client = builder.build()) {
			for (LdesMember member : client) {
				count++;
				if (writer != null) {
					member.message().quads().forEachRemaining(writer::quad);
					writer.message();
				}
			}
		} catch (RuntimeException e) {
			err.println("Error: " + e.getMessage());
			if (writer != null) {
				writer.finish();
			}
			System.exit(1);
		}
		if (writer != null) {
			writer.finish();
		}
		err.println(count + " members");
	}

	private static Instant instant(String value) {
		return OffsetDateTime.parse(value).toInstant();
	}

	private static void usage(PrintStream out) {
		out.println("""
				Usage: ldes-client <url> [options]

				Replicates an LDES 2.0 and writes its members to standard output as an RDF Message log (one message per member).

				  -f, --follow                   Keep polling for new members after catching up.
				  -s, --save <dir>               Save the bookmark in this directory, to resume and synchronize later.
				      --start-fresh              Discard the saved bookmark and read everything again.
				      --after <dateTime>         Only members after this xsd:dateTime (exclusive).
				      --before <dateTime>        Only members before this xsd:dateTime (exclusive).
				      --poll-interval <ms>       Time between runs when following (default 1000); ldes:pollingInterval wins.
				      --concurrent <n>           Pages requested at the same time (default 10).
				  -t, --default-timezone <tz>    Timezone of timestamps without one (e.g. +01:00); ldes:defaultTimezone wins.
				      --format <format>          trig (default), nquads or jelly.
				  -q, --quiet                    Do not write members.
				  -h, --help                     This text.""");
	}
}
