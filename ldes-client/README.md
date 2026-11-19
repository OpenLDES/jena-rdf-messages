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

Replication throughput of this client, on the workload of the JavaScript ldes-client's
[performance suite](https://github.com/rdf-connect/ldes-client/tree/main/perf): 20,000 synthetic members of 8 triples
(a type, a timestamp, scalar properties and a nested blank node), published as LDES 2.0 pages in TriG and in Jelly-RDF,
served over loopback HTTP without latency or compression. Default client settings, every member checked. Measured on 15
November 2026 on an Intel Core i7-1265U with OpenJDK 21.0.13; medians.

| Data pages × members/page | TriG, fresh JVM | Jelly, fresh JVM | TriG, after start-up | Jelly, after start-up |
| --- | ---: | ---: | ---: | ---: |
| 200 × 100 | 12,256 members/s | 13,420 members/s | 92,079 members/s | 96,181 members/s |
| 20 × 1,000 | 13,906 members/s | 16,782 members/s | 102,436 members/s | 126,977 members/s |
| 4 × 5,000 | 14,451 members/s | 15,737 members/s | 108,295 members/s | 147,091 members/s |
| 1 × 20,000 | 14,550 members/s | 14,282 members/s | 126,903 members/s | 149,148 members/s |

* **Fresh JVM** is one replication in a new process, as the command line client runs: timing starts when the client is
  constructed, after JVM startup. It is dominated by loading Jena and compiling the client (about 0.45 s to the first
  member), so it is a cold-start figure, not the client's speed.
* **After start-up** is the same replication repeated in one JVM with a fresh client each time, after 8 warm-up
  replications: what a long-running client or pipeline gets. Members arrive within 10 ms of starting.

Multiply by 8 for triples per second. To compare with LDES 1.0: the JavaScript client's
[published results](https://github.com/rdf-connect/ldes-client/blob/main/perf/results/README.md) use the same members
and layouts, in fresh Node.js processes. There, the LDES 1.0 (Turtle, TREE) version of these feeds replicates at 11,000
to 16,000 members/s with the client of the `experiment-ldes-2.0` branch, and at 570 to 13,500 members/s with the
`main` branch of 5 November 2026 (slowest on large pages); the LDES 2.0 version at 40,000 to 68,000 members/s.

Against the LDES 1.0 client of [Linked Data Interactions](https://github.com/OpenLDES/Linked-Data-Interactions) 3.2.2,
the client in LDIO's `Ldio:LdesClient`, on the same members published as LDES 1.0: after start-up, this client on
LDES 2.0 replicates 4.2 to 8.3 times faster in TriG and 5.2 to 9.9 times faster in Jelly (19 November 2026). Most of
the difference comes from LDES 2.0 itself, which the JavaScript client also reads 3 to 6 times faster than LDES 1.0;
running the LDI client in an LDIO pipeline adds no measurable cost.

[perf/compare-clients.mjs](perf/compare-clients.mjs) produces these numbers, side by side with the JavaScript client on
byte-identical feeds, and [perf/ldio/](perf/ldio/) runs the LDIO pipeline; [perf/README.md](perf/README.md) has the
method and the comparison. For the speed of the parsers alone, see the [parsers benchmark](../parsers/README.md#performance).
