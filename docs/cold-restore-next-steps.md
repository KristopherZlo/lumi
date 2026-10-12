# Cold Restore: next experiments

This is a proposal based on the current pipeline and its recorded experiments,
not a measured speedup of the current release. The four recent performance fixes
have not been followed by a new repeated large cold-Restore comparison.

The subsequent implementation and comparison are tracked in
[Preserve the cold stored Restore path](cold-restore-path-preservation.md).

Current Restore already has physically ordered immutable-tree reads with one
helper session, a bounded section-payload cache, two estimated 64-MiB native
slabs, at most 128 cold chunks per window, transferable first-window block
prewarm, unloaded-chunk rewrites, and one final persistence barrier with exact
durable reread. Reimplementing those mechanisms is not a new optimization.

1. **Overlap readiness for subsequent fallback windows.** Classify chunks before
   prefetching: retain eligible unloaded chunks on the stored-write path, and
   prewarm only the chunks that must load. Share the existing cold-ticket budget
   between the current and following windows. The current intent prewarm covers
   the first window and additional already-resident chunks; it does not provide
   general lookahead over subsequent cold fallback windows. Measure the tradeoff
   against the current region-local ordering and memory bounds.
2. **Extend stored rewrites to POI changes.** `MinecraftStoredChunkAccess.prepare`
   falls back to loading terrain when a delta changes points of interest, such as
   villager workstations. A future stored path must update and reread the chunk
   and POI files together, keep the load gate through durable verification, and
   prove exact POI removal/replacement under process crashes. Entity identity and
   legacy cleanup require their own treatment; they are not a trivial extension.
3. **Reduce remaining cold payload I/O only where traces justify it.** Count
   section-payload cache misses, pack reopens, repeated immutable IDs, and
   preparation/read/write/barrier/reread time. Existing tree read ordering,
   contiguous pack-entry reads, and scoped region forces must remain in the
   baseline. Additional caching or read batching must retain object validation
   and a fixed memory budget.

Prior attempts matter. Block-ticket prewarm (`260a2b5cc`, `3ecd97360`) improved
the historical median from 5.853 to 4.109 seconds. Entity-ticket prewarm
(`cda1a694e`, reverted by `e71b5756a`) measured 4.156 seconds and increased heap
without improving readiness. Extra decode lookahead also regressed the stored
workload. Save-time origin packing (`65d7e5a6e`, reverted by `29bf7c206`) worsened
cold and warm Restore. These are historical measurements, not today's results.

Compare identical immutable fixtures in separate fresh JVMs, with repeated
loaded and stored workloads and exact endpoint digests. Report preparation,
chunk readiness, loaded/stored counts, writes, barrier, force, reread, full ticks
and heap separately. Candidates must also pass process-crash and exact POI/entity
coverage. A three-second median is not established by the existing evidence.
