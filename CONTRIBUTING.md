# Contributing

This project follows the conventions of the other OpenLDES Java repositories
([Linked-Data-Interactions](https://github.com/OpenLDES/Linked-Data-Interactions) and
[LDESServer](https://github.com/OpenLDES/LDESServer)), so that a contributor to one of them feels at home here.

## Building

Java 21 and Maven 3.8 or newer are required.

```sh
mvn verify                 # compile, unit tests, integration tests, RDF Messages conformance suite
mvn verify -Pcoverage      # the same, with JaCoCo coverage reports in */target/site/jacoco
mvn -pl parsers -Pperf exec:java   # the parser benchmark (see parsers/README.md)
```

The modules are [parsers](parsers) (the RDF Message type, the parsers and the writers, including Jelly-RDF),
[ldes-client](ldes-client) (the LDES 2.0 client and its command line) and [ldi](ldi) (the Linked Data Interactions
adapter and output). Every module has its own README.

## Code style

* Java 21, with records, sealed types, switch expressions and virtual threads where they make the code simpler.
* Tabs for indentation in Java, two spaces in XML, YAML and Markdown (see [.editorconfig](.editorconfig)). Lines are at
  most 120 characters.
* One public type per file, with a Javadoc comment that says what the type is for and refers to the section of the
  specification it implements. Comments explain *why*, the code says *what*.
* Public API: builders for anything with more than two options (`RDFMessagesParser.create()...`), immutable value
  objects (records), and exceptions that name the page, message or IRI that caused the problem.
* No checked exceptions in the public API: I/O problems are wrapped in `RiotException` or `LdesClientException`.
* Dependencies are kept to Apache Jena, SLF4J and the optional integrations (Jelly-RDF, the LDI API). Logging goes
  through SLF4J; the library never configures a logging backend.
* The parsers derived from Apache Jena's RIOT parsers keep the Apache license header and state what they derive from
  (see [NOTICE](NOTICE)). All other files are licensed under the EUPL v1.2.

## Quality

* Every change comes with tests: unit tests are `*Test` classes (Surefire), tests that start a local HTTP server are
  `*IT` classes (Failsafe). Both run in `mvn verify`.
* The [RDF Messages test suite](parsers/src/test/resources/rdf-messages-tests/README.md) is vendored at a pinned
  upstream revision and is part of the build: a parsing or serialization case that fails, fails the build. New upstream
  cases that the runner does not recognize fail the build too, so that they cannot be ignored silently.
* The LDES 2.0 client is tested against local mock servers in [ldes-client/src/test](ldes-client/src/test), following
  the structure of the specification.
* Pull requests are verified by [workflow 1.a](.github/workflows/1a.build-test-project.yml), optionally with a
  SonarCloud quality gate (see [WORKFLOWS.md](WORKFLOWS.md)). Keep the build free of compiler warnings (`-Xlint:all`).
* Commit messages follow the [Conventional Commits](https://www.conventionalcommits.org/) style used by the OpenLDES
  workflows (`feat:`, `fix:`, `ci:`, `docs:`, ...).
* Dependabot keeps the dependencies and the pinned GitHub Actions up to date.

## Specifications

* [RDF Messages](https://w3c-cg.github.io/rsp/spec/messages) and its
  [test suite](https://w3c-cg.github.io/rsp/spec/messages-tests) (W3C RDF Stream Processing Community Group).
* [Linked Data Event Streams 2.0](https://w3id.org/ldes/specification) (draft).
* The discussion that led to this project: [apache/jena#4363](https://github.com/apache/jena/issues/4363).

Both specifications are living documents: when they change, update the vendored copies, the implementation notes in
the module READMEs, and the tests, in one pull request.
