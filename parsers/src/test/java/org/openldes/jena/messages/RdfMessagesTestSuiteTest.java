package org.openldes.jena.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.graph.Node;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.util.IsoMatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openldes.jena.messages.jelly.JellySupport;

/**
 * Runs the vendored <a href="https://w3c-cg.github.io/rsp/spec/messages-tests">RDF Messages test suite</a>. The suite is
 * a prose document: the cases are extracted from its Bikeshed source, see {@code src/test/resources/rdf-messages-tests}.
 * The results are written as an EARL report and a Markdown summary into the directory of the {@code rdfmessages.reports}
 * system property (the module's {@code target} directory in the Maven build).
 */
class RdfMessagesTestSuiteTest {

	private static final String SPEC = "https://w3c-cg.github.io/rsp/spec/messages-tests";
	private static final Path FIXTURES = Path.of("src/test/resources/rdf-messages-tests");
	private static final Pattern HEADING = Pattern.compile("^(#{2,3}) (.*?) #+ \\{#([^}]+)}\\s*$");
	private static final Pattern MESSAGE_LINE = Pattern.compile("^Message \\d+:$");
	private static final Pattern NOT_LINE_COMPATIBLE = Pattern.compile("@|^\\s*(PREFIX|BASE)\\b", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
	private static final List<Lang> LINE_FORMATS = List.of(Lang.NTRIPLES, Lang.NQUADS);

	private record Block(String label, String lang, String text) {
	}

	private record Section(String id, String title, int level, String group, List<Block> blocks) {
	}

	/** kind: parse, error, serialize, skip */
	private record Case(String id, String title, String kind, String input, String expected, String reason, boolean xml) {
	}

	private record Result(String id, String title, String status, String detail) {
	}

	private static final List<Result> RESULTS = new ArrayList<>();
	private static JsonObject upstream;

	// ---- Reading the suite

	private static List<Section> readSections(String source) {
		List<Section> sections = new ArrayList<>();
		String group = null;
		Section current = null;
		String label = null;
		String fenceLang = null;
		String fenceLabel = null;
		List<String> fenceLines = null;
		for (String line : source.split("\n", -1)) {
			if (fenceLines != null) {
				if (line.startsWith("```")) {
					current.blocks().add(new Block(fenceLabel, fenceLang, String.join("\n", fenceLines)));
					fenceLines = null;
				} else {
					fenceLines.add(line);
				}
				continue;
			}
			Matcher heading = HEADING.matcher(line);
			if (heading.matches()) {
				int level = heading.group(1).length();
				if (level == 2) {
					group = heading.group(2);
				}
				current = new Section(heading.group(3), heading.group(2), level, group, new ArrayList<>());
				sections.add(current);
			} else if (line.startsWith("```") && current != null) {
				fenceLang = line.substring(3).trim();
				fenceLabel = label;
				fenceLines = new ArrayList<>();
			} else if (!line.isBlank()) {
				label = line.trim();
			}
		}
		return sections;
	}

	private static List<Case> discoverCases(String source) {
		List<Case> cases = new ArrayList<>();
		for (Section section : readSections(source)) {
			String group = section.group() == null ? "" : section.group();
			boolean skippedGroup = group.contains("Discovery") || group.contains("NDJSON-LD");
			if (section.level() != 3 && !skippedGroup) {
				continue;
			}
			if (section.level() != 3) {
				continue;
			}
			List<Block> blocks = section.blocks();
			if (group.contains("Discovery")) {
				cases.add(new Case(section.id(), section.title(), "skip", null, null,
						"protocol adapter test: it does not apply to RDF syntax parsers and serializers", false));
			} else if (group.contains("NDJSON-LD")) {
				cases.add(new Case(section.id(), section.title(), "skip", null, null,
						"NDJSON-LD is a different serialization, which is not supported", false));
			} else if (group.startsWith("Parsing Tests for Turtle") && !blocks.isEmpty()) {
				cases.add(new Case(section.id(), section.title(), "parse", find(blocks, "Input:"), find(blocks, "Expected"),
						null, false));
			} else if (group.startsWith("Parsing Tests for RDF/XML") && !blocks.isEmpty()) {
				cases.add(new Case(section.id(), section.title(), "parse", find(blocks, "Input:"), find(blocks, "Expected"),
						null, true));
			} else if (group.startsWith("Error Tests") && !blocks.isEmpty()) {
				Block input = blocks.stream().filter(b -> b.label().startsWith("Input:")).findFirst().orElseThrow();
				cases.add(new Case(section.id(), section.title(), "error", input.text(), null, null,
						"xml".equals(input.lang()) || section.title().contains("RDF/XML")));
			} else if (group.startsWith("Serialization Tests")) {
				for (int i = 0; i + 1 < blocks.size(); i += 2) {
					cases.add(new Case("serialization-round-trip-" + (i / 2 + 1), blocks.get(i).label().replaceAll(":$", ""),
							"serialize", blocks.get(i).text(), blocks.get(i + 1).text(), null, false));
				}
			}
		}
		return cases;
	}

	private static String find(List<Block> blocks, String labelPrefix) {
		return blocks.stream().filter(b -> b.label() != null && b.label().startsWith(labelPrefix)).findFirst()
				.map(Block::text).orElseThrow(() -> new IllegalStateException("No block " + labelPrefix));
	}

	/** The "Message N:" listings of N-Quads; null when the expectation is prose. */
	private static List<DatasetGraph> parseExpectedMessages(String text) {
		List<List<String>> messages = new ArrayList<>();
		List<String> current = null;
		for (String line : text.split("\n")) {
			String trimmed = line.trim();
			if (MESSAGE_LINE.matcher(trimmed).matches()) {
				current = new ArrayList<>();
				messages.add(current);
			} else if (trimmed.isEmpty()) {
				continue;
			} else if (current != null && line.startsWith(" ") && trimmed.startsWith("<")) {
				current.add(trimmed);
			} else if (current != null && (trimmed.equals("empty") || trimmed.equals("(empty)"))) {
				continue;
			} else if (trimmed.equals("No messages.")) {
				return List.of();
			} else {
				return null;
			}
		}
		List<DatasetGraph> result = new ArrayList<>();
		for (List<String> lines : messages) {
			result.add(RDFParser.create().fromString(String.join("\n", lines)).lang(Lang.NQUADS).toDatasetGraph());
		}
		return result;
	}

	// ---- Running the cases

	private static List<Lang> formatsFor(Case testCase) {
		if (testCase.xml()) {
			return List.of(Lang.RDFXML);
		}
		if (testCase.title().contains("N-Quads")) {
			return List.of(Lang.NQUADS);
		}
		if (testCase.input().contains("{")) {
			return List.of(Lang.TRIG);
		}
		boolean lineCompatible = !NOT_LINE_COMPATIBLE.matcher(testCase.input()).find();
		List<Lang> formats = new ArrayList<>(List.of(Lang.TURTLE, Lang.TRIG));
		if (testCase.kind().equals("error") || lineCompatible) {
			formats.addAll(LINE_FORMATS);
		}
		return formats;
	}

	private static List<RdfMessage> parsePush(String input, Lang lang) {
		return RDFMessagesParser.create().fromString(input).lang(lang)
				.errorHandler(ErrorHandlerFactory.errorHandlerNoLogging).toList();
	}

	private static List<RdfMessage> parsePull(String input, Lang lang) {
		try (MessageLog log = RDFMessagesParser.create().source(TestData.byteAtATime(input)).lang(lang)
				.bufferedMessages(1).errorHandler(ErrorHandlerFactory.errorHandlerNoLogging).iterator()) {
			return log.toList();
		}
	}

	private static void assertSameMessages(List<RdfMessage> actual, List<DatasetGraph> expected, String label) {
		if (actual.size() != expected.size()) {
			throw new AssertionError(label + ": expected " + expected.size() + " messages, got " + actual.size());
		}
		for (int i = 0; i < expected.size(); i++) {
			if (!IsoMatcher.isomorphic(actual.get(i).dataset(), expected.get(i))) {
				throw new AssertionError(label + ": message " + (i + 1) + " differs from the expected quads");
			}
		}
	}

	/** Checks for expectations that the suite only describes in prose. */
	private static final Map<String, Consumer<List<RdfMessage>>> SEMANTIC_CHECKS = Map.of(
			"blank-node-labels-are-scoped-per-message", RdfMessagesTestSuiteTest::distinctBlankNodes,
			"rdf-xml-blank-nodes-scoped-per-message", RdfMessagesTestSuiteTest::distinctBlankNodes,
			"rdf-xml-blank-node-reused-within-message", messages -> {
				if (messages.size() != 1 || messages.get(0).size() != 2) {
					throw new AssertionError("expected one message with two quads");
				}
				List<Node> subjects = messages.get(0).stream().map(q -> q.getSubject()).toList();
				if (!subjects.get(0).isBlank() || !subjects.get(0).equals(subjects.get(1))) {
					throw new AssertionError("the two blank nodes must be the same RDF term");
				}
			});

	private static void distinctBlankNodes(List<RdfMessage> messages) {
		if (messages.size() != 2 || messages.stream().anyMatch(m -> m.size() != 1)) {
			throw new AssertionError("expected two messages with one quad each");
		}
		Node a = messages.get(0).quads().next().getSubject();
		Node b = messages.get(1).quads().next().getSubject();
		if (!a.isBlank() || !b.isBlank()) {
			throw new AssertionError("expected blank node subjects");
		}
		if (a.equals(b)) {
			throw new AssertionError("the two blank nodes must not be the same RDF term");
		}
	}

	private static void runParseCase(Case testCase) {
		List<DatasetGraph> expected = parseExpectedMessages(testCase.expected());
		Consumer<List<RdfMessage>> check = SEMANTIC_CHECKS.get(testCase.id());
		if (expected == null && check == null) {
			throw new AssertionError("the expected result is prose and no semantic check is registered for this case");
		}
		for (Lang lang : formatsFor(testCase)) {
			for (String mode : List.of("push", "pull")) {
				List<RdfMessage> messages = mode.equals("push") ? parsePush(testCase.input(), lang)
						: parsePull(testCase.input(), lang);
				String label = lang.getName() + " (" + mode + ")";
				if (expected != null) {
					assertSameMessages(messages, expected, label);
				} else {
					try {
						check.accept(messages);
					} catch (AssertionError e) {
						throw new AssertionError(label + ": " + e.getMessage(), e);
					}
				}
			}
		}
	}

	private static void runErrorCase(Case testCase) {
		if (testCase.id().equals("rdf-xml-log-without-message-support")) {
			// Not the message log reader, but a parser without message support
			expectError("plain RDF/XML parser", () -> RDFParser.create().fromString(testCase.input()).lang(Lang.RDFXML)
					.errorHandler(ErrorHandlerFactory.errorHandlerNoLogging).parse(StreamRDFLib.sinkNull()));
			return;
		}
		for (Lang lang : formatsFor(testCase)) {
			expectError(lang.getName() + " (push)", () -> parsePush(testCase.input(), lang));
			expectError(lang.getName() + " (pull)", () -> parsePull(testCase.input(), lang));
		}
	}

	private static void expectError(String label, Runnable action) {
		try {
			action.run();
		} catch (RiotException | IllegalArgumentException e) {
			return;
		}
		throw new AssertionError(label + ": expected a parse error");
	}

	private static void runSerializeCase(Case testCase) {
		List<DatasetGraph> input = parseExpectedMessages(testCase.input());
		List<DatasetGraph> expected = parseExpectedMessages(testCase.expected());
		List<RdfMessage> messages = input.stream().map(RdfMessage::of).toList();
		boolean hasGraphs = input.stream().anyMatch(dsg -> dsg.listGraphNodes().hasNext());
		List<Lang> formats = new ArrayList<>(hasGraphs ? List.of(Lang.TRIG, Lang.NQUADS)
				: List.of(Lang.TURTLE, Lang.TRIG, Lang.NTRIPLES, Lang.NQUADS));
		for (Lang lang : formats) {
			String text = RDFMessagesWriter.create().lang(lang).writeString(messages);
			assertSameMessages(parsePush(text, lang), expected, lang.getName() + " (push)");
			assertSameMessages(parsePull(text, lang), expected, lang.getName() + " (pull)");
		}
		if (JellySupport.isAvailable()) {
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			RDFMessages.write(bytes, JellySupport.lang(), messages);
			List<RdfMessage> back = RDFMessagesParser.create().source(new ByteArrayInputStream(bytes.toByteArray()))
					.lang(JellySupport.lang()).toList();
			assertSameMessages(back, expected, "Jelly");
		}
	}

	// ---- The dynamic tests

	private static void verifyFixture() {
		try {
			upstream = JSON.parse(Files.readString(FIXTURES.resolve("UPSTREAM.json")));
			for (String file : List.of("messages-tests.bs", "messages.bs")) {
				String expected = upstream.get("files").getAsObject().get("spec/" + file).getAsString().value();
				byte[] bytes = Files.readAllBytes(FIXTURES.resolve(file));
				String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
				assertThat(actual).as("%s matches the pinned upstream revision", file).isEqualTo(expected);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@TestFactory
	Collection<DynamicTest> rdfMessagesTestSuite() throws IOException {
		verifyFixture();
		String source = Files.readString(FIXTURES.resolve("messages-tests.bs"));
		List<Case> cases = discoverCases(source);
		assertThat(cases).as("cases extracted from the suite").hasSizeGreaterThan(20);
		List<DynamicTest> tests = new ArrayList<>();
		for (Case testCase : cases) {
			tests.add(DynamicTest.dynamicTest(testCase.title() + " [" + testCase.kind() + "]", () -> {
				if (testCase.kind().equals("skip")) {
					RESULTS.add(new Result(testCase.id(), testCase.title(), "skipped", testCase.reason()));
					Assumptions.abort(testCase.reason());
				}
				try {
					switch (testCase.kind()) {
						case "parse" -> runParseCase(testCase);
						case "error" -> runErrorCase(testCase);
						case "serialize" -> runSerializeCase(testCase);
						default -> throw new IllegalStateException(testCase.kind());
					}
					RESULTS.add(new Result(testCase.id(), testCase.title(), "passed", ""));
				} catch (AssertionError | RuntimeException e) {
					RESULTS.add(new Result(testCase.id(), testCase.title(), "failed", String.valueOf(e.getMessage())));
					throw e;
				}
			}));
		}
		return tests;
	}

	@AfterAll
	static void writeReports() throws IOException {
		String dir = System.getProperty("rdfmessages.reports", "target");
		Path reports = Path.of(dir);
		Files.createDirectories(reports);
		String commit = upstream.get("commit").getAsString().value();
		String repository = upstream.get("repository").getAsString().value();
		long passed = RESULTS.stream().filter(r -> r.status().equals("passed")).count();
		long failed = RESULTS.stream().filter(r -> r.status().equals("failed")).count();
		long skipped = RESULTS.stream().filter(r -> r.status().equals("skipped")).count();

		StringBuilder md = new StringBuilder();
		md.append("# RDF Messages test suite\n\nSuite: ").append(repository).append(" @ `").append(commit, 0, 10)
				.append("`, Apache Jena ").append(org.apache.jena.atlas.lib.Version.versionForClass(RDFParser.class).orElse("?"))
				.append("\n\n**").append(passed).append(" passed, ").append(failed).append(" failed, ").append(skipped)
				.append(" skipped** of ").append(RESULTS.size()).append(" cases.\n\n| Result | Case | Detail |\n| --- | --- | --- |\n");
		for (Result result : RESULTS) {
			md.append("| ").append(result.status()).append(" | ").append(result.title()).append(" | ")
					.append(result.detail().replace("|", "\\|").replace("\n", " ")).append(" |\n");
		}
		Files.writeString(reports.resolve("rdf-messages-conformance.md"), md.toString(), StandardCharsets.UTF_8);

		StringBuilder earl = new StringBuilder();
		String subject = "<https://github.com/pietercolpaert/jena-messages>";
		earl.append("@prefix earl: <http://www.w3.org/ns/earl#> .\n@prefix dc: <http://purl.org/dc/terms/> .\n")
				.append("@prefix doap: <http://usefulinc.com/ns/doap#> .\n@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\n")
				.append(subject).append(" a earl:Software, earl:TestSubject, doap:Project ;\n")
				.append("  doap:name \"jena-messages\" ;\n  dc:description \"RDF Messages test suite revision ").append(commit)
				.append(" of ").append(repository).append("\" .\n");
		String now = Instant.now().toString();
		for (Result result : RESULTS) {
			String outcome = switch (result.status()) {
				case "passed" -> "earl:passed";
				case "failed" -> "earl:failed";
				default -> "earl:inapplicable";
			};
			earl.append("\n[] a earl:Assertion ;\n  earl:subject ").append(subject).append(" ;\n  earl:test <").append(SPEC)
					.append('#').append(result.id()).append("> ;\n  earl:result [ a earl:TestResult ; earl:outcome ").append(outcome)
					.append(" ;\n    dc:date \"").append(now).append("\"^^xsd:dateTime ] ;\n  earl:mode earl:automatic .\n");
		}
		Files.writeString(reports.resolve("rdf-messages-earl-report.ttl"), earl.toString(), StandardCharsets.UTF_8);
	}

	/** Keeps the helper used by the pull mode from being flagged as unused. */
	static InputStream unused() {
		return new ByteArrayInputStream(new byte[0]);
	}
}
