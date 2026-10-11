# Zone and selection interaction fixes

## Plan and safety boundaries

1. Validate an Enter target before changing existing actor membership. Correlate
   the client wait with the request ID and handle terminal refusal without
   reopening the menu.
2. While the wooden sword is held, route Undo/Redo only to selection history.
   An empty local history produces feedback rather than a world mutation.
3. Give zone See Changes an explicit server-validated scope. Keep preview
   preparation bounded and preserve the normal workspace preview path.
4. Enable zone Save/Amend from fresh exact zone pending statistics. Explain
   clean or still-calculating state and keep the server's validation authoritative.
5. Assign coincident shell geometry to one visible zone before rendering,
   preferring the active zone, then the entered zone, then a stable zone order.
   Retain each zone's metadata and exclusive geometry.

Each slice includes regression tests and a checkpoint commit. The final checks
include the full unit suite, build, existing performance gates, and native
client UI workflows with negative interaction coverage. Restore publication,
mutation freezes, immutable commit trees, zone membership rules, and file
formats retain their existing contracts. No new dependencies are required.

## Implementation and verification

### Enter target validation

The service loads the requested zone before removing the actor from any other
zone. A deleted or unreadable target now rejects the request without changing
the existing focus or its revision. The existing one-active-zone rule is
retained for a successful Enter. Regression tests cover a deleted target,
an unreadable target, and successful switching while preserving other actors.

This is a validation-order fix, not a new multi-file transaction guarantee:
the existing per-file atomic zone persistence remains in use.

### Correlated Enter UI

`ZoneEntryController` owns one pending request, its target, and its context.
Matching terminal failures release the wait and expose the server message;
unrelated events do not. Successful navigation still waits for an authoritative
snapshot with the target active. Disconnect or a workspace/dimension change
also releases the wait. The screen only coordinates the controller and widgets.
Regression tests cover refusal and retry, unrelated events, delayed activation,
context loss, and local send failure.

### Sword Undo/Redo

The dispatcher distinguishes a held selection tool from an exhausted local
history. Holding a wooden sword in either hand routes Undo/Redo exclusively
to the scoped selection history and uses the existing no-undo/no-redo messages
when empty. Without the tool, the same chord keeps its world-action behavior.
Tests exhaust real selection history in both directions and verify that putting
the sword away restores world Undo without consuming the local history.

Other slices and final check results will be recorded as they complete.

### Zone preview protocol

The separate zone preview response carries request, dimension, workspace,
head/ref revision, zone revision, and pending revision. It caps coordinates at
512 and carries no geometry on failure. This keeps the shared history snapshot
workspace-scoped. Codec and scope tests check round trips, negative coordinates,
invalid revisions, and response bounds. The protocol is being wired into the
existing command and overlay paths.

The server prepares zone-filtered preview coordinates on its background
executor. The zone predicate is applied before the 512-point cap. It checks
zone revision around preparation and history/pending context before sending;
failed or superseded requests cannot publish geometry. Preview requests remain
read-only and do not enter the mutation queue or freeze a dimension.

The client stores the selected zone preview separately from workspace pending
points. Waiting, failure, and stale zone/pending revisions display no zonal
geometry and never substitute workspace coordinates. History context changes
or leaving the zone clear the selection. Workspace See Changes explicitly
selects the workspace path; Action refresh retains a valid chosen zone.
