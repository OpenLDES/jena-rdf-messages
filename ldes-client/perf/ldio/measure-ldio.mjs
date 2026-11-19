// Replicates LDES 1.0 feeds with LDIO 3.2.2: the LDI LDES client in an LDIO pipeline (pipeline.yml: Ldio:LdesClient
// with its defaults, into Ldio:NoopOut). The feeds and the server are those of compare-clients.mjs, so the numbers
// line up with the LDI client on its own and with the other clients. See ../README.md. From the repository root:
//   ldes-client/perf/ldio/fetch-ldio.sh
//   node ldes-client/perf/ldio/measure-ldio.mjs --js-client ../ldes-client
//
// Every process is a fresh LDIO; in it, the same feed is replicated --iterations times, each time by a new pipeline
// (a new client with empty state), created through the admin API. The first pipeline of a process measures LDIO
// after Spring start-up but before the JIT has compiled the client; the pipelines after --skip-iterations measure it
// after start-up. A replication is timed from just before the pipeline is created until LDIO's ldio_data_out counter
// of that pipeline reaches the number of members, polled every --poll ms.
import http from "node:http";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawn, spawnSync } from "node:child_process";
import { createWriteStream, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { performance } from "node:perf_hooks";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

const here = path.dirname(fileURLToPath(import.meta.url));
const { values: options } = parseArgs({
    options: {
        "js-client": { type: "string", default: "../ldes-client" },
        ldio: { type: "string", default: path.resolve(here, "../../target/ldio") },
        members: { type: "string", default: "20000" },
        "page-sizes": { type: "string", default: "100,1000,5000,20000" },
        processes: { type: "string", default: "3" },
        iterations: { type: "string", default: "15" },
        "skip-iterations": { type: "string", default: "8" },
        poll: { type: "string", default: "10" },
        timeout: { type: "string", default: "300" },
        "java-opts": { type: "string", default: "" },
        output: { type: "string" },
        summary: { type: "string" },
    },
});
const members = Number(options.members);
const processes = Number(options.processes);
const iterations = Number(options.iterations);
const skipIterations = Number(options["skip-iterations"]);
const pollMs = Number(options.poll);
const timeoutMs = Number(options.timeout) * 1000;
const pageSizes = [...new Set(options["page-sizes"].split(",").map((s) => Math.min(Number(s), members)))];
if (![members, processes, iterations, pollMs, ...pageSizes].every((v) => Number.isSafeInteger(v) && v > 0) || !(skipIterations >= 0 && skipIterations < iterations))
    throw new Error("Members, page sizes, processes, iterations and poll must be positive, and --skip-iterations smaller than --iterations");

const ldio = path.resolve(options.ldio);
const jsClient = path.resolve(options["js-client"]);
for (const [file, hint] of [
    [path.join(ldio, "ldio-application.jar"), "ldes-client/perf/ldio/fetch-ldio.sh"],
    [path.join(ldio, "lib/ldio-ldes-client-jar-with-dependencies.jar"), "ldes-client/perf/ldio/fetch-ldio.sh"],
    [path.join(ldio, "lib/ldio-noop-out-jar-with-dependencies.jar"), "ldes-client/perf/ldio/fetch-ldio.sh"],
    [path.join(jsClient, "perf/lib/fixtures.mjs"), "the JavaScript ldes-client checkout with its perf/ directory"],
]) {
    if (!existsSync(file)) throw new Error(`Missing ${file}: ${hint}`);
}
const { createFixtures } = await import(pathToFileURL(path.join(jsClient, "perf/lib/fixtures.mjs")).href);
const pipelineTemplate = readFileSync(path.join(here, "pipeline.yml"), "utf8");
const applicationYml = path.join(here, "application.yml");

const java = spawnSync("java", ["-XshowSettings:properties", "-version"], { encoding: "utf8" }).stderr.match(/java\.runtime\.version = (.*)/)?.[1];
const metadata = {
    date: new Date().toISOString(),
    ldio: "openldes/ldi-orchestrator:3.2.2 (jars of the official image, local JVM)",
    java,
    platform: `${os.platform()} ${os.release()} ${os.arch()}`,
    cpu: os.cpus()[0].model,
    logicalCpus: os.cpus().length,
    members,
    quadsPerMember: 8,
    pageSizes,
    processes,
    iterationsPerProcess: iterations,
    skippedIterations: skipIterations,
    pollMs,
    javaOpts: options["java-opts"] || "defaults",
};

// The server of compare-clients.mjs, for the LDES 1.0 feeds only: Turtle pages marked immutable, and an
// ldes:versionOfPath on the event stream, which the LDI client requires.
let pages;
const server = http.createServer((request, response) => {
    const page = pages.get(request.url.split("?")[0]);
    if (!page) return response.writeHead(404).end();
    response.writeHead(200, { "content-type": "text/turtle", "cache-control": "public, max-age=604800, immutable" }).end(page);
});
await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
});

