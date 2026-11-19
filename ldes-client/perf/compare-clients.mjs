// Replicates the same members, published as LDES 1.0 and as LDES 2.0 feeds, with three clients: the JavaScript
// ldes-client (both versions), the LDES client of Linked Data Interactions (LDI, LDES 1.0) and this repository's Java
// client (LDES 2.0). See README.md in this directory for the method. Run from the repository root:
//   node ldes-client/perf/compare-clients.mjs --js-client ../ldes-client
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { execFileSync, spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { performance } from "node:perf_hooks";
import { parseArgs } from "node:util";

const { values: options } = parseArgs({
    options: {
        "js-client": { type: "string", default: "../ldes-client" },
        clients: { type: "string", default: "js,java,ldi" },
        members: { type: "string", default: "20000" },
        "page-sizes": { type: "string", default: "100,1000,5000,20000" },
        formats: { type: "string", default: "ldes1,trig,jelly" },
        repeats: { type: "string", default: "5" },
        warmups: { type: "string", default: "1" },
        delay: { type: "string", default: "0" },
        timeout: { type: "string", default: "180" },
        "java-opts": { type: "string", default: "" },
        iterations: { type: "string", default: "1" },
        "skip-iterations": { type: "string", default: "0" },
        output: { type: "string" },
        summary: { type: "string" },
    },
});
const members = Number(options.members);
const repeats = Number(options.repeats);
const warmups = Number(options.warmups);
const delayMs = Number(options.delay);
const timeoutMs = Number(options.timeout) * 1000;
const formats = options.formats.split(",");
// Replications per process; the first skip-iterations of every process are warm-up and are not reported
const iterations = Number(options.iterations);
const skipIterations = Number(options["skip-iterations"]);
if (!Number.isSafeInteger(iterations) || iterations < 1 || !Number.isSafeInteger(skipIterations) || skipIterations < 0 || skipIterations >= iterations)
    throw new Error("--iterations must be positive, and --skip-iterations smaller than it");
const warm = iterations > 1;
const pageSizes = [...new Set(options["page-sizes"].split(",").map((s) => Math.min(Number(s), members)))];
if (![members, repeats, ...pageSizes].every((v) => Number.isSafeInteger(v) && v > 0) || ![warmups, delayMs].every((v) => Number.isSafeInteger(v) && v >= 0))
    throw new Error("Members, page sizes and repeats must be positive; warmups and delay non-negative integers");
// The formats every client reads: ldes1 is the LDES 1.0 feed (TREE, Turtle), trig and jelly the LDES 2.0 feeds
const SUPPORTED = { js: ["ldes1", "trig", "jelly"], java: ["trig", "jelly"], ldi: ["ldes1"] };
const clients = options.clients.split(",");
if (!clients.every((c) => c in SUPPORTED)) throw new Error("Clients: js, java and/or ldi");
if (!formats.every((f) => ["ldes1", "trig", "jelly"].includes(f))) throw new Error("Formats: ldes1, trig and/or jelly");

const here = path.dirname(fileURLToPath(import.meta.url));
const module = path.resolve(here, "..");
const jsClient = path.resolve(options["js-client"]);
const jsDist = path.join(jsClient, "dist");
const jsChild = path.join(jsClient, "perf/lib/replicate.mjs");
const javaJar = path.join(module, "target/ldes-client.jar");
const javaTestClasses = path.join(module, "target/test-classes");
const ldiTarget = path.join(here, "ldi/target");
const required = {
    js: [
        [path.join(jsDist, "lib/client.js"), `cd ${jsClient} && npm ci && npm run build`],
        [jsChild, `the JavaScript ldes-client checkout with its perf/ directory`],
    ],
    java: [
        [javaJar, "mvn -pl ldes-client -am package -DskipTests"],
        [path.join(javaTestClasses, "org/openldes/jena/messages/ldes/perf/Replicate.class"), "mvn -pl ldes-client -am package -DskipTests"],
    ],
    ldi: [[path.join(ldiTarget, "classes/org/openldes/jena/messages/ldes/perf/ldi/ReplicateLdi.class"), "mvn -f ldes-client/perf/ldi/pom.xml package"]],
};
// The fixtures and the server always come from the JavaScript client's perf suite
for (const [file, hint] of [...required.js.slice(1), ...clients.flatMap((c) => required[c])]) {
    if (!existsSync(file)) throw new Error(`Missing ${file}: build it first (${hint})`);
}
const { createFixtures, member, turtlePrefixes } = await import(pathToFileURL(path.join(jsClient, "perf/lib/fixtures.mjs")).href);

const git = (dir) => {
    try {
        return execFileSync("git", ["-C", dir, "rev-parse", "--short", "HEAD"], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim();
    } catch {
        return null;
    }
};
/** The Java runtime version; java prints its settings on stderr */
function javaRuntime() {
    try {
        execFileSync("java", ["-XshowSettings:properties", "-version"], { stdio: ["ignore", "ignore", "pipe"] });
    } catch {
        return null;
    }
    const { stderr } = spawnSync("java", ["-XshowSettings:properties", "-version"], { encoding: "utf8" });
    return stderr.match(/java\.runtime\.version = (.*)/)?.[1] ?? null;
}
const metadata = {
    date: new Date().toISOString(),
    node: process.version,
    java: javaRuntime(),
    platform: `${os.platform()} ${os.release()} ${os.arch()}`,
    cpu: os.cpus()[0].model,
    logicalCpus: os.cpus().length,
    members,
    quadsPerMember: 8,
    pageSizes,
    clients,
    formats,
    ldiVersion: "3.2.2",
    repeats,
    warmups,
    delayMs,
    javaOpts: options["java-opts"] || "defaults",
    iterationsPerProcess: iterations,
    skippedIterations: skipIterations,
    jsClientCommit: git(jsClient),
    javaClientCommit: git(module),
};

// The server: pre-generated responses, in this process; the clients run in child processes.
// The LDES 1.0 pages (strings, Turtle) are marked immutable, as a server marks the pages of a closed history: the LDI
// client only ends a replication when all pages are immutable, and polls mutable pages again.
let pages;
const server = http.createServer((request, response) => {
    const page = pages.get(request.url.split("?")[0]);
    if (!page) return response.writeHead(404).end();
    const ldes1 = typeof page === "string";
    const { body, type } = ldes1 ? { body: page, type: "text/turtle" } : page;
    const headers = { "content-type": type, ...(ldes1 ? { "cache-control": "public, max-age=604800, immutable" } : {}) };
    const timer = setTimeout(() => response.writeHead(200, headers).end(body), delayMs);
    response.on("close", () => clearTimeout(timer));
});
await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
});

