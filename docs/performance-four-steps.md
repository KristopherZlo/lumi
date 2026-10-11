# Four performance changes

Implementation follows the [cause investigation](performance-investigation.md).
Minecraft APIs, persistence formats, canonical object IDs, ref CAS, origin
durability and captured-generation boundaries remain the existing contracts.
Rust is outside this work.

## Restore persistence

`RestorePersistence` owns one worker task at a time. Production
`MinecraftWorldStateApply` supplies the existing background executor. Tests may
use the synchronous adapter default or a controlled executor.

Creation and advancement of the journal precede world apply. Verified world
persistence precedes publication; publication and working-index durability
precede runtime installation and background journal cleanup. The dimension
remains frozen until cleanup completes. Safe return and degraded journals use
the same worker. A tick may await the worker only through its remaining
deadline. Slow `force` calls therefore cannot extend that deadline.

`RestorePublication.publish` and `publishReturn` must use immutable prepared
state and thread-safe repositories/tracking. Minecraft runtime selection belongs
in `installed`, which runs on the server thread after durable publication.
Once a persistence task owns activation, cancellation is refused rather than
abandoning an in-flight journal. Shutdown joins the owned writer before closing
the prepared plan and leaves unfinished durable journals for recovery.

The regression controls every journal/publication/cleanup task and verifies
that world apply and freeze release cannot cross an unfinished write. A blocked
writer test measures a 5 ms tick wait independently of disk speed.

## Save metadata reads

Pack entry headers and compressed payloads are read in one contiguous buffer.
Indexed lengths are bounded before allocation, and entry lengths, LZ4 output,
content hashes, EOF and pack headers retain their validation.

Save sorts changed chunk metadata by the existing physical pack order one region
at a time (at most 1024 chunks). A Save-local LRU reuses only successfully
verified immutable chunk trees, with ceilings of 256 nodes and 4096 child
references. Oversized nodes bypass the cache; failures are never cached. Restore
verification readers remain fresh. No extra parallel decoding tasks are added.

## Working-index coalescing

Production groups ordinary index scheduling for up to 50 ms using a delayed
executor, without sleeping on a persistence worker. The writer still snapshots
the newest complete working/builder state and verifies durable origins before
each atomic forced write. Only one writer owns the index.

A Save/Restore durability boundary, captured-generation clear, recovery tracking,
explicit durability wait or blocked vanilla publication bypasses the delay.
Urgency survives an origin that has not finished yet. An already queued timer
cannot dispatch the same writer again after an urgent flush. Chunk retention
and publication gates still require the actual durable generations, not timer
completion. Existing synchronous/test adapters retain zero-delay scheduling.

## Verification

After the Restore slice, the targeted Restore/publication tests and
`compileGametestJava` passed. Native measurements and real crash results will
be recorded here after all four slices are implemented.
