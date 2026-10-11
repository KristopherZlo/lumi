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

Axiom remains installed. Its default Tinker and Bulldozer capabilities are
temporarily disabled and restored after the test. Tinker consumes empty-hand
right clicks before the vanilla use packet; this reproduced a failed chest-open
probe with one client use and no server handler. That setup failure is not
evidence of a Lumi history-capture delay. The trace records client attempts,
use results and received use packets to distinguish these cases.

Both chests start with seeded dungeon loot. First opening must generate contents
and clear the loot table, exercising block-entity mutation. The placement support
is in the section below the untouched target. First placement must require an
origin capture during the created and new-section phases, before breaking the
placed block. Save clears captured generations; it does not discard the durable
origin cache. Placement aims at the support block's top face.

Events are written under `build/reports/lumi-behavior/*-interaction-latency/`.
Individual events include handler and observation clocks; summaries report the
first sample and the median/maximum of five repeats per action and boundary.
One click must apply within 200 test ticks. Latencies are diagnostics rather than
a hardware-independent performance gate. Compilation alone does not verify
packet delivery, mouse input, or latency.

## Verified run: 2026-10-11

The final native run passed all 120 presses, seeded-loot assertions, both
first-origin placement assertions, Save/Restore boundaries, world reopen and
runtime-health checks. It used JDK 21, a 1-GiB client heap and two active JVM
processors, with the overloaded Oracle Java IDE process stopped.

The complete server-handler median was 0.393 ms and p95 was 0.666 ms. The two
first-origin placements took 1.317 and 0.666 ms. The initial chest open took
69.614 ms in the handler and 162.640 ms until client observation; later first
opens at the other boundaries took 0.461-1.479 ms in the handler. These are
whole Minecraft interaction measurements with Lumi, WorldEdit and Axiom loaded,
not isolated Lumi overhead. Tinker and Bulldozer were disabled by the test scope.
The fixture did not reproduce a need for repeated clicks. The initial chest
spike remains unattributed without a matching vanilla control and profiling.

Raw evidence: `build/reports/lumi-ui-audit/interaction-native-v19.log` and
`build/reports/lumi-behavior/20261011-152209-interaction-latency/events.jsonl`.
Normal observation samples were near 100 ms under the synchronized GameTest
tick/poll schedule; those clocks must not be read as 100 ms of Lumi CPU work.

Final verification also passed 923 unit tests and nine performance regression
tests, recompiled the GameTest sources, and built `remapJar`. Inspection of
`lumi-0.2.0-rc.1.jar` found no GameTest classes or timing mixins. Production
sources and persistence behavior were unchanged. Verification log:
`build/reports/lumi-ui-audit/interaction-final-verification.log`.
