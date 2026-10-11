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

### Entity-only zone changes

Exact block counts alone cannot gate a zone Save: armor stands and other
entity-only builder edits must remain savable. Pending statistics now also
carry a bounded set of zones containing builder-dirty entity columns. Column
attribution runs off-thread through the existing ZoneScope contract and does
not add entity blob capture or decoding to the statistics tick path. Block
counts retain their existing meaning; an entity-dirty column is a conservative
pending signal, not an exact entity-difference count. A regression test verifies
entity-only scope attribution, including the existing whole-column behavior.
The statistics wire response adds this set; client and server must use the
same Lumi version.

The client associates statistics with the zone revisions present when the
request began. Resizing, adding, or deleting zones triggers a replacement even
if the builder generation did not change. Save eligibility uses only fresh
zone statistics and preserves the entity-pending signal. Existing workspace
counter display can still retain its last number during recalculation.

### Zone Save form

The Save controller revalidates zone eligibility before sending either Save
or a confirmed Amend. The form updates availability when statistics arrive,
even if the pending-key count stays unchanged. Submission uses the latest
observed zone revision. A clean zone and pending calculation have distinct
messages; entity-only pending work remains savable. Amend retains its existing
requirement for a saved zone version. Workspace Save keeps its existing gate.
The zone dashboard uses the same availability model and refreshes its controls
from the current page snapshot. Changes outside the zone cannot enable its
Save, Amend, or See Changes controls.

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