// The expected members, as a TriG message log, for the full content check of the Java client
const scratch = mkdtempSync(path.join(os.tmpdir(), "ldes-perf-"));
const expectedLog = path.join(scratch, "expected.trig");
writeFileSync(
    expectedLog,
    '@version "1.2-messages" .\n' + turtlePrefixes + Array.from({ length: members }, (_, i) => member(i)).join("\n@message .\n") + "\n",
);
// The same members for the LDI client, which runs on Jena 6.2 without RDF Messages: the prefixes, then one member per line
const expectedMembers = path.join(scratch, "expected-members.ttl");
writeFileSync(expectedMembers, turtlePrefixes + Array.from({ length: members }, (_, i) => member(i)).join("\n") + "\n");

function command(scenario, verify) {
    if (scenario.client === "js") {
        if (warm && !verify) return [process.execPath, [path.join(here, "replicate-warm.mjs"), jsDist, scenario.url, String(members), String(iterations)]];
        return [process.execPath, [jsChild, jsDist, scenario.url, "none", String(members), verify ? "verify" : "measure"]];
    }
    const [classpath, main, expected] =
        scenario.client === "java"
            ? [`${javaJar}${path.delimiter}${javaTestClasses}`, "org.openldes.jena.messages.ldes.perf.Replicate", expectedLog]
            : [
                  `${path.join(ldiTarget, "classes")}${path.delimiter}${path.join(ldiTarget, "lib/*")}`,
                  "org.openldes.jena.messages.ldes.perf.ldi.ReplicateLdi",
                  expectedMembers,
              ];
    const args = [
        ...(options["java-opts"] ? options["java-opts"].split(" ") : []),
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=error",
        "-cp",
        classpath,
        main,
        scenario.url,
        String(members),
        ...(verify ? ["verify", expected] : ["measure", String(iterations)]),
    ];
    return ["java", args];
}

