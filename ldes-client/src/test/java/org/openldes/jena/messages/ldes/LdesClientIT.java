package org.openldes.jena.messages.ldes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.sparql.core.Quad;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openldes.jena.messages.RDFMessages;
import org.openldes.jena.messages.RdfMessage;
import org.openldes.jena.messages.jelly.JellySupport;

/**
 * Tests of the LDES 2.0 client against a local mock server, following the structure of the specification. A port of the
 * test suite of the JavaScript ldes-client.
 */
class LdesClientIT {

	private static MockLdesServer server;

	@TempDir
	static Path stateDir;

	@BeforeAll
	static void start() throws IOException {
		server = new MockLdesServer();
	}

	@AfterAll
	static void stop() {
		server.close();
	}

	@BeforeEach
	void reset() {
		server.reset();
	}

	private static String root() {
		return server.url("/root");
	}

	private static List<LdesMember> collect(Consumer<LdesClient.Builder> configure) {
		LdesClient.Builder builder = LdesClient.create(root());
		configure.accept(builder);
		List<LdesMember> members = new ArrayList<>();
		try (LdesClient client = builder.build()) {
			client.forEach(members::add);
		}
		return members;
	}

	private static List<LdesMember> collect() {
		return collect(b -> {
		});
	}

	private static List<String> ids(List<LdesMember> members) {
		return members.stream().map(m -> m.id().getURI().replace("http://example.org/", "")).toList();
	}

	private static String record(String name, String time) {
		return record(name, time, "");
	}

	private static String record(String name, String time, String extra) {
		return "ex:" + name + " a ex:Record ; dcterms:created \"" + time + "\"^^xsd:dateTime " + extra + ".";
	}

	private static final String DESCRIPTION = "ex:ES a ldes:EventStream ; ldes:timestampPath dcterms:created ; ldes:memberType ex:Record .";

	private static String relation(String path, String bounds) {
		return "[ ldes:page <" + server.url(path) + "> " + bounds + " ]";
	}

	@Nested
	class TimestampOrdered {

		@Test
		void membersOfAllPagesAreEmittedInAscendingOrderAsAMergeOfTheLogs() {
			server.trig("/root", DESCRIPTION + "\n<> ldes:relation "
					+ relation("/a", "; ldes:timeFrom \"2026-01-01T00:00:00Z\"^^xsd:dateTime ; ldes:timeUntil \"2026-01-10T00:00:00Z\"^^xsd:dateTime") + " , "
					+ relation("/b", "; ldes:timeFrom \"2026-01-02T00:00:00Z\"^^xsd:dateTime ; ldes:timeUntil \"2026-01-10T00:00:00Z\"^^xsd:dateTime") + " .");
			server.trig("/a", record("a1", "2026-01-01T00:00:00Z"), record("a2", "2026-01-03T00:00:00Z"), record("a3", "2026-01-05T00:00:00Z"));
			server.trig("/b", record("b1", "2026-01-02T00:00:00Z"), record("b2", "2026-01-04T00:00:00Z"));
			List<LdesMember> members = collect();
			assertThat(ids(members)).containsExactly("a1", "b1", "a2", "b2", "a3");
			assertThat(members).allMatch(m -> !m.message().isEmpty());
			assertThat(members.get(0).timestamp()).contains(Instant.parse("2026-01-01T00:00:00Z"));
			assertThat(members.get(0).type()).contains(NodeFactory.createURI("http://example.org/Record"));
		}

		@Test
		void theContextInformationOfTheLdesIsReported() {
			server.trig("/root", DESCRIPTION + "\nex:ES ldes:pollingInterval 60 ; ldes:retentionPolicy [ ldes:fullLogDuration \"P1Y\"^^xsd:duration ] .\n<> ldes:relation "
					+ relation("/a", "") + " .");
			server.trig("/a", record("a1", "2026-01-01T00:00:00Z"));
			AtomicReference<LdesContext> seen = new AtomicReference<>();
			List<LdesMember> members = collect(b -> b.listener(new LdesListener() {
				@Override
				public void onContext(LdesContext context) {
					seen.set(context);
				}
			}));
			LdesContext context = seen.get();
			assertThat(context.ldesVersion()).isEqualTo(2);
			assertThat(context.timestampPath().toString()).contains("http://purl.org/dc/terms/created");
			assertThat(context.pollingInterval()).contains(Duration.ofSeconds(60));
			assertThat(context.retentionPolicies()).hasSize(1);
			assertThat(context.streamId().getURI()).isEqualTo("http://example.org/ES");
			assertThat(members).hasSize(1);
		}

		@Test
		void theRootPageCanContainMembersItself() {
			server.trig("/root", DESCRIPTION, record("r1", "2026-01-01T00:00:00Z"), record("r2", "2026-01-02T00:00:00Z"));
			assertThat(ids(collect())).containsExactly("r1", "r2");
		}

