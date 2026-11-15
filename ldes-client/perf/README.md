# LDES 2.0 replication: JavaScript client vs Java client

[compare-js.mjs](compare-js.mjs) replicates identical LDES 2.0 feeds with the JavaScript
[ldes-client](https://github.com/rdf-connect/ldes-client) and with the Java client of this repository, and reports
both side by side. It reuses the JavaScript client's own benchmark unchanged, so that the JavaScript client is measured
exactly as its own performance suite measures it:

* the fixtures of `perf/lib/fixtures.mjs`: members of 8 triples (a type, a timestamp, scalar properties and a nested
  blank node), published as LDES 2.0 pages in TriG (one message per member) and in Jelly-RDF (one frame per message);
* the same in-process HTTP server with pre-generated responses, so both clients read byte-identical pages;
* `perf/lib/replicate.mjs` as the JavaScript child process.

The Java side is [Replicate.java](../src/test/java/org/openldes/jena/messages/ldes/perf/Replicate.java), a child with
the same contract: the same checks (every member ID in range and not duplicated, 8 quads per member), a stream
high-water mark of 100, default concurrency (10 pages ahead) and the same JSON line. LDES 1.0 is not compared: the
Java client reads LDES 2.0 only.

## Run

Build both clients, then run from the root of this repository:

```sh
(cd ../ldes-client && npm ci && npm run build)
mvn -pl ldes-client -am package -DskipTests
node ldes-client/perf/compare-js.mjs --js-client ../ldes-client \
  --output ldes-client/target/perf/js-vs-java.json --summary ldes-client/target/perf/js-vs-java.md
```

| Option | Default |
| --- | --- |
| `--js-client PATH` | `../ldes-client`, a built checkout with its `perf/` directory |
| `--members N` | `20000` |
| `--page-sizes N,N,...` | `100,1000,5000,20000`, capped at the member count |
| `--formats trig,jelly` | both |
| `--repeats N` / `--warmups N` | `5` / `1` |
| `--delay MS` | `0`, server latency per request |
| `--iterations N` / `--skip-iterations M` | `1` / `0`: replications per process, and how many of them are warm-up |
| `--java-opts="..."` | none, JVM options for the Java child, with `=` because they start with a dash (for example `--java-opts="-Xmx1g"`) |
| `--output FILE` / `--summary FILE` | raw samples as JSON / the Markdown table |

A quick smoke run: `--members 300 --page-sizes 25,300 --repeats 1 --warmups 0`.

To measure the clients **after start-up**, let every process replicate the feed several times and skip the first
iterations:

```sh
node ldes-client/perf/compare-js.mjs --js-client ../ldes-client \
  --repeats 3 --warmups 0 --iterations 15 --skip-iterations 8
```

Every iteration builds a new client, with an empty bookmark, and replicates the whole feed again in the same process;
only the iterations after `--skip-iterations` are reported. The JavaScript side then runs
[replicate-warm.mjs](replicate-warm.mjs), which imports the JavaScript client's build and applies the same checks as its
`replicate.mjs` in a loop. Both clients settle after about 8 iterations of 20,000 members.

## Method

Every replication runs in a fresh child process, Node.js or JVM. Before any timing, every case passes a full content
check: each member is compared with the fixture it was generated from (the Java child compares by RDF isomorphism with
a message log written from the same JavaScript fixture generator). Then one unreported warmup round and five measured
rounds run, rotating the order of the cases every round to spread drift over both clients.

* **Replication** is the time from just before the client is constructed until the last member has been consumed, in
  both children. It excludes Node.js module imports and JVM startup, but includes everything a fresh process does from
  there: in Java, loading and initializing Jena's classes and running unwarmed (interpreted) code. These are cold-process
  numbers, which is how a command line client or a short-lived job runs.
* **First member** is the time to the first member within the replication time.
* **Process** is the wall time of the whole child process, startup and exit included, as measured by the orchestrator.
* **Peak RSS** is the peak resident memory of the child (Node.js: `process.resourceUsage().maxRSS`; JVM: `VmHWM`).

One machine, local HTTP without compression or latency (unless `--delay`), synthetic members: the numbers describe the
cost of the clients, not of a deployment. Results depend on the JVM's heap and JIT defaults; `--java-opts` lets you vary
them.

## Results in a fresh process

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

## Results after start-up

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
