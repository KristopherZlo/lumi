# Reliability regression checks

Repository maintenance uses a shared coordinator for each normalized dimension
repository path. A publication lease covers reuse of immutable objects until
their durable origin or commit is written. Starting a publication permanently
invalidates any earlier GC snapshot. GC does not start a reachability pass while
a publication is active.

The maintenance monitor covers only one loose-file deletion or one pack/index
deletion pair. Reachability scanning, compression, publication and compaction do
not hold it. Ordinary world capture and server ticks do not acquire a publication
lease; the background persistence path owns that lease.

`RepositoryMaintenanceTest` checks cross-instance identity, dimension isolation,
nested publication lifetimes, stale snapshot rejection and exclusion between
pack/index removal and a new publication. Storage format, object identity,
retention cutoff and Restore durability barriers are unchanged.
