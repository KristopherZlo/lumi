# Interaction latency diagnostics

The GameTest-only `LumiInteractionTestDriver` sends one mouse press per chest
open, block break/place, or lever toggle. It requires the authoritative server
result and the visible client result, without retries. The released mod does
not include the timing mixin or any of these test classes.

Each event records whether the section needs an origin capture, server-handler
duration, press-to-handler start/completion, and server/client observation
latency. Inventory synchronization, teleport, aiming and vanilla mouse cooldown
are outside the measured interval. Observation includes polling and tick
scheduling; handler time and visible response time have different boundaries.

Compilation alone does not verify packet delivery, mouse input, or latency.
The lifecycle scenario and native execution evidence are recorded separately
when that gate has completed.