const freePort = () =>
    new Promise((resolve, reject) => {
        const probe = net.createServer().once("error", reject);
        probe.listen(0, "127.0.0.1", () => {
            const { port } = probe.address();
            probe.close(() => resolve(port));
        });
    });
const scratch = mkdtempSync(path.join(os.tmpdir(), "ldio-perf-"));

/** A fresh LDIO process, ready to take pipelines */
async function startLdio(label) {
    const port = await freePort();
    const log = path.join(scratch, `${label.replace(/[^a-z0-9]+/gi, "-")}.log`);
    const started = performance.now();
    const child = spawn(
        "java",
        [
            ...(options["java-opts"] ? options["java-opts"].split(" ") : []),
            "-Dloader.path=lib/",
            "-cp",
            "ldio-application.jar",
            "org.springframework.boot.loader.PropertiesLauncher",
            `--spring.config.location=file:${applicationYml}`,
            `--server.port=${port}`,
        ],
        { cwd: ldio, stdio: ["ignore", "pipe", "pipe"] },
    );
    const out = createWriteStream(log);
    child.stdout.pipe(out);
    child.stderr.pipe(out);
    let exited = false;
    child.on("exit", () => (exited = true));
    // Ready once LDIO's pipeline start-up service has run (after the web server and the health endpoint are up)
    let ready = false;
    let tail = "";
    child.stdout.on("data", (data) => {
        tail = (tail + data).slice(-1000);
        if (tail.includes("Finished LDI Orchestrator Spring Config Initializer")) ready = true;
    });
    const base = `http://127.0.0.1:${port}`;
    while (!ready) {
        if (exited) throw new Error(`LDIO exited during start-up, see ${log}`);
        if (performance.now() - started > timeoutMs) throw new Error(`LDIO did not start within ${timeoutMs / 1000}s, see ${log}`);
        await sleep(20);
    }
    return { child, base, log, startupSeconds: (performance.now() - started) / 1000 };
}

async function stopLdio({ child }) {
    const peakRss = (() => {
        try {
            return Number(readFileSync(`/proc/${child.pid}/status`, "utf8").match(/VmHWM:\s+(\d+)/)[1]) * 1024;
        } catch {
            return 0;
        }
    })();
    const exited = new Promise((resolve) => child.once("exit", resolve));
    child.kill("SIGTERM");
    await Promise.race([exited, sleep(10000).then(() => child.kill("SIGKILL"))]);
    return peakRss;
}

async function membersOut(base, name) {
    // The tag is pipeline.name (pipeline_name in the Prometheus output)
    const response = await fetch(`${base}/actuator/metrics/ldio_data_out?tag=pipeline.name:${name}`);
    if (response.status === 404) return 0; // the counter appears with the pipeline's sender
    if (!response.ok) throw new Error(`metrics: HTTP ${response.status}`);
    return (await response.json()).measurements.find((m) => m.statistic === "COUNT").value;
}

/** One replication: a new pipeline, timed until its last member is out, then deleted */
async function replicate(ldioProcess, url, iteration) {
    const { base, log } = ldioProcess;
    const name = `replicate-${iteration}`;
    const body = pipelineTemplate.replace(/^name: .*$/m, `name: ${name}`).replace(/^(\s+- )http:\/\/\S+$/m, `$1${url}`);
    const started = performance.now();
    const created = await fetch(`${base}/admin/api/v1/pipeline`, { method: "POST", headers: { "content-type": "application/yaml" }, body });
    if (!created.ok) throw new Error(`Creating ${name}: HTTP ${created.status} ${await created.text()}`);
    let firstMemberSeconds;
    let count = 0;
    while (count < members) {
        if (performance.now() - started > timeoutMs) throw new Error(`${name}: ${count} of ${members} members after ${timeoutMs / 1000}s, see ${log}`);
        await sleep(pollMs);
        count = await membersOut(base, name);
        if (count > 0) firstMemberSeconds ??= (performance.now() - started) / 1000;
    }
    const seconds = (performance.now() - started) / 1000;
    if (count !== members) throw new Error(`${name}: ${count} members out, expected ${members}`);
    await fetch(`${base}/admin/api/v1/pipeline/${name}`, { method: "DELETE" });
    return { iteration, members: count, seconds, firstMemberSeconds };
}

const median = (values) => {
    const sorted = [...values].sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
};

