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

Run the focused client scenario with JDK 21:

```powershell
.\gradlew.bat "-Dlumi.gametest.suite=INTERACTION" runClientGameTest --no-daemon
```

The scenario performs 120 actions: one first and five repeated presses for each
of four interaction types at five boundaries: fixture creation, Save, another
section, Restore, and world reopen. Fixture writes precede measurement;
`originNeeded` identifies actual first-touch captures. Reopen resets the dimension
runtime, but does not start a fresh JVM or provide a vanilla control.

Preparation closes delayed onboarding and establishes a grabbed mouse in the
simulated active test window. Minecraft otherwise consumes the first mouse press
to grab the cursor, before any interaction packet. Window-active state is restored
when the scenario ends. These setup steps are outside the measured interval.

Events are written under `build/reports/lumi-behavior/*-interaction-latency/`.
Individual events include handler and observation clocks; summaries report the
first sample and the median/maximum of five repeats per action and boundary.
One click must apply within 200 test ticks. Latencies are diagnostics rather than
a hardware-independent performance gate. Compilation alone does not verify
packet delivery, mouse input, or latency.
