# LDES client

An [LDES 2.0](https://w3id.org/ldes/specification) client on top of the RDF Messages parsers for Apache Jena. In LDES
2.0 an event stream is an RDF Message stream profile: every member is an RDF Message, and every page with members is an
RDF Message log. The client therefore needs no member extraction: it reads pages as streams of messages and merges them.

**LDES 2.0 is a draft.** This client follows the proposal at
[SEMICeu/LinkedDataEventStreams#116](https://github.com/SEMICeu/LinkedDataEventStreams/pull/116) and will change with
it. It reads LDES 2.0 only; an LDES 1.0 (TREE) page is reported as an error, with a pointer to the LDES 1.0 client of
Linked Data Interactions.

## Command line

`mvn package` builds `target/ldes-client.jar` with all dependencies.

```sh
java -jar ldes-client.jar https://example.org/ldes > members.trig     # replicate and exit
java -jar ldes-client.jar https://example.org/ldes --follow            # keep polling
java -jar ldes-client.jar https://example.org/ldes --save ./state      # resume later from the saved bookmark
```

Standard output is a TriG RDF Message log with one message per member (`--format nquads` or `--format jelly` for
the others); logs go to standard error. Options: `--follow`, `--save <dir>`, `--start-fresh`, `--after` and `--before`
(exclusive `xsd:dateTime` bounds), `--poll-interval <ms>`, `--concurrent <n>`, `--default-timezone <offset>`, `--quiet`.
The URL can also be a local file, for example a root page that links to a Jelly-RDF file.

## Library

```java
try (LdesClient client = LdesClient.create("https://example.org/ldes")
        .bookmarks(new FileBookmarkStore(Path.of("state")))
        .follow(true)
        .listener(new LdesListener() {
            @Override public void onRunFinished(long emitted) { ... }   // a synchronization run is finalized
        })
        .build()) {
    LdesContext context = client.context();        // timestamp path, membership rule, shape, retention policy, ...
    for (LdesMember member : client) {
        member.id();                               // the member identifier
        member.message();                          // the whole member message, an RdfMessage
        member.timestamp();                        // Optional<Instant>, from ldes:timestampPath
    }
}
```

The client reads pages in a virtual thread and hands members over through a bounded queue (`highWaterMark`): a slow
consumer slows down the client, and nothing beyond the current messages is held in memory. A member counts as emitted
when the consumer has taken it, so the saved bookmark never skips a member the consumer did not see.

## What it implements

The structure follows the specification; the tests in [src/test](src/test/java/org/openldes/jena/messages/ldes) do too.

* **Pages as message logs**: TriG, N-Quads, N-Triples, Turtle, RDF/XML and Jelly-RDF, gzip-compressed or not. A page
  without message delimiters is one message, the description message. The first message is the description message
  when it has the page IRI as subject or describes the `ldes:EventStream`; every other message is a member message. An
  empty message is an error.
* **Membership**: direct (`<ES> ldes:member ?m`) and indirect (`ldes:memberGraph`, `ldes:memberType`,
  `ldes:memberPath` with SHACL property paths). The member is the whole message.
* **Ordering keys**: `ldes:timestampPath` and/or `ldes:sequencePath` (XPath comparison of numbers, dates and strings),
  or implicit order by page number and index. Timestamps without a timezone follow the 28-hour uncertainty rules:
  `ldes:defaultTimezone` or a configured timezone resolves them, and is added to the emitted literals.
* **Traversal**: the merge of sorted logs with the bookmark as the only state (key, members sharing the key, immutable
  pages, ETags of mutable pages). Relations whose upper bound is before the bookmark are skipped without a request;
  pages marked immutable are not fetched again; pages are revalidated with `If-None-Match`. Pages are prefetched up to
  `concurrentFetches`, without changing the order of emission.
* **HTTP**: an `Accept` header preferring message serializations, redirects, retries with back-off for 408, 425, 429,
  500, 502, 503 and 504, `410 Gone` as an empty page, any other 4xx or 5xx as an error. Local files are read directly.
* **Polling**: `follow` with `ldes:pollingInterval` or the configured interval; every run is reported to the listener.

Not implemented yet: the ordering rule for members that finalize a transaction, and Turtle conditions beyond the
`after`/`before` window.

## Performance

The parsers module has the serialization benchmark ([parsers/README.md](../parsers/README.md#performance)): Jelly-RDF
pages parse 3 to 4 times faster than TriG or N-Quads pages. Replication throughput is bounded by the parser of the page
serialization, as the merge itself holds only the next message of every open page.
