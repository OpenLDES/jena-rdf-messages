# Jena RDF Messages

A toolset to work with [RDF Messages](https://w3c-cg.github.io/rsp/spec/messages) on top of
[Apache Jena](https://jena.apache.org/). The project is part of the OpenLDES initiative.

This repository is the answer to [apache/jena#4363](https://github.com/apache/jena/issues/4363), in which the Jena
maintainers asked for RDF Messages to be built next to Jena, on its extension points, rather than inside it.

| Module | Artifact | What it is |
| --- | --- | --- |
| [parsers](parsers) | `org.openldes:jena-messages-parsers` | The `RdfMessage` type, `StreamRDFMessages` (Jena's `StreamRDF` plus a `message()` boundary), and parsers and writers for RDF Message logs in TriG, Turtle, N-Quads, N-Triples, RDF/XML and Jelly-RDF. Tested against the [RDF Messages test suite](https://w3c-cg.github.io/rsp/spec/messages-tests): 28 passed, 0 failed, 5 skipped (NDJSON-LD). |
| [ldes-client](ldes-client) | `org.openldes:jena-messages-ldes-client` | An [LDES 2.0](https://w3id.org/ldes/specification) client built on the parsers: every member is a message, every page a message log. A library and a command line tool. |
| [ldi](ldi) | `org.openldes:jena-messages-ldi` | An adapter and an output for [Linked Data Interactions](https://github.com/OpenLDES/Linked-Data-Interactions) pipelines, in which streaming RDF messages are sent through a pipeline. |

An RDF Message is an RDF dataset that is interpreted atomically: it requires processing before it is added to a store or
elsewhere (for example, the RDF body of a POST request). That is what `RdfMessage` is: a `DatasetGraph` with its position
in the log, and not a store.

```java
RDFMessages.parse(in, Lang.TRIG, message -> process(message.dataset()));   // one callback per message, streaming

try (LdesClient client = LdesClient.create("https://example.org/ldes").follow(true).build()) {
    for (LdesMember member : client) { process(member.message()); }
}
```

## Building

Java 21 and Maven. `mvn verify` runs the unit tests, the integration tests against local mock servers, and the vendored
RDF Messages test suite. The project depends on Apache Jena 6.3.0-SNAPSHOT (from the Apache snapshots repository)
until Jena 6.3.0 is released: that version adds `VERSION` to the N-Triples and N-Quads parsers
([GH-2805](https://github.com/apache/jena/issues/2805)), which RDF Message logs need.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the code style and quality conventions (those of the OpenLDES repositories)
and [WORKFLOWS.md](WORKFLOWS.md) for the GitHub Actions workflows.

## License

[EUPL v1.2](LICENSE), like the rest of the OpenLDES stack. The parsers derived from Apache Jena keep their Apache 2.0
header (see [NOTICE](NOTICE)); the vendored test suite keeps its W3C license.
