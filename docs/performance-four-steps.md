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

## Verification

After the Restore slice, the targeted Restore/publication tests and
`compileGametestJava` passed. Native measurements and real crash results will
be recorded here after all four slices are implemented.
