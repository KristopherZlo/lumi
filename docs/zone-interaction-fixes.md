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

Other slices and final check results will be recorded as they complete.
