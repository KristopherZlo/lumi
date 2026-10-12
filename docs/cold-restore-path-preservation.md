# Preserve the cold stored Restore path

Branch: `perf/cold-restore-pipeline`, baseline: `6e424fa7c` (`main`).

## Cause and selected change

Restore intent prewarm retained every chunk in the first decoded window,
including unloaded chunks eligible for direct vanilla-storage rewrites.
FULL generation and lighting-neighbor tickets also create surrounding chunk
holders. `ChunkLoadGate` must reject both live chunks and existing holders:
rewriting their storage would race vanilla loading or pending unload writes.
Those refusals propagate into subsequent windows as `RESIDENT` fallbacks.

The first baseline measurement applied 4,194,724 changes over 1,042 chunks,
including the 1,024-chunk dense fixture. Only 110 used stored rewrites;
932 fell back to loading. These are measured path counts, not evidence that
the load gate is unnecessary.

Prewarm now retains already-resident chunks and first-window chunks whose
decoded delta requires loading. A shared `DecodedSection.supportsStoredRewrite`
predicate keeps this decision aligned with the stored adapter: missing
directional deltas and changed POI still require loaded application.
An eligible cold chunk rejected later because its storage is missing,
unsupported, or concurrently resident still uses the existing loading fallback.

The change keeps transferred resident prewarm, fresh-return recomposition,
the 128-cold-chunk window and slab bounds, background decoding, entity cleanup,
the single final barrier, region force, exact durable reread, and ref publication
ordering. It does not change the persisted format or increase concurrency.

The first prewarm-only candidate did not materially improve three fresh-JVM
samples: 16.482, 15.077 and 16.561 seconds, with 110/142/142 stored chunks.
The remaining cause was the interaction with resident-window coalescing
(`8e688d1a2`): a window may contain resident chunks plus 128 cold chunks, but
`PreparedWorldMutationSession` marked classification complete after just one
128-total-chunk write batch. Bulk loading then retained the unclassified tail,
promoting eligible cold chunks and their surrounding holders to fallback.

Classification now traverses the complete window in sequential batches of at
most 128 before requesting any fallback tickets. The two limits keep their
original meanings; resident coalescing and cold-memory limits stay intact.
The new 34-resident/128-cold regression failed before this ordering correction.
It also holds the second write future incomplete to prove no loaded apply or
persistence starts while the tail is unclassified.

## History and alternatives

The retained block-ticket prewarm (`260a2b5cc`, `3ecd97360`) improved its historical
median from 5.853 to 4.109 seconds. Preserve that benefit for resident builders.
Entity prewarm (`cda1a694e`, reverted by `e71b5756a`) added heap without a gain.
Extra decode lookahead regressed stored application; origin packing
(`65d7e5a6e`, reverted by `29bf7c206`) regressed cold and warm Restore.
See [the investigation](performance-investigation.md) for their original contexts.

Ready-chunk write pipelining, bounded lookahead for necessary fallback loads,
and exact stored POI rewrites remain candidates. They need separate evidence;
first remove unnecessary loading before adding scheduling or storage machinery.

## Verification

Two new regression tests were run against unchanged production code and both
failed on unwanted prewarm tickets. They also cover transfer without mutation,
final durable verification, resident/POI selection across multiple sections,
negative coordinates and loading after a stored rejection.

Native comparisons use the existing immutable `build/cold-restore/fixture` and
its SHA-256 manifest. Every sample opens a new copied save in a fresh JVM and
performs Latest-to-Initial as its first mutation, then checks the exact endpoint
digest. Both variants use the same one-worker Gradle configuration and a 1-GiB,
two-processor Minecraft JVM. JVM-cold is not an operating-system disk-cache flush.
The JSONL report and properties retain separate preparation, readiness,
application, write, barrier/force, verification, tick and heap measurements.

Results will be recorded after the repeated comparison and recovery checks.

For native cold samples, `-Dlumi.benchmark.minimumStoredChunks=1024` requires
the full dense fixture to retain the stored path in addition to its exact digest.
Crash producers accept `-Dlumi.gametest.restoreCrashCold=true`: they add a
131,072-block, 32-chunk distant target/return pair, wait for vanilla unload,
complete intent prewarm, and assert no fixture chunk or holder was loaded.
Recovery and a second clean reopen must both match that target digest as well
as the original nearby blocks, armor stand, ref, journal and freeze checks.
