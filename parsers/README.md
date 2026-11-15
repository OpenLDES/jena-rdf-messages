# RDF Messages parsers for Apache Jena

[RDF Messages](https://w3c-cg.github.io/rsp/spec/messages) for [Apache Jena](https://jena.apache.org/): the RDF Message
type, and parsers and writers for RDF Message logs in **TriG, Turtle, N-Quads, N-Triples, RDF/XML and Jelly-RDF**, with
ordinary Jena `Quad`s, `Triple`s and `DatasetGraph`s. Maven: `org.openldes:jena-messages-parsers`.

Jelly-RDF has had message framing from the start (one frame per message); this module gives the text serializations the
same message-level interface, and gives Jelly the same types as the text serializations. NDJSON-LD is not supported.

## The types

* `RdfMessage`: an RDF dataset that is to be interpreted atomically, as one communicative act. It is a `DatasetGraph`
  with its position in the log and the prefixes that were in effect. A message is not a store: it is the unit that a
  consumer validates, transforms or loads (`addTo(DatasetGraph)`) before it ends up anywhere.
* `StreamRDFMessages`: Jena's `StreamRDF` plus `message()`, the boundary callback. This is the sub-interface that was
  suggested in [apache/jena#4363](https://github.com/apache/jena/issues/4363), so that Jena itself stays untouched. A
  parser emits the triples and quads of a message as usual, and calls `message()` when the message is complete.
* `MessageLog`: a pull-based `Iterator<RdfMessage>` over a log that is being parsed in a virtual thread, which reads
  ahead by a bounded number of messages. It is what an LDES client reads pages with.

## Parsing

```java
import org.openldes.jena.messages.*;

// Push: one callback per message, as soon as its delimiter is read
RDFMessages.parse(inputStream, Lang.TRIG, message -> store.addAll(message.dataset()));

// Pull: close the log when it is not read to the end
try (MessageLog log = RDFMessages.read("https://example.org/log.trig")) {
    for (RdfMessage message : log) { ... }
}

// Low level: triples, quads and boundaries, without building datasets
RDFMessages.parse(inputStream, Lang.NQUADS, new StreamRDFMessages() { ... });

// All options
List<RdfMessage> messages = RDFMessagesParser.create()
    .source(inputStream)
    .contentType("application/trig; version=1.2-messages")   // serialization and external announcement
    .base("https://example.org/")
    .bufferedMessages(64)
    .toList();
```

The serialization comes from `lang(Lang)`, `contentType(String)` or the file name. A Turtle, TriG, N-Triples or N-Quads
document is read as a message log when it announces itself (`VERSION "1.2-messages"` or `@version "1.2-messages" .`),
when the media type announces it (`version=1.2-messages`), or when `messages(true)` says so. Any other serialization
that Jena knows (JSON-LD, TriX, ...) has no message delimiters and is read as one message, which is how the LDES
specification reads a page without delimiters. Jelly-RDF is used when `jelly-jena` is on the classpath (see below).

What the parsers implement, from [section 2.1 of the specification](https://w3c-cg.github.io/rsp/spec/messages#turtle):

* The delimiters `MESSAGE` (all four syntaxes) and `@message .` (Turtle and TriG), only where directives are allowed:
  a delimiter in a graph block, a statement or a list is a syntax error. `MESSAGE` is case-sensitive, like the grammar
  writes it. In N-Triples and N-Quads it must be alone on its line (a comment may follow), as the grammar is line-based.
* Every triple or quad belongs to the current message; a delimiter finalizes it (possibly empty); at the end of the
  input an open message is finalized; a trailing delimiter does not add an empty message. A document that is not
  announced as a log is one message, and a delimiter in it is an error.
* The labels `1.2-messages`, `1.2-basic-messages` and `1.1-messages` are the ones of the specification; any label with
  the `-messages` suffix is accepted. An announcement after the first statement is an error (blank node scoping must be
  known from the start); a repeated announcement is fine, so that logs can be concatenated.
* Blank node labels are scoped to their message: the parser resets Jena's label-to-node mapping at every delimiter, so
  the same label in two messages gives two nodes, with any `LabelToNode` policy. Prefixes and the base persist across
  messages, and a later `PREFIX` overrides.
* RDF/XML logs (`<rdfm:MessageLog>` with `<rdf:RDF>` children) are read with StAX, one `<rdf:RDF>` element at a time;
  namespaces, `xml:base`, `xml:lang` and `rdf:version` of the root are inherited by every message, and each message is
  parsed by a fresh Jena RDF/XML parser, which scopes `rdf:nodeID` to the message.

## Writing

```java
StreamRDFMessages out = RDFMessagesWriter.create().lang(Lang.TRIG).prefixes(prefixes).build(outputStream);
out.start();              // VERSION "1.2-messages" and the prefixes
out.quad(...); out.message();
out.finish();

RDFMessages.write(outputStream, Lang.NQUADS, messages);
String text = RDFMessagesWriter.create().lang(Lang.TURTLE).directiveStyle(DirectiveStyle.AT).writeString(messages);
```

The writers are Jena's streaming writers plus the announcement and the delimiter. A delimiter is written after every
message, not between messages, so that a consumer receives a message as soon as it is written and a trailing empty
message survives a round trip. The announcement is `1.2-messages` unless `version(...)` says otherwise;
`writeVersion(false)` leaves it out when the media type carries it. Turtle and N-Triples have no graphs: a quad loses its
graph there.

## Jelly-RDF

[Jelly-RDF](https://w3id.org/jelly/) is supported through `eu.neverblink.jelly:jelly-jena`, an optional dependency: add
it to your project and `RDFMessagesParser`/`RDFMessagesWriter` accept `JellyLanguage.JELLY`, with the same `RdfMessage`
and `StreamRDFMessages` types as the text serializations.

```java
RDFMessagesParser.create().source(in).lang(JellyLanguage.JELLY).forEach(message -> ...);
RDFMessagesWriter.create().lang(JellyLanguage.JELLY).write(out, messages);   // one frame per message
```

* Reading: a Jelly stream of type `MESSAGES` (Jelly-RDF 1.2) carries its own message boundaries, which are used. In any
  other stream (`FLAT`, Jelly-RDF 1.0 to 1.2) every frame is one message, as
  [the specification says](https://w3c-cg.github.io/rsp/spec/messages#other-formats). Delimited and non-delimited
  streams are detected.
* Writing: a delimited stream of type `MESSAGES` with quads, one frame per message (`framePerMessage(false)` fills
  frames up instead).

## Conformance

The [RDF Messages test suite](https://w3c-cg.github.io/rsp/spec/messages-tests) is vendored at a pinned upstream
revision in [src/test/resources/rdf-messages-tests](src/test/resources/rdf-messages-tests) and runs in every build
(`mvn verify`): every parsing case runs through the push parser and the pull parser (one byte at a time), in every
syntax in which the input is valid, and every serialization case round-trips through the writers (Jelly included).
The build writes `target/rdf-messages-earl-report.ttl` and `target/rdf-messages-conformance.md`.

Result for this version (w3c-cg/rsp `4a4a2c54`, Apache Jena 6.3.0-SNAPSHOT): **28 passed, 0 failed, 5 skipped** of 33
cases. The 5 skipped cases are the NDJSON-LD parsing tests, a serialization this module does not implement.

## Performance

Two benchmarks live in [src/test/java/.../perf](src/test/java/org/openldes/jena/messages/perf). Numbers below are
from an Intel Core i7-1265U, OpenJDK 21.0.13, Jena 6.3.0-SNAPSHOT, jelly-jena 4.0.1: one machine, one JVM per run,
medians, so a comparison and not a guarantee.

**Generated log** (`mvn -pl parsers -Pperf exec:java`, [Benchmark.java](src/test/java/org/openldes/jena/messages/perf/Benchmark.java)):
20 000 messages of 10 quads, median of 5 runs after a warm-up.

| Serialization | Size | Write (quads/s) | Parse, push (quads/s) | Parse, pull (quads/s) |
| --- | ---: | ---: | ---: | ---: |
| TriG | 13.2 MB | 810k | 1,559k | 1,222k |
| N-Quads | 28.4 MB | 2,128k | 1,262k | 929k |
| Jelly | 3.7 MB | 4,529k | 5,192k | 3,201k |

**Real data** ([CityPulseBenchmark.java](src/test/java/org/openldes/jena/messages/perf/CityPulseBenchmark.java)): the
RiverBench CityPulse traffic samples, 10 000 messages of 36 triples (360 000 triples), as
[N-Triples](https://w3id.org/riverbench/datasets/citypulse-traffic/dev/files/flat_10K.nt.gz) (83.7 MB raw, 3.9 MB gzip)
and [Jelly](https://w3id.org/riverbench/datasets/citypulse-traffic/dev/files/jelly_10K.2.jelly.gz) (9.0 MB raw, 2.7 MB
gzip), plus the same messages written as prefixed TriG by this module (33.1 MB raw, 1.9 MB gzip). Every timed run checks
the message count and sizes; all 10 000 messages were verified isomorphic across the three serializations. Five warm-ups,
nine timed runs, inputs in memory.

| API | N-Triples | TriG | Jelly |
| --- | ---: | ---: | ---: |
| `parse(StreamRDFMessages)`, counting only | 448 ms | 349 ms | 21 ms |
| `parse(StreamRDFMessages)`, quads collected per message | 493 ms | 347 ms | 25 ms |
| `forEach(RdfMessage)`, a dataset per message | 503 ms | 398 ms | 52 ms |
| `MessageLog` iterator, a dataset per message | 522 ms | 417 ms | 57 ms |
| gzip decompression on top (push API) | +40 ms | +13 ms | +26 ms |

What this says:

* The delimiters cost nothing measurable. On the N-Triples sample, the message parser (448 ms) is as fast as plain
  Jena on the same bytes with the announcement and delimiters removed (469 ms); a JDK Flight Recorder profile of the
  message parser attributes 68% of samples to Jena's tokenizer reading IRIs, 12% to IRI resolution and 0.3% to the
  delimiter and framing code. Text parsing speed is Jena's tokenizer speed; a faster text parser would need a different
  tokenizer, which this project does not attempt.
* Jelly is 7 times faster than TriG when building a dataset per message, and 14 times faster at the event level,
  because it decodes 9 times fewer bytes than N-Triples and reuses encoded terms instead of rescanning IRIs. Compressed
  sizes hide this: gzipped TriG is even smaller than gzipped Jelly here.
* Building a `DatasetGraph` per message costs about 50 ms per 10 000 messages, and the pull iterator another 20 ms for
  the hand-over between threads. Use `parse(StreamRDFMessages)` when triples and boundaries are enough, `forEach` for
  a dataset per message, and the iterator when a pull interface is needed (as the LDES client does).
* Against [n3.js-messages](https://github.com/pietercolpaert/n3.js-messages) (Node.js 25, N3.js 2.13) on the same TriG
  bytes, both runtimes parse about 1.0 to 1.1 million triples per second at the quad-array level (Java 347 ms,
  JavaScript 326 ms, ranges overlapping); the difference between the two implementations is noise, the difference
  between text and Jelly is not.

The CityPulse benchmark takes the two downloaded gzip files as arguments (see its Javadoc); it needs no network at
run time.

## Relationship to Apache Jena

This module does not change Jena. The Turtle and TriG parsers extend Jena's `LangTurtleBase`, which hands every top
level element that is not `PREFIX`, `BASE` or `VERSION` to the subclass; `@message` is rewritten into a keyword by a
tokenizer wrapper, as Jena's main loop handles `@`-directives itself. The N-Triples and N-Quads parsers extend
`LangNTuple`. The derived files keep the Apache license header (see [NOTICE](../NOTICE)). Jena 6.3.0-SNAPSHOT is used
because `VERSION` in N-Triples and N-Quads ([GH-2805](https://github.com/apache/jena/issues/2805)) is not in a release
yet; the first Jena 6.3.0 release will do.
