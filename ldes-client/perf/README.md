# LDES replication: three clients, LDES 1.0 and LDES 2.0

[compare-clients.mjs](compare-clients.mjs) replicates the same members, published once as an LDES 1.0 feed and once as
LDES 2.0 feeds, with three clients, and reports them side by side:

| Client | Reads | Child process |
| --- | --- | --- |
| The JavaScript [ldes-client](https://github.com/rdf-connect/ldes-client) | LDES 1.0 and 2.0 | its own `perf/lib/replicate.mjs` |
| The LDES client of [Linked Data Interactions](https://github.com/OpenLDES/Linked-Data-Interactions) (LDI) 3.2.2, the one in LDIO's `Ldio:LdesClient` | LDES 1.0 | [ReplicateLdi.java](ldi/src/main/java/org/openldes/jena/messages/ldes/perf/ldi/ReplicateLdi.java) |
| The Java client of this repository | LDES 2.0 | [Replicate.java](../src/test/java/org/openldes/jena/messages/ldes/perf/Replicate.java) |

[ldio/measure-ldio.mjs](ldio/measure-ldio.mjs) replicates the same LDES 1.0 feeds with the LDI client inside an LDIO
3.2.2 pipeline, to see what the pipeline adds to the client.

The feeds are those of the JavaScript client's own performance suite (`perf/lib/fixtures.mjs`), served by the same
in-process HTTP server with pre-generated responses, so every client reads byte-identical pages:

* 20,000 members of 8 triples (a type, a timestamp, scalar properties and a nested blank node);
* **LDES 1.0**: a `tree:Node` root with `tree:GreaterThanOrEqualToRelation`s to pages of Turtle, members listed with
  `tree:member`, as one graph per page;
* **LDES 2.0**: a root with `ldes:relation`s to pages of TriG with one RDF message per member, and the same messages
  as Jelly-RDF frames.

Two changes to the LDES 1.0 feed, both needed by the LDI client and applied for every client:

* every LDES 1.0 page has `Cache-Control: public, max-age=604800, immutable`, as a server marks the pages of a closed
  history. The LDI client only ends a replication when every page is immutable; without the header, it polls the pages
  again after their default expiry;
* the event stream has an `ldes:versionOfPath dcterms:isVersionOf`. The LDI client fails with a `NullPointerException`
  without one (`ViewSpecification.extractEventStreamProperties`), although LDES 1.0 makes it optional. The members have
  no `dcterms:isVersionOf`, and no client materialises versions.

## Run

Build the three clients, then run from the root of this repository:

```sh
(cd ../ldes-client && npm ci && npm run build)
mvn -pl ldes-client -am package -DskipTests
mvn -f ldes-client/perf/ldi/pom.xml package
node ldes-client/perf/compare-clients.mjs --js-client ../ldes-client \
  --output ldes-client/target/perf/clients.json --summary ldes-client/target/perf/clients.md
```

[ldi/](ldi/) is a separate Maven project, not part of this repository's build: LDI 3.2.2 is built on Apache Jena 6.2.0,
this repository on Jena 6.3.0-SNAPSHOT, and each client runs with its own classpath. It takes LDI from Maven Central.

| Option | Default |
| --- | --- |
| `--js-client PATH` | `../ldes-client`, a built checkout with its `perf/` directory (needed for the fixtures in any case) |
| `--clients js,java,ldi` | all three |
| `--formats ldes1,trig,jelly` | all three; every client reads the formats it supports |
| `--members N` | `20000` |
| `--page-sizes N,N,...` | `100,1000,5000,20000`, capped at the member count |
| `--repeats N` / `--warmups N` | `5` / `1` |
| `--delay MS` | `0`, server latency per request |
| `--iterations N` / `--skip-iterations M` | `1` / `0`: replications per process, and how many of them are warm-up |
| `--java-opts="..."` | none, JVM options for both Java children, with `=` because they start with a dash (for example `--java-opts="-Xmx1g"`) |
| `--timeout S` | `180`, per child process |
| `--output FILE` / `--summary FILE` | raw samples as JSON / the Markdown tables |

A quick smoke run: `--members 300 --page-sizes 25,300 --repeats 1 --warmups 0`.

To measure the clients **after start-up**, let every process replicate the feed several times and skip the first
iterations:

```sh
node ldes-client/perf/compare-clients.mjs --js-client ../ldes-client \
  --repeats 3 --warmups 0 --iterations 15 --skip-iterations 8 --timeout 900
```

Every iteration builds a new client, with empty state, and replicates the whole feed again in the same process; only
the iterations after `--skip-iterations` are reported. The JavaScript side then runs
[replicate-warm.mjs](replicate-warm.mjs), which imports the JavaScript client's build and applies the same checks as its
`replicate.mjs` in a loop. The clients settle after about 8 iterations of 20,000 members.

### The LDI client

[ReplicateLdi.java](ldi/src/main/java/org/openldes/jena/messages/ldes/perf/ldi/ReplicateLdi.java) wires the LDI client
as LDIO's `Ldio:LdesClient` wires it with its default configuration (`MemberSupplierFactory` and
`LdioLdesClientConfigurator`): Turtle as source format, state in memory, the exactly-once filter on, no version
materialisation, a request executor without authentication with 5 retries and no rate limit. It takes the members from
the member supplier in a loop, as the LDIO input does, and applies the same checks as the other children. The LDI
client fetches one page at a time; it has no read-ahead.

### The LDIO pipeline

[ldio/](ldio/) holds the pipeline and the LDIO configuration:

* [pipeline.yml](ldio/pipeline.yml): `Ldio:LdesClient` with its defaults (only `urls` and `source-format: text/turtle`)
  into `Ldio:NoopOut`, which discards the members, so that the pipeline measures the client and LDIO, not a sink;
* [application.yml](ldio/application.yml): no pipelines at start-up, and the actuator metrics endpoint;
* [docker-compose.yml](ldio/docker-compose.yml): the official image `openldes/ldi-orchestrator:3.2.2` with this
  configuration, to run the pipeline by hand.

```sh
ldes-client/perf/ldio/fetch-ldio.sh
node ldes-client/perf/ldio/measure-ldio.mjs --js-client ../ldes-client \
  --output ldes-client/target/perf/ldio.json --summary ldes-client/target/perf/ldio.md
```

[fetch-ldio.sh](ldio/fetch-ldio.sh) extracts LDIO from the official 3.2.2 image without Docker: the application and
the instrumentation, LDES client and no-op output connectors, from their image layers on Docker Hub, each checked
against its digest. [measure-ldio.mjs](ldio/measure-ldio.mjs) runs them as the image does
(`java -Dloader.path=lib/ -cp ldio-application.jar org.springframework.boot.loader.PropertiesLauncher`), but with the
local JVM. For every feed, it starts 3 fresh LDIO processes (`--processes`), and in each it creates 15 pipelines one
after the other (`--iterations`) through the admin API (`POST /admin/api/v1/pipeline`), each a new client with empty
state, and deletes each pipeline when it is done. A replication is timed from just before the pipeline is created until
LDIO's `ldio_data_out` counter of that pipeline (tag `pipeline.name`) reaches 20,000, polled every 10 ms (`--poll`).
The first pipeline of a process runs right after LDIO's start-up, before the JIT has compiled the client; pipelines 9
to 15 (`--skip-iterations 8`) run after start-up. LDIO does not check the members' content; the LDI client's content is
checked by `compare-clients.mjs`, and the count by the counter.

## Results, 19 November 2026

Intel Core i7-1265U (12 logical CPUs), Linux, Node.js 25.9.0 with the JavaScript client at `a527c36` (0.6.2), OpenJDK
21.0.13.1 with default JVM settings for both Java clients and LDIO, LDI and LDIO 3.2.2, this repository at `f94b99a`.
20,000 members, no request delay. Every case passed the full content check.

### After start-up

15 replications per process with a fresh client each, the first 8 not counted, the median of the remaining 7 in each
of 3 fresh processes per case (21 samples). For LDIO, 15 pipelines per LDIO process, the same way.

| Data pages × members/page | JavaScript, LDES 1.0 | LDI, LDES 1.0 | LDIO pipeline, LDES 1.0 | JavaScript, LDES 2.0 TriG | Java, LDES 2.0 TriG | JavaScript, LDES 2.0 Jelly | Java, LDES 2.0 Jelly |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 200 × 100 | 20,890 | 15,144 | 18,153 | 64,405 | 72,326 | 61,685 | 79,312 |
| 20 × 1,000 | 22,487 | 19,901 | 22,079 | 72,760 | 83,898 | 81,670 | 113,379 |
| 4 × 5,000 | 22,538 | 21,689 | 23,208 | 79,518 | 95,637 | 88,866 | 121,458 |
| 1 × 20,000 | 13,770 | 13,182 | 12,463 | 83,180 | 109,207 | 90,437 | 130,482 |

Members per second (multiply by 8 for triples per second).

* **This repository's client on LDES 2.0 replicates 4.2 to 8.3 times faster than the LDI client on LDES 1.0 in TriG,
  and 5.2 to 9.9 times faster in Jelly.** The gap is largest on the single page of 20,000 members.
* **Most of that is the specification, not the client.** The JavaScript client, which reads both, is 3.1 to 6.0 times
  faster on LDES 2.0 TriG than on LDES 1.0, with the same members. An LDES 1.0 page is one graph: the client has to
  parse the whole page before it can extract the first member (by its `tree:member` and the subject's description),
  so it holds the page in memory and the first member waits for the last byte. An LDES 2.0 page is a sequence of
  messages, each a member, which a client can hand over as soon as each is parsed.
* **Client against client on the same specification**, this repository's client is 1.12 to 1.31 times faster than the
  JavaScript client on LDES 2.0 TriG (1.29 to 1.44 in Jelly); the LDI client is 0.72 to 0.96 times as fast as the
  JavaScript client on LDES 1.0.
* **The LDIO pipeline adds no measurable cost**: the same client in LDIO runs within 0.95 to 1.20 times its speed on
  its own, which is within the noise of these runs. The client, not the pipeline, sets the throughput.
* **First member**: on the page of 20,000 members, 721 ms for the LDI client (744 ms in LDIO) and 1,091 ms for the
  JavaScript client on LDES 1.0, against 6 ms for this repository's client and 48 ms for the JavaScript client on
  LDES 2.0.
* **Memory**: on that page, the LDES 1.0 clients peak at 1.9 GB resident (LDI and JavaScript) and LDIO at 1.2 GB,
  against 0.26 GB (JavaScript) and 0.58 GB (Java, mostly heap sizing) on LDES 2.0.

### In a fresh process

One replication per fresh process, median of 5 rounds after 1 warmup. LDIO: the first pipeline after LDIO's start-up
(5.7 to 6.3 s, not included), median of 3 processes.

| Data pages × members/page | JavaScript, LDES 1.0 | LDI, LDES 1.0 | LDIO pipeline, LDES 1.0 | JavaScript, LDES 2.0 TriG | Java, LDES 2.0 TriG | JavaScript, LDES 2.0 Jelly | Java, LDES 2.0 Jelly |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 200 × 100 | 14,179 | 6,403 | 5,014 | 33,082 | 9,371 | 34,349 | 10,805 |
| 20 × 1,000 | 16,468 | 7,359 | 6,089 | 42,839 | 10,750 | 48,432 | 12,562 |
| 4 × 5,000 | 15,563 | 7,340 | 5,573 | 40,419 | 11,379 | 49,583 | 11,784 |
| 1 × 20,000 | 10,832 | 5,400 | 3,955 | 44,874 | 11,919 | 51,219 | 13,918 |

In a fresh JVM, both Java clients spend most of the replication loading classes and compiling: this repository's client
is 1.46 to 2.21 times faster than the LDI client in TriG, and 1.61 to 2.58 times in Jelly, and both are slower than
the JavaScript client in a fresh Node.js process. The first pipeline in a freshly started LDIO is 0.73 to 0.83 times as
fast as the LDI client in a fresh JVM of its own.

The fresh-process Java numbers of this run are up to 25% below those of 15 November below, on the same machine with a
newer JDK update; compare clients within one run.

### What these numbers do not cover

One machine, loopback HTTP without latency or compression, synthetic members, and a historical replication with
immutable pages. With request latency (`--delay`), the LDI client, which fetches one page at a time, would fall further
behind on feeds of many pages than the clients that read ahead; this was not measured. The LDIO numbers use the
in-memory state; the SQLite and PostgreSQL state of the LDI client was not measured.

## Method

Every replication runs in a fresh child process, Node.js or JVM. Before any timing, every case passes a full content
check: each member is compared with the fixture it was generated from (the Java children compare by RDF isomorphism with
members written from the same JavaScript fixture generator: a message log for this repository's client, one Turtle
member per line for the LDI client). Then one unreported warmup round and five measured rounds run, rotating the order
of the cases every round to spread drift over the clients.

* **Replication** is the time from just before the client is constructed until the last member has been consumed, in
  every child. It excludes Node.js module imports and JVM startup, but includes everything a fresh process does from
  there: in Java, loading and initializing Jena's classes and running unwarmed (interpreted) code. These are cold-process
  numbers, which is how a command line client or a short-lived job runs.
* **First member** is the time to the first member within the replication time.
* **Process** is the wall time of the whole child process, startup and exit included, as measured by the orchestrator.
* **Peak RSS** is the peak resident memory of the child (Node.js: `process.resourceUsage().maxRSS`; JVM: `VmHWM`).

One machine, local HTTP without compression or latency (unless `--delay`), synthetic members: the numbers describe the
cost of the clients, not of a deployment. Results depend on the JVM's heap and JIT defaults; `--java-opts` lets you vary
them.

## Earlier: JavaScript vs Java on LDES 2.0, 15 November 2026, in a fresh process

15 November 2026, Intel Core i7-1265U (12 logical CPUs), Linux, Node.js 25.9.0 with the JavaScript client at
`a8daa2b`, OpenJDK 21.0.13 with default JVM settings. 20,000 members of 8 quads, median of 5 rounds after 1 warmup,
no request delay. Both clients passed the full content check on every case.

| Layout | Format | JavaScript | Java | Java vs JS | First member, JS / Java | Peak RSS, JS / Java |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 200 pages × 100 | TriG | 0.621 s | 1.632 s | 0.38× | 134 / 504 ms | 194 / 470 MB |
| 200 pages × 100 | Jelly | 0.540 s | 1.490 s | 0.36× | 107 / 559 ms | 190 / 473 MB |
| 20 pages × 1,000 | TriG | 0.472 s | 1.438 s | 0.33× | 92 / 451 ms | 193 / 450 MB |
| 20 pages × 1,000 | Jelly | 0.421 s | 1.192 s | 0.35× | 104 / 521 ms | 183 / 435 MB |
| 4 pages × 5,000 | TriG | 0.443 s | 1.384 s | 0.32× | 93 / 430 ms | 189 / 448 MB |
| 4 pages × 5,000 | Jelly | 0.389 s | 1.271 s | 0.31× | 99 / 489 ms | 176 / 454 MB |
| 1 page × 20,000 | TriG | 0.422 s | 1.375 s | 0.31× | 86 / 438 ms | 182 / 457 MB |
| 1 page × 20,000 | Jelly | 0.349 s | 1.400 s | 0.25× | 92 / 517 ms | 175 / 449 MB |

**In a fresh process, the JavaScript client replicates these feeds 3 to 4 times faster than the Java client, with
less than half the memory.** Both clients read Jelly faster than TriG; the page size matters little beyond 100 members
per page.

Where the Java time goes:

* **JIT compilation.** A Flight Recorder profile of a 100,000-member Jelly run logged 1.3 s of C2 compiler-thread time
  during a 2.1 s replication, while the process averaged about 40% of the 12 CPUs for a single-threaded replication.
  The replication thread itself produced few execution samples; garbage collection paused for 44 ms in total. With only
  the C1 compiler (`--java-opts="-XX:TieredStopAtLevel=1"`), the 20 × 1,000 case drops from 1.44 to 0.94 s (TriG)
  and from 1.19 to 0.83 s (Jelly), at 0.46× of the JavaScript client, with 80 to 130 MB less memory.
* **Startup.** About 0.4 to 0.5 s pass before the first member, against 0.1 s in Node.js: loading and initializing
  Jena, RIOT, the SHACL path engine and Jelly in a cold JVM.
* **Not the client's steady state.** On a 100,000-member feed (100 pages of 1,000) the gap narrows to 0.53× (TriG) and
  0.60× (Jelly), because the JIT is still warming up during most of that run; see the next section for the clients
  after start-up.

So for a short replication from the command line, the JVM's cold start dominates, and the JavaScript client wins.

## Earlier: JavaScript vs Java on LDES 2.0, 15 November 2026, after start-up

Same machine and day, same feeds: 15 replications per process with a fresh client each, the first 8 not counted, the
median of the remaining 7 in each of 3 fresh processes per case (21 samples).

| Layout | Format | JavaScript | Java | Java vs JS | First member, JS / Java |
| --- | --- | ---: | ---: | ---: | ---: |
| 200 pages × 100 | TriG | 62,788 members/s (0.319 s) | 92,079 members/s (0.217 s) | 1.47× | 47 / 10 ms |
| 200 pages × 100 | Jelly | 64,036 members/s (0.312 s) | 96,181 members/s (0.208 s) | 1.50× | 46 / 9 ms |
| 20 pages × 1,000 | TriG | 74,808 members/s (0.267 s) | 102,436 members/s (0.195 s) | 1.37× | 49 / 9 ms |
| 20 pages × 1,000 | Jelly | 80,480 members/s (0.249 s) | 126,977 members/s (0.158 s) | 1.58× | 48 / 8 ms |
| 4 pages × 5,000 | TriG | 79,957 members/s (0.250 s) | 108,295 members/s (0.185 s) | 1.35× | 49 / 8 ms |
| 4 pages × 5,000 | Jelly | 89,097 members/s (0.224 s) | 147,091 members/s (0.136 s) | 1.65× | 48 / 7 ms |
| 1 page × 20,000 | TriG | 80,860 members/s (0.247 s) | 126,903 members/s (0.158 s) | 1.57× | 47 / 5 ms |
| 1 page × 20,000 | Jelly | 88,966 members/s (0.225 s) | 149,148 members/s (0.134 s) | 1.68× | 44 / 5 ms |

**After start-up, the Java client replicates 1.35 to 1.68 times faster than the JavaScript client**, and its first
member arrives in 5 to 10 ms instead of about 47 ms. The advantage is largest for Jelly, which the Java client decodes
with less work per member than TriG, and on large pages, where there are fewer requests per member. Both clients
roughly double their fresh-process throughput once warm; the JVM gains more because it starts further behind.

The Java processes peak at 570 to 760 MB resident, against 260 to 340 MB for Node.js. This is mostly heap sizing,
not live data: with default settings the JVM grows its heap towards a quarter of the machine's memory and keeps it
(the profile of a 100,000-member run used at most 261 MB of heap). `--java-opts="-Xmx256m"` bounds it.

Which client is faster thus depends on how long it runs: the JavaScript client for one-off replications of feeds of
this size, the Java client for anything that runs long enough to warm up, such as a following client or a pipeline.
These are single-machine numbers, on loopback HTTP without latency.