function measure(scenario, verify = false) {
    const [executable, args] = command(scenario, verify);
    return new Promise((resolve, reject) => {
        const started = performance.now();
        const child = spawn(executable, args, { env: { ...process.env, LOG_LEVEL: "error" }, stdio: ["ignore", "pipe", "pipe"] });
        let out = "",
            err = "",
            timedOut = false;
        child.stdout.on("data", (d) => (out += d));
        child.stderr.on("data", (d) => (err = (err + d).slice(-5000)));
        const timer = setTimeout(() => {
            timedOut = true;
            child.kill("SIGKILL");
        }, timeoutMs);
        child.on("error", (error) => {
            clearTimeout(timer);
            reject(error);
        });
        child.on("close", (code) => {
            clearTimeout(timer);
            const processSeconds = (performance.now() - started) / 1000;
            if (timedOut) return reject(new Error(`${scenario.id}: timeout after ${timeoutMs / 1000}s`));
            if (code !== 0) return reject(new Error(`${scenario.id}: ${err || out || `exit ${code}`}`));
            try {
                const lines = out.trim().split("\n").filter((line) => line.startsWith("{")).map((line) => JSON.parse(line));
                if (!verify && lines.length !== iterations) throw new Error(`expected ${iterations} iterations, got ${lines.length}`);
                resolve(lines.map((line, i) => ({ iteration: i + 1, ...line, processSeconds })));
            } catch {
                reject(new Error(`${scenario.id}: invalid measurement output: ${out}`));
            }
        });
    });
}

const median = (values) => {
    const sorted = [...values].sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
};
const quantile = (values, q) => {
    const sorted = [...values].sort((a, b) => a - b);
    const position = (sorted.length - 1) * q;
    const low = Math.floor(position);
    return sorted[low] + (sorted[Math.ceil(position)] - sorted[low]) * (position - low);
};

const result = { metadata, summary: [], samples: [] };
const cases = [];
try {
    const fixtures = await createFixtures(`http://127.0.0.1:${server.address().port}`);
    pages = fixtures.pages;
    for (const [i, size] of pageSizes.entries()) {
        const layout = `${Math.ceil(members / size)} × ${size}`;
        for (const format of formats) {
            const name = `${format}-${i}`;
            const readers = clients.filter((c) => SUPPORTED[c].includes(format));
            if (!readers.length) continue;
            const url = await fixtures[format](name, members, size);
            // The LDI client requires ldes:versionOfPath on the event stream (LDES 1.0 makes it optional): announce one
            // for all clients. The members have no dcterms:isVersionOf, and no client materialises versions.
            if (format === "ldes1")
                for (const [key, page] of pages)
                    if (key.startsWith(`/${name}/`))
                        pages.set(key, page.replace("ldes:timestampPath dcterms:created ;", "ldes:timestampPath dcterms:created ; ldes:versionOfPath dcterms:isVersionOf ;"));
            const responseBytes = [...pages]
                .filter(([key]) => key.startsWith(`/${name}/`))
                .reduce((sum, [, p]) => sum + Buffer.byteLength(typeof p === "string" ? p : p.body), 0);
            for (const client of readers) cases.push({ id: `${layout}/${format}/${client}`, layout, format, client, url, responseBytes });
        }
    }
    console.error(JSON.stringify(metadata));
    for (const scenario of cases) {
        console.error(`validate: ${scenario.id}`);
        await measure(scenario, true);
    }
    // Rotate the order of the cases every round to spread drift over both clients
    for (let round = -warmups; round < repeats; round++) {
        const offset = (round + warmups) % cases.length;
        for (const scenario of [...cases.slice(offset), ...cases.slice(0, offset)]) {
            console.error(`${round < 0 ? "warmup" : `round ${round + 1}`}: ${scenario.id}`);
            const runs = await measure(scenario);
            if (round >= 0)
                for (const run of runs.slice(skipIterations)) result.samples.push({ id: scenario.id, round: round + 1, ...run });
        }
    }
    for (const scenario of cases) {
        const samples = result.samples.filter((s) => s.id === scenario.id);
        const seconds = samples.map((s) => s.seconds);
        result.summary.push({
            id: scenario.id,
            layout: scenario.layout,
            format: scenario.format,
            client: scenario.client,
            responseBytes: scenario.responseBytes,
            medianSeconds: median(seconds),
            q1Seconds: quantile(seconds, 0.25),
            q3Seconds: quantile(seconds, 0.75),
            membersPerSecond: members / median(seconds),
            firstMemberSeconds: median(samples.map((s) => s.firstMemberSeconds)),
            processSeconds: median(samples.map((s) => s.processSeconds)),
            peakRssMB: median(samples.map((s) => s.peakRss)) / 1e6,
        });
    }
} catch (error) {
    result.error = error.message;
    console.error(error);
} finally {
    server.close();
    server.closeAllConnections();
    rmSync(scratch, { recursive: true, force: true });
}

