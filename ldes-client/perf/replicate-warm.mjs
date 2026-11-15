// Replicates the same feed several times in one Node.js process, with a fresh client every time, so that the later
// iterations measure the JavaScript client after start-up. The checks are those of perf/lib/replicate.mjs of the
// JavaScript ldes-client. Prints one JSON line per iteration.
//   node replicate-warm.mjs <dist> <url> <expected members> <iterations>
import path from "node:path";
import { pathToFileURL } from "node:url";
import { performance } from "node:perf_hooks";

const [, , dist, url, expected, iterations] = process.argv;
const { replicateLDES } = await import(pathToFileURL(path.join(dist, "lib/client.js")).href);

for (let iteration = 1; iteration <= Number(iterations); iteration++) {
    const seen = new Uint8Array(Number(expected));
    let firstMemberSeconds;
    const started = performance.now();
    const client = replicateLDES({ url }, "none");
    let members = 0;
    for await (const item of client.stream({ highWaterMark: 100 })) {
        firstMemberSeconds ??= (performance.now() - started) / 1000;
        const id = Number(item.id.value.replace("http://example.org/m", ""));
        if (!Number.isInteger(id) || id < 0 || id >= seen.length || seen[id]) throw new Error(`Invalid/duplicate member: ${item.id.value}`);
        seen[id] = 1;
        if (item.quads.length !== 8) throw new Error(`Member ${id}: expected 8 quads, got ${item.quads.length}`);
        members++;
    }
    if (members !== Number(expected)) throw new Error(`Expected ${expected} members, got ${members}`);
    const seconds = (performance.now() - started) / 1000;
    console.log(JSON.stringify({ iteration, members, seconds, firstMemberSeconds, peakRss: process.resourceUsage().maxRSS * 1024 }));
}
process.exit(0);