const result = { metadata, summary: [], samples: [], processes: [] };
try {
    const fixtures = await createFixtures(`http://127.0.0.1:${server.address().port}`);
    pages = fixtures.pages;
    const feeds = pageSizes.map((size, i) => {
        const name = `ldes1-${i}`;
        const url = fixtures.ldes1(name, members, size);
        for (const [key, page] of pages)
            if (key.startsWith(`/${name}/`))
                pages.set(key, page.replace("ldes:timestampPath dcterms:created ;", "ldes:timestampPath dcterms:created ; ldes:versionOfPath dcterms:isVersionOf ;"));
        return { layout: `${Math.ceil(members / size)} × ${size}`, url };
    });
    console.error(JSON.stringify(metadata));
    for (let round = 1; round <= processes; round++) {
        // Rotate the order of the feeds every round to spread drift
        const offset = (round - 1) % feeds.length;
        for (const feed of [...feeds.slice(offset), ...feeds.slice(0, offset)]) {
            const ldioProcess = await startLdio(`${feed.layout}-${round}`);
            try {
                for (let iteration = 1; iteration <= iterations; iteration++) {
                    const sample = await replicate(ldioProcess, feed.url, iteration);
                    console.error(`round ${round}: ${feed.layout}, pipeline ${iteration}: ${sample.seconds.toFixed(3)} s`);
                    result.samples.push({ layout: feed.layout, round, ...sample });
                }
            } finally {
                const peakRss = await stopLdio(ldioProcess);
                result.processes.push({ layout: feed.layout, round, startupSeconds: ldioProcess.startupSeconds, peakRss });
            }
        }
    }
    for (const { layout } of feeds) {
        const samples = result.samples.filter((s) => s.layout === layout);
        const first = samples.filter((s) => s.iteration === 1);
        const warm = samples.filter((s) => s.iteration > skipIterations);
        const runs = result.processes.filter((p) => p.layout === layout);
        result.summary.push({
            layout,
            firstPipelineSeconds: median(first.map((s) => s.seconds)),
            firstPipelineMembersPerSecond: members / median(first.map((s) => s.seconds)),
            firstPipelineFirstMemberSeconds: median(first.map((s) => s.firstMemberSeconds)),
            warmSeconds: median(warm.map((s) => s.seconds)),
            warmMembersPerSecond: members / median(warm.map((s) => s.seconds)),
            warmFirstMemberSeconds: median(warm.map((s) => s.firstMemberSeconds)),
            startupSeconds: median(runs.map((p) => p.startupSeconds)),
            peakRssMB: median(runs.map((p) => p.peakRss)) / 1e6,
        });
    }
} catch (error) {
    result.error = error.message;
    console.error(error);
} finally {
    server.close();
    server.closeAllConnections();
    if (!result.error) rmSync(scratch, { recursive: true, force: true });
}

function markdown() {
    const lines = [
        "## LDES 1.0 replication in LDIO 3.2.2 (Ldio:LdesClient into Ldio:NoopOut)",
        "",
        `${members} members of 8 triples. ${processes} fresh LDIO processes per feed, ${iterations} pipelines each, one after the other; ` +
            `first pipeline: the median of the first pipeline of every process; after start-up: the median of pipelines ${skipIterations + 1} to ${iterations} ` +
            `(${(iterations - skipIterations) * processes} samples). Counter polled every ${pollMs} ms. Java ${metadata.java}, ${metadata.cpu}.`,
        "",
        "| Layout | First pipeline | After start-up | First member, first / after start-up | LDIO start-up | Peak RSS |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ];
    for (const row of result.summary) {
        lines.push(
            `| ${row.layout} | ${Math.round(row.firstPipelineMembersPerSecond).toLocaleString("en")} members/s (${row.firstPipelineSeconds.toFixed(3)} s) | ` +
                `${Math.round(row.warmMembersPerSecond).toLocaleString("en")} members/s (${row.warmSeconds.toFixed(3)} s) | ` +
                `${(row.firstPipelineFirstMemberSeconds * 1000).toFixed(0)} / ${(row.warmFirstMemberSeconds * 1000).toFixed(0)} ms | ` +
                `${row.startupSeconds.toFixed(1)} s | ${row.peakRssMB.toFixed(0)} MB |`,
        );
    }
    if (result.error) lines.push("", `**Error:** ${result.error}`);
    return lines.join("\n");
}
const write = (file, content) => {
    mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
    writeFileSync(file, content);
};
if (options.output) write(options.output, JSON.stringify(result, null, 2) + "\n");
const report = markdown();
console.log(report);
if (options.summary) write(options.summary, report + "\n");
process.exitCode = result.error ? 1 : 0;