const CLIENT_NAMES = { js: "JavaScript", java: "Java (jena-messages)", ldi: "LDI" };
const FORMAT_NAMES = { ldes1: "LDES 1.0 Turtle", trig: "LDES 2.0 TriG", jelly: "LDES 2.0 Jelly" };
function markdown() {
    const lines = [
        `## LDES replication: JavaScript ldes-client, LDI LDES client, Java (jena-messages)`,
        "",
        (warm
            ? `${members} members of 8 quads, after start-up: ${iterations} replications per process, of which the first ${skipIterations} ` +
              `warm up and are not counted; median of the remaining ${iterations - skipIterations} × ${repeats} rounds, ` +
              "every replication with a fresh client. Process: wall time of the whole process, all iterations included. "
            : `${members} members of 8 quads, median of ${repeats} rounds after ${warmups} warmup${warmups === 1 ? "" : "s"}, `) +
            `${delayMs} ms delay per request. Node.js ${metadata.node}, Java ${metadata.java}, ${metadata.cpu}.`,
        "",
        "| Layout | Format | Client | Members/s | Replication | IQR | First member | Process | Peak RSS |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ];
    for (const row of result.summary) {
        lines.push(
            `| ${row.layout} | ${FORMAT_NAMES[row.format]} | ${CLIENT_NAMES[row.client]} | ${Math.round(row.membersPerSecond).toLocaleString("en")} | ` +
                `${row.medianSeconds.toFixed(3)} s | ${row.q1Seconds.toFixed(3)}–${row.q3Seconds.toFixed(3)} s | ` +
                `${(row.firstMemberSeconds * 1000).toFixed(0)} ms | ${row.processSeconds.toFixed(2)} s | ${row.peakRssMB.toFixed(0)} MB |`,
        );
    }
    // Members per second, one column per client and feed
    const columns = Object.keys(SUPPORTED)
        .flatMap((client) => SUPPORTED[client].map((format) => ({ client, format })))
        .filter(({ client, format }) => result.summary.some((r) => r.client === client && r.format === format));
    const layouts = [...new Set(result.summary.map((r) => r.layout))];
    lines.push(
        "",
        "Members per second:",
        "",
        `| Layout | ${columns.map(({ client, format }) => `${CLIENT_NAMES[client]}, ${FORMAT_NAMES[format]}`).join(" | ")} |`,
        `| --- | ${columns.map(() => "---:").join(" | ")} |`,
        ...layouts.map((layout) => {
            const cell = ({ client, format }) => {
                const row = result.summary.find((r) => r.layout === layout && r.client === client && r.format === format);
                return row ? Math.round(row.membersPerSecond).toLocaleString("en") : "";
            };
            return `| ${layout} | ${columns.map(cell).join(" | ")} |`;
        }),
    );
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