		@Test
		void membersWithTheSameTimestampAreOrderedByTheSequencePath() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:timestampPath dcterms:created ; ldes:sequencePath ex:seq ; ldes:memberType ex:Record .\n<> ldes:relation "
					+ relation("/a", "") + " .");
			String t = "2026-01-01T00:00:00Z";
			server.trig("/a", record("a1", t, "; ex:seq 2 "), record("a2", t, "; ex:seq 10 "));
			List<LdesMember> members = collect();
			assertThat(ids(members)).containsExactly("a1", "a2");
			assertThat(members.get(1).sequence().orElseThrow().getLiteralLexicalForm()).isEqualTo("10");
		}

		@Test
		void aRelationWithOnlyALowerBoundAndARelationWithAnUnknownTypeAreFollowed() {
			server.trig("/root", DESCRIPTION + "\n<> ldes:relation [ a tree:PrefixRelation ; tree:path ex:name ; tree:value \"a\" ; ldes:page <"
					+ server.url("/a") + "> ] , " + relation("/b", "; ldes:timeFrom \"2026-02-01T00:00:00Z\"^^xsd:dateTime") + " .");
			server.trig("/a", record("a1", "2026-01-01T00:00:00Z"));
			server.trig("/b", record("b1", "2026-02-02T00:00:00Z"));
			assertThat(ids(collect())).containsExactly("a1", "b1");
		}

		@Test
		void aPageCanBeAMemberLessPageThatOnlyLinksToOtherPages() {
			server.trig("/root", DESCRIPTION + "\n<> ldes:relation " + relation("/index", "") + " .");
			server.trig("/index", "<> ldes:relation " + relation("/a", "") + " .");
			server.trig("/a", record("a1", "2026-01-01T00:00:00Z"));
			assertThat(ids(collect())).containsExactly("a1");
		}

		@Test
		void aPageInTurtleIsReadAsOnlyADescriptionMessage() {
			server.set("/root", MockLdesServer.Page.of(MockLdesServer.PREFIXES + DESCRIPTION + "\n<" + root() + "> ldes:relation "
					+ relation("/a", "") + " .", "text/turtle"));
			server.trig("/a", record("a1", "2026-01-01T00:00:00Z"));
			assertThat(ids(collect())).containsExactly("a1");
		}

		@Test
		void a410GoneIsAPageWithoutMembersAndRelations() {
			server.trig("/root", DESCRIPTION + "\n<> ldes:relation " + relation("/gone", "") + " , " + relation("/a", "") + " .");
			server.set("/gone", MockLdesServer.Page.of("gone", "text/plain").withStatus(410));
			server.trig("/a", record("a1", "2026-01-01T00:00:00Z"));
			assertThat(ids(collect())).containsExactly("a1");
		}

		@Test
		void retryableStatusCodesAreRetried() {
			server.trig("/root", DESCRIPTION + "\n<> ldes:relation " + relation("/flaky", "") + " .");
			server.trig("/flaky", record("a1", "2026-01-01T00:00:00Z"));
			MockLdesServer.Page good = server.pages.get("/flaky");
			server.set("/flaky", good.withStatus(503));
			Thread.ofVirtual().start(() -> {
				try {
					Thread.sleep(300);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				server.set("/flaky", good);
			});
			assertThat(ids(collect())).containsExactly("a1");
			assertThat(server.requests.stream().filter("/flaky"::equals).count()).isGreaterThan(1);
		}
	}

	@Nested
	class SequenceOrdered {

		@Test
		void membersAreOrderedByAnXsdDateTimeSequenceAlsoWithoutTimezoneAndWithMicroseconds() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberGraph true ; ldes:sequencePath ex:at .\n<> ldes:relation "
					+ relation("/a", "; ldes:sequenceFrom \"2023-01-01T00:00:00.000001\"^^xsd:dateTime ; ldes:sequenceUntil \"2023-01-02T00:00:00\"^^xsd:dateTime") + " .");
			server.trig("/a", "ex:g1 ex:at \"2023-01-01T00:00:00.000001\"^^xsd:dateTime . ex:g1 { ex:x ex:p 1 . }",
					"ex:g2 ex:at \"2023-01-01T00:00:00.000002\"^^xsd:dateTime . ex:g2 { ex:x ex:p 2 . }");
			MemoryBookmarkStore state = new MemoryBookmarkStore();
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("g1", "g2");

			server.requests.clear();
			server.trig("/a", "ex:g1 ex:at \"2023-01-01T00:00:00.000001\"^^xsd:dateTime . ex:g1 { ex:x ex:p 1 . }",
					"ex:g2 ex:at \"2023-01-01T00:00:00.000002\"^^xsd:dateTime . ex:g2 { ex:x ex:p 2 . }",
					"ex:g3 ex:at \"2023-01-01T00:00:00.000003\"^^xsd:dateTime . ex:g3 { ex:x ex:p 3 . }");
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("g3");
		}

		@Test
		void membersThatAreNotInAscendingOrderOfTheirSequenceIsAnError() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberGraph true ; ldes:sequencePath ex:at .\n<> ldes:relation " + relation("/a", "") + " .");
			server.trig("/a", "ex:g2 ex:at \"2023-01-01T00:00:00.000002\"^^xsd:dateTime . ex:g2 { ex:x ex:p 2 . }",
					"ex:g1 ex:at \"2023-01-01T00:00:00.000001\"^^xsd:dateTime . ex:g1 { ex:x ex:p 1 . }");
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("ordering");
		}
	}

	@Nested
	class ImplicitOrder {

		@Test
		void pagesAreOrderedByTheirPageNumberAndMembersByTheirIndex() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberGraph true .\n<> ldes:relation "
					+ relation("/p1", "; ldes:pageNumber 1") + " , " + relation("/p0", "; ldes:pageNumber 0") + " .");
			server.trig("/p0", "ex:m0 { ex:m0 ex:value 0 . }", "ex:m1 { ex:m1 ex:value 1 . }");
			server.trig("/p1", "ex:m2 { ex:m2 ex:value 2 . }");
			List<LdesMember> members = collect();
			assertThat(ids(members)).containsExactly("m0", "m1", "m2");
			// The member is the message: the quads keep their graph
			assertThat(members.get(0).message().graphNames()).containsExactly(NodeFactory.createURI("http://example.org/m0"));
		}

		@Test
		void aPageOfWhichTheNumberCannotBeDeterminedIsAnError() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberGraph true .\n<> ldes:relation " + relation("/p0", "") + " .");
			server.trig("/p0", "ex:m0 { ex:m0 ex:value 0 . }");
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("page number");
		}

		@Test
		void aClientSkipsThePagesBeforeTheBookmarkAndDoesNotNeedToRememberPages() {
			String rootPage = "ex:ES a ldes:EventStream ; ldes:memberGraph true .\n<> ldes:relation " + relation("/p0", "; ldes:pageNumber 0")
					+ " , " + relation("/p1", "; ldes:pageNumber 1") + " , " + relation("/p2", "; ldes:pageNumber 2") + " .";
			server.trig("/root", rootPage);
			server.trig("/p0", "ex:m0 { ex:m0 ex:value 0 . }");
			server.trig("/p1", "ex:m1 { ex:m1 ex:value 1 . }");
			server.trig("/p2", "ex:m2 { ex:m2 ex:value 2 . }");
			MemoryBookmarkStore state = new MemoryBookmarkStore();
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("m0", "m1", "m2");

			server.requests.clear();
			server.trig("/root", rootPage.replace("ldes:pageNumber 2 ]", "ldes:pageNumber 2 ] , " + relation("/p3", "; ldes:pageNumber 3")));
			server.trig("/p2", "ex:m2 { ex:m2 ex:value 2 . }", "ex:m2b { ex:m2b ex:value 2 . }");
			server.trig("/p3", "ex:m3 { ex:m3 ex:value 3 . }");
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("m2b", "m3");
			assertThat(server.requests).containsExactlyInAnyOrder("/root", "/p2", "/p3");
		}
	}

	@Nested
	class MembershipRules {
		private String relation() {
			return "<> ldes:relation " + LdesClientIT.relation("/a", "; ldes:pageNumber 0") + " .";
		}

		@Test
		void directMembership() {
			server.trig("/root", "ex:ES a ldes:EventStream . " + relation());
			server.trig("/a", "ex:ES ldes:member ex:m0 . ex:m0 ex:value 0 ; ex:detail [ ex:deeper 1 ] .");
			List<LdesMember> members = collect();
			assertThat(ids(members)).containsExactly("m0");
			// The member is the full message, including the triple that identifies the member
			assertThat(members.get(0).message().size()).isEqualTo(4);
		}

		@Test
		void indirectMembershipByType() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberType ex:Record . " + relation());
			server.trig("/a", "ex:m0 a ex:Record ; ex:detail _:b .\n_:b ex:value 5 .\nex:graph { _:c ex:value 6 . }");
			List<LdesMember> members = collect();
			assertThat(ids(members)).containsExactly("m0");
			assertThat(members.get(0).message().size()).isEqualTo(4);
		}

		@Test
		void indirectMembershipByShaclPropertyPath() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberPath ( ex:a ex:b ) . " + relation());
			server.trig("/a", "ex:m0 ex:a [ ex:b 1 ] . ex:other ex:c 2 .");
			assertThat(ids(collect())).containsExactly("m0");
		}

		@Test
		void aMessageInWhichTheMemberCannotBeIdentifiedIsAnError() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberType ex:Record . " + relation());
			server.trig("/a", "ex:m0 a ex:Record . ex:m1 a ex:Record .");
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("exactly one");
		}

		@Test
		void settingMoreThanOneIndirectMembershipPropertyIsAnError() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberType ex:Record ; ldes:memberGraph true . " + relation());
			server.trig("/a", "ex:m0 a ex:Record .");
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("more than one");
		}
	}

	@Nested
	class Errors {
		private final String record1 = record("a1", "2026-01-01T00:00:00Z");
		private final String description = DESCRIPTION + "\n<> ldes:relation " + relation("/a", "") + " .";

		@Test
		void anEmptyMessageIsAnError() {
			server.trig("/root", description);
			server.trig("/a", record1, "", record("a2", "2026-01-02T00:00:00Z"));
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("mpty message");
		}

		@Test
		void membersThatAreNotOrderedInAPageIsAnError() {
			server.trig("/root", description);
			server.trig("/a", record("a2", "2026-01-02T00:00:00Z"), record1);
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("ordering");
		}

		@Test
		void aTimestampThatIsNotAnXsdDateTimeIsAnError() {
			server.trig("/root", description);
			server.trig("/a", record("a1", "yesterday"));
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("xsd:dateTime");
		}

		@Test
		void aRelationWithASequenceBoundWithoutATimestampBoundIsAnError() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:timestampPath dcterms:created ; ldes:sequencePath ex:seq ; ldes:memberType ex:Record .\n<> ldes:relation "
					+ relation("/a", "; ldes:sequenceFrom 1") + " .");
			server.trig("/a", record1);
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("sequenceFrom");
		}

		@Test
		void aRelationMustHaveExactlyOneLdesPage() {
			server.trig("/root", DESCRIPTION + "\n<> ldes:relation [ ldes:timeFrom \"2026-01-01T00:00:00Z\"^^xsd:dateTime ] .");
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("exactly one ldes:page");
		}

		@Test
		void aRootPageWithoutADescriptionOfTheLdesIsAnError() {
			server.trig("/root", "<> ldes:relation " + relation("/a", "") + " .", record1);
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("does not describe an ldes:EventStream");
		}

		@Test
		void moreThanOneEventStreamInTheRootPageIsAnError() {
			server.trig("/root", "ex:ES a ldes:EventStream . ex:ES2 a ldes:EventStream .");
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("exactly one ldes:EventStream");
		}

		@Test
		void another4xxOr5xxStatusCodeIsAnError() {
			server.trig("/root", description);
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("404");
		}

		@Test
		void anLdes1PageIsAnError() {
			server.set("/root", MockLdesServer.Page.of(MockLdesServer.PREFIXES + "<" + root() + "#ES> a ldes:EventStream ; tree:view <" + root()
					+ "> ; tree:member ex:m0 .\nex:m0 dcterms:created \"2026-01-01T00:00:00Z\"^^xsd:dateTime .", "text/turtle"));
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("LDES 1.0");
		}
	}

	@Nested
	class JellyPages {
		private static final String EX = "http://example.org/";

		private byte[] jelly(List<List<Quad>> messages) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			RDFMessages.write(out, JellySupport.lang(), messages.stream().map(RdfMessage::of).toList());
			return out.toByteArray();
		}

		private Quad quad(String s, String p, String o, String g) {
			return Quad.create(NodeFactory.createURI(EX + g), NodeFactory.createURI(EX + s), NodeFactory.createURI(EX + p),
					o.startsWith("http") ? NodeFactory.createURI(o) : NodeFactory.createLiteralString(o));
		}

		@Test
		void aClientReadsAPageInJellyInWhichEveryFrameIsAMemberMessage() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberGraph true .\n<> ldes:relation " + relation("/page.jelly", "; ldes:pageNumber 0") + " .");
			server.set("/page.jelly", new MockLdesServer.Page(jelly(List.of(List.of(quad("m0", "value", "0", "m0"), quad("m0", "other", "x", "m0")),
					List.of(quad("m1", "value", "1", "m1")))), "application/x-jelly-rdf", 200, Map.of(), 0));
			List<LdesMember> members = collect();
			assertThat(ids(members)).containsExactly("m0", "m1");
			assertThat(members.get(0).message().size()).isEqualTo(2);
		}

		@Test
		void aClientReadsAGzippedJellyPageOfWhichTheMediaTypeIsNotHelpful() throws IOException {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberGraph true .\n<> ldes:relation " + relation("/page.jelly.gz", "; ldes:pageNumber 0") + " .");
			ByteArrayOutputStream gz = new ByteArrayOutputStream();
			try (var out = new java.util.zip.GZIPOutputStream(gz)) {
				out.write(jelly(List.of(List.of(quad("m0", "value", "0", "m0")))));
			}
			server.set("/page.jelly.gz", new MockLdesServer.Page(gz.toByteArray(), "application/octet-stream", 200, Map.of(), 0));
			assertThat(ids(collect())).containsExactly("m0");
		}

		@Test
		void anEmptyFrameIsAnEmptyMessageWhichIsAnError() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberGraph true .\n<> ldes:relation " + relation("/page.jelly", "; ldes:pageNumber 0") + " .");
			server.set("/page.jelly", new MockLdesServer.Page(jelly(List.of(List.of(quad("m0", "value", "0", "m0")), List.of(),
					List.of(quad("m1", "value", "1", "m1")))), "application/x-jelly-rdf", 200, Map.of(), 0));
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("mpty message");
		}
	}

	@Nested
	class OtherSerializations {
		@Test
		void aClientReadsPagesInNQuadsWithMessageDelimiters() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberGraph true .\n<> ldes:relation " + relation("/p0.nq", "; ldes:pageNumber 0") + " .");
			server.set("/p0.nq", MockLdesServer.Page.of("VERSION \"1.2-messages\"\n<http://example.org/m0> <http://example.org/value> \"0\" <http://example.org/m0> .\nMESSAGE\n"
					+ "<http://example.org/m1> <http://example.org/value> \"1\" <http://example.org/m1> .\n", "application/n-quads"));
			assertThat(ids(collect())).containsExactly("m0", "m1");
		}

		@Test
		void theVersionParameterOfTheMediaTypeAnnouncesTheMessages() {
			server.trig("/root", "ex:ES a ldes:EventStream ; ldes:memberGraph true .\n<> ldes:relation " + relation("/p0.nq", "; ldes:pageNumber 0") + " .");
			server.set("/p0.nq", MockLdesServer.Page.of("<http://example.org/m0> <http://example.org/value> \"0\" <http://example.org/m0> .\nMESSAGE\n"
					+ "<http://example.org/m1> <http://example.org/value> \"1\" <http://example.org/m1> .\n", "application/n-quads; version=1.2-messages"));
			assertThat(ids(collect())).containsExactly("m0", "m1");
		}
	}

	@Nested
	class StateManagement {
		private final String description = DESCRIPTION + "\n<> ldes:relation " + relation("/a", "") + " .";

		@Test
		void bookmarksEmitMembersSharingATimestampExactlyOnce() {
			for (BookmarkStore state : List.of(new MemoryBookmarkStore(), new FileBookmarkStore(stateDir.resolve("state-" + System.nanoTime())))) {
				server.trig("/root", description);
				server.trig("/a", record("a1", "2026-01-01T00:00:00Z"), record("a2", "2026-01-02T00:00:00Z"));
				assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("a1", "a2");
				server.trig("/a", record("a1", "2026-01-01T00:00:00Z"), record("a2", "2026-01-02T00:00:00Z"),
						record("a3", "2026-01-02T00:00:00Z"), record("a4", "2026-01-03T00:00:00Z"));
				assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("a3", "a4");
				assertThat(ids(collect(b -> b.bookmarks(state)))).isEmpty();
			}
		}

		@Test
		void theBookmarkRoundTripsThroughJson() {
			Bookmark bookmark = new Bookmark();
			bookmark.setKey(OrderingKey.ofTime(Instant.EPOCH, false));
			bookmark.members().addAll(List.of("a", "b"));
			bookmark.immutable().add("p");
			bookmark.pages().put("http://x/", new Bookmark.CachedPage("\"etag\"", "<http://x/s> <http://x/p> <http://x/o> .\n"));
			Bookmark back = Bookmark.fromJson(bookmark.toJson().toString());
			assertThat(back.key()).isEqualTo(bookmark.key());
			assertThat(back.members()).containsExactly("a", "b");
			assertThat(back.immutable()).containsExactly("p");
			assertThat(back.pages()).containsKey("http://x/");
		}

		@Test
		void manyMembersThatShareTheTimestampOfTheBookmarkAreEmittedOnceAndSkippedAfterARestart() {
			int n = 5000;
			String[] batch = IntStream.range(0, n).mapToObj(i -> record("b" + i, "2026-01-01T00:00:00Z")).toArray(String[]::new);
			server.trig("/root", description);
			server.trig("/a", batch);
			MemoryBookmarkStore state = new MemoryBookmarkStore();
			List<String> first = ids(collect(b -> b.bookmarks(state)));
			assertThat(first).hasSize(n);
			assertThat(first.stream().distinct().count()).isEqualTo(n);

			String[] more = new String[n + 2];
			System.arraycopy(batch, 0, more, 0, n);
			more[n] = record("late", "2026-01-01T00:00:00Z");
			more[n + 1] = record("next", "2026-01-02T00:00:00Z");
			server.trig("/a", more);
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("late", "next");
		}

		@Test
		void aFollowingClientEmitsMembersThatArePublishedLater() {
			server.trig("/root", description);
			server.trig("/a", record("a1", "2026-01-01T00:00:00Z"));
			List<String> seen = new ArrayList<>();
			try (LdesClient client = LdesClient.create(root()).follow(true).pollInterval(Duration.ofMillis(50)).build()) {
				for (LdesMember member : client) {
					seen.add(member.id().getURI().replace("http://example.org/", ""));
					if (seen.size() == 1) {
						server.trig("/a", record("a1", "2026-01-01T00:00:00Z"), record("a2", "2026-01-02T00:00:00Z"));
					} else {
						break;
					}
				}
			}
			assertThat(seen).containsExactly("a1", "a2");
		}

		@Test
		void pagesAreRevalidatedWithTheirETag() {
			server.trig("/root", description);
			server.trig("/a", record("a1", "2026-01-01T00:00:00Z"));
			server.set("/a", server.pages.get("/a").withHeaders(Map.of("ETag", "\"v1\"")));
			server.set("/root", server.pages.get("/root").withHeaders(Map.of("ETag", "\"r1\"")));
			MemoryBookmarkStore state = new MemoryBookmarkStore();
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("a1");
			server.requests.clear();
			// Nothing changed: both pages answer 304, and no member is emitted again
			assertThat(ids(collect(b -> b.bookmarks(state)))).isEmpty();
			assertThat(server.requests).containsExactly("/root", "/a");
			assertThat(state.load(root()).orElseThrow().pages()).containsKeys(root(), server.url("/a"));
		}
	}

	@Nested
	class TimestampsWithoutTimezone {
		private final String description = "ex:ES a ldes:EventStream ; ldes:timestampPath dcterms:created ; ldes:memberType ex:Record";
		private final String pages = "<> ldes:relation " + relation("/a", "") + " , " + relation("/b", "") + " .";

		@Test
		void literalsWithoutATimezoneInTheSameLdesAreComparedAsIfTheyHaveTheSameTimezone() {
			server.trig("/root", description + " . " + pages);
			server.trig("/a", record("a1", "2026-01-01T00:30:00"), record("a2", "2026-01-03T00:00:00"));
			server.trig("/b", record("b1", "2026-01-01T00:45:00"), record("b2", "2026-01-02T00:00:00"));
			assertThat(ids(collect())).containsExactly("a1", "b1", "b2", "a2");
		}

		@Test
		void withoutADefaultTimezoneAMixIsOrderedByTheEarliestInstant() {
			server.trig("/root", description + " . " + pages);
			server.trig("/a", record("a1", "2026-01-01T00:30:00"));
			server.trig("/b", record("b1", "2026-01-01T00:00:00Z"));
			assertThat(ids(collect())).containsExactly("a1", "b1");
		}

		@Test
		void theLdesDefaultTimezoneIsTheTimezoneOfLiteralsWithoutATimezone() {
			server.trig("/root", description + " ; ldes:defaultTimezone \"-05:00\" . " + pages);
			server.trig("/a", record("a1", "2026-01-01T00:30:00"));
			server.trig("/b", record("b1", "2026-01-01T00:00:00Z"));
			List<LdesMember> members = collect();
			assertThat(ids(members)).containsExactly("b1", "a1");
			assertThat(members.get(1).timestamp()).contains(Instant.parse("2026-01-01T05:30:00Z"));
			// The timezone is added to the literal of the emitted member
			assertThat(members.get(1).message().stream().filter(q -> q.getPredicate().getURI().endsWith("created")).findFirst()
					.orElseThrow().getObject().getLiteralLexicalForm()).isEqualTo("2026-01-01T00:30:00-05:00");
		}

		@Test
		void aConfiguredDefaultTimezoneIsUsedWhenTheLdesHasNone() {
			server.trig("/root", description + " . " + pages);
			server.trig("/a", record("a1", "2026-01-01T00:30:00"));
			server.trig("/b", record("b1", "2026-01-01T00:00:00Z"));
			assertThat(ids(collect(b -> b.defaultTimezone(ZoneOffset.ofHours(1))))).containsExactly("a1", "b1");
			assertThat(ids(collect(b -> b.defaultTimezone(ZoneOffset.ofHours(-5))))).containsExactly("b1", "a1");
		}

		@Test
		void anLdesDefaultTimezoneThatIsNotATimezoneIsAnError() {
			server.trig("/root", description + " ; ldes:defaultTimezone \"CET\" . " + pages);
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("defaultTimezone");
		}

		@Test
		void aPageIsOnlyReportedAsNotOrderedWhenThatIsCertain() {
			server.trig("/root", description + " . <> ldes:relation " + relation("/a", "") + " .");
			// 11:00 without timezone can be after 12:00Z: this is not certain
			server.trig("/a", record("a1", "2026-01-01T12:00:00Z"), record("a2", "2026-01-01T11:00:00"));
			assertThat(ids(collect())).containsExactly("a1", "a2");
			// 4 days before is certain
			server.trig("/a", record("a1", "2026-01-05T12:00:00Z"), record("a2", "2026-01-01T11:00:00"));
			assertThatThrownBy(LdesClientIT::collect).hasMessageContaining("ordering");
		}

		@Test
		void aMemberIsOnlySkippedWhenItIsCertainlyBeforeTheBookmark() {
			server.trig("/root", description + " . <> ldes:relation " + relation("/a", "") + " .");
			server.trig("/a", record("m0", "2025-12-30T00:00:00"), record("m1", "2026-01-01T12:00:00Z"));
			MemoryBookmarkStore state = new MemoryBookmarkStore();
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("m0", "m1");
			server.trig("/a", record("m0", "2025-12-30T00:00:00"), record("m1", "2026-01-01T12:00:00Z"), record("m2", "2026-01-01T12:00:00"));
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("m2");
			assertThat(ids(collect(b -> b.bookmarks(state)))).isEmpty();
		}

		@Test
		void aRelationIsOnlySkippedWhenItsUpperBoundIsCertainlyBeforeTheBookmark() {
			MemoryBookmarkStore state = new MemoryBookmarkStore();
			server.trig("/root", description + " . <> ldes:relation " + relation("/c", "") + " .");
			server.trig("/c", record("c1", "2026-03-01T00:00:00Z"));
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("c1");
			server.requests.clear();
			server.trig("/root", description + " .\n<> ldes:relation " + relation("/c", "") + " , "
					+ relation("/old", "; ldes:timeUntil \"2026-01-01T00:00:00\"^^xsd:dateTime") + " , "
					+ relation("/maybe", "; ldes:timeUntil \"2026-03-01T05:00:00\"^^xsd:dateTime") + " .");
			server.trig("/old", record("o1", "2025-12-01T00:00:00"));
			server.trig("/maybe", record("m1", "2026-03-01T03:00:00"));
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("m1");
			assertThat(server.requests).doesNotContain("/old").contains("/maybe");
		}
	}

	@Nested
	class ImmutablePages {
		@Test
		void aPageMarkedImmutableByThePageThatLinksToItIsNotFetchedAgainOnceProcessed() {
			MemoryBookmarkStore state = new MemoryBookmarkStore();
			server.trig("/root", DESCRIPTION + "\n<> ldes:relation " + relation("/p1", "") + " , " + relation("/p2", "") + " .\n<"
					+ server.url("/p1") + "> ldes:immutable true .");
			server.trig("/p1", record("a1", "2026-01-01T00:00:00Z"));
			server.trig("/p2", record("b1", "2026-01-02T00:00:00Z"));
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("a1", "b1");
			server.requests.clear();
			server.trig("/p2", record("b1", "2026-01-02T00:00:00Z"), record("b2", "2026-01-03T00:00:00Z"));
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("b2");
			assertThat(server.requests).containsExactly("/root", "/p2");
		}

		@Test
		void aPageMarkedImmutableThatLinksToOtherPagesIsReadAgain() {
			MemoryBookmarkStore state = new MemoryBookmarkStore();
			server.trig("/root", DESCRIPTION + "\n<> ldes:relation " + relation("/p1", "") + " .\n<" + server.url("/p1") + "> ldes:immutable true .");
			server.trig("/p1", "<> ldes:relation " + relation("/p2", "") + " .", record("a1", "2026-01-01T00:00:00Z"));
			server.trig("/p2", record("b1", "2026-01-02T00:00:00Z"));
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("a1", "b1");
			server.requests.clear();
			server.trig("/p2", record("b1", "2026-01-02T00:00:00Z"), record("b2", "2026-01-03T00:00:00Z"));
			assertThat(ids(collect(b -> b.bookmarks(state)))).containsExactly("b2");
			assertThat(server.requests).containsExactly("/root", "/p1", "/p2");
		}
	}

	@Nested
	class TimeWindow {
		@Test
		void afterAndBeforeFilterMembersAndPruneRelations() {
			server.trig("/root", DESCRIPTION + "\n<> ldes:relation "
					+ relation("/old", "; ldes:timeUntil \"2026-01-01T00:00:00Z\"^^xsd:dateTime") + " , " + relation("/a", "") + " .");
			server.trig("/old", record("o1", "2025-12-01T00:00:00Z"));
			server.trig("/a", record("a1", "2026-01-01T00:00:00Z"), record("a2", "2026-01-02T00:00:00Z"), record("a3", "2026-01-03T00:00:00Z"));
			List<LdesMember> members = collect(b -> b.after(Instant.parse("2026-01-01T00:00:00Z")).before(Instant.parse("2026-01-03T00:00:00Z")));
			assertThat(ids(members)).containsExactly("a2");
			assertThat(server.requests).doesNotContain("/old");
		}
	}

	@Nested
	class RequestingPagesAhead {
		private static final long DELAY = 40;
		private final String stream = "ex:ES a ldes:EventStream ; ldes:timestampPath dcterms:created ; ldes:memberType ex:Record .";

		private String day(int n) {
			return "2026-01-" + String.format("%02d", n) + "T00:00:00Z";
		}

		private String bounded(String page, int from, int until) {
			return relation(page, "; ldes:timeFrom \"" + day(from) + "\"^^xsd:dateTime ; ldes:timeUntil \"" + day(until) + "\"^^xsd:dateTime");
		}

		/** A root with count leaf pages, one member each. */
		private List<String> flat(int count) {
			List<String> relations = new ArrayList<>();
			List<String> expected = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				relations.add(bounded("/p" + i, i + 1, i + 2));
				server.trig("/p" + i, record("m" + i, day(i + 1)));
				server.set("/p" + i, server.pages.get("/p" + i).withDelay(DELAY));
				expected.add("m" + i);
			}
			server.trig("/root", stream + "\n<> ldes:relation " + String.join(" , ", relations) + " .");
			return expected;
		}

		@Test
		void pagesThatAreFollowedNextAreRequestedAtTheSameTimeAndTheOrderDoesNotChange() {
			List<String> expected = flat(6);
			assertThat(ids(collect(b -> b.concurrentFetches(4)))).containsExactlyElementsOf(expected);
			assertThat(server.maxInFlight).isGreaterThan(1);
		}

		@Test
		void neverMorePagesThanTheConcurrentRequestsAreRequestedAhead() {
			List<String> expected = flat(8);
			assertThat(ids(collect(b -> b.concurrentFetches(2)))).containsExactlyElementsOf(expected);
			assertThat(server.maxInFlight).isEqualTo(2);
		}

		@Test
		void withOneConcurrentRequestPagesAreRequestedOneByOne() {
			List<String> expected = flat(4);
			assertThat(ids(collect(b -> b.concurrentFetches(1)))).containsExactlyElementsOf(expected);
			assertThat(server.maxInFlight).isEqualTo(1);
			assertThat(server.requests).containsExactly("/root", "/p0", "/p1", "/p2", "/p3");
		}

		@Test
		void aPageThatFailsToLoadIsOnlyAnErrorWhenTheMergeNeedsIt() {
			flat(3);
			server.set("/p2", MockLdesServer.Page.of("unavailable", "text/plain").withStatus(404));
			List<String> first = new ArrayList<>();
			try (LdesClient client = LdesClient.create(root()).concurrentFetches(4).highWaterMark(1).build()) {
				first.add(client.next().id().getURI().replace("http://example.org/", ""));
			}
			assertThat(first).containsExactly("m0");
			assertThatThrownBy(() -> collect(b -> b.concurrentFetches(4))).hasMessageContaining("404");
		}

		@Test
		void pagesBeforeTheBookmarkOfASavedStateAreNotRequestedAhead() {
			List<String> expected = flat(5);
			MemoryBookmarkStore state = new MemoryBookmarkStore();
			assertThat(ids(collect(b -> b.bookmarks(state).concurrentFetches(3)))).containsExactlyElementsOf(expected);
			server.requests.clear();
			assertThat(ids(collect(b -> b.bookmarks(state).concurrentFetches(3)))).isEmpty();
			assertThat(server.requests).containsExactly("/root", "/p4");
		}
	}

	@Nested
	class OutputAsMessageLog {
		@Test
		void theMembersWrittenAsATriGMessageLogAreReadBackAsTheSameMessages() {
			server.trig("/root", DESCRIPTION + "\n<> ldes:relation " + relation("/p1", "") + " .");
			server.trig("/p1", record("a1", "2026-01-01T00:00:00Z", "; ex:label \"first\" "), record("b1", "2026-01-02T00:00:00Z"));
			List<LdesMember> members = collect();
			String log = RDFMessages.writeString(Lang.TRIG, members.stream().map(LdesMember::message).toList());
			assertThat(log).startsWith("VERSION \"1.2-messages\"");
			List<RdfMessage> back = RDFMessages.parse(log, Lang.TRIG);
			assertThat(back).hasSize(2);
			assertThat(back.get(0).size()).isEqualTo(members.get(0).message().size());
		}
	}

	@Nested
	class LocalFiles {
		@Test
		void aRootPageCanBeALocalFileWithARelativeRelation(@TempDir Path dir) throws IOException {
			Files.writeString(dir.resolve("root.trig"), "VERSION \"1.2-messages\"\n" + MockLdesServer.PREFIXES
					+ "<#ES> a ldes:EventStream ; ldes:memberGraph true .\n<> ldes:relation [ ldes:page <page.nq> ; ldes:pageNumber 0 ] .\n");
			Files.writeString(dir.resolve("page.nq"), "VERSION \"1.2-messages\"\n<http://example.org/m0> <http://example.org/v> \"0\" <http://example.org/m0> .\nMESSAGE\n"
					+ "<http://example.org/m1> <http://example.org/v> \"1\" <http://example.org/m1> .\n");
			List<String> ids = new ArrayList<>();
			try (LdesClient client = LdesClient.create(dir.resolve("root.trig").toString()).build()) {
				client.forEach(member -> ids.add(member.id().getURI().replace("http://example.org/", "")));
			}
			assertThat(ids).containsExactly("m0", "m1");
		}
	}
}
