# RDF Messages test suite (vendored)

`messages-tests.bs` and `messages.bs` are unmodified copies of `spec/messages-tests.bs` and `spec/messages.bs` of
<https://github.com/w3c-cg/rsp> at the commit recorded in `UPSTREAM.json`, together with their SHA-256 hashes. The test
`RdfMessagesTestSuiteTest` verifies the hashes, extracts the cases from the Bikeshed source (the suite is a prose
document, not a manifest), and runs every case; a failing case fails the build. It writes an EARL report to
`target/rdf-messages-earl-report.ttl` and a summary to `target/rdf-messages-conformance.md`.

| Upstream group | Handling |
| --- | --- |
| Parsing Tests for Turtle, TriG, N-Triples, and N-Quads | Run through the push parser (`RDFMessagesParser.parse`) and the pull parser (`MessageLog`, reading the input one byte at a time), in every syntax in which the input is valid. Expected messages are compared by RDF isomorphism. Expectations in prose (blank node scoping) are mapped to an explicit check; a case with prose expectations and no check fails. |
| Parsing Tests for RDF/XML | Run through the RDF/XML message log reader, with the same comparison. |
| Serialization Tests | The messages are written with `RDFMessagesWriter` in each applicable syntax, parsed again and compared. |
| Error Tests | The input must be rejected, in each applicable syntax. The RDF/XML case "read by a parser without message support" is run through Jena's plain RDF/XML parser. |
| Message Stream Discovery Tests | Skipped: protocol adapters are out of scope for a parser library. |
| Parsing Tests for NDJSON-LD | Skipped: NDJSON-LD is not supported. |

To update: copy the two files from a newer upstream commit, update `UPSTREAM.json` (commit, date, hashes), run
`mvn -pl parsers test`, and extend `RdfMessagesTestSuiteTest` for new kinds of cases.
