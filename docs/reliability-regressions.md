# Reliability regression checks

Repository maintenance uses a shared coordinator for each normalized dimension
repository path. A publication lease covers reuse of immutable objects until
their durable origin or commit is written. Starting a publication permanently
invalidates any earlier GC snapshot. GC does not start a reachability pass while
a publication is active.

The maintenance monitor covers only one loose-file deletion or one pack/index
deletion pair. Reachability scanning, compression, publication and compaction do
not hold it. Ordinary world capture and server ticks do not acquire a publication
lease; the background data-persistence path owns that lease. Ref, commit,
tombstone and origin metadata publication also invalidates a pending sweep.

`RepositoryMaintenanceTest` checks cross-instance identity, dimension isolation,
nested publication lifetimes, stale snapshot rejection and exclusion between
pack/index removal and a new publication. Storage format, object identity,
retention cutoff and Restore durability barriers are unchanged.

Save holds the lease through object reuse, durable commit and ref publication. Origin
persistence holds it through both the object batch and origin-shard publication.
A superseded GC stops deleting and reports any removals already completed; a
later scheduled pass recomputes reachability. The 24-hour retention cutoff still
applies. Compaction retains its existing publish-before-delete protocol.
