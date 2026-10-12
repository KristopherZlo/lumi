# Release readiness

[Documentation home](README.md) / Release readiness

The release candidate targets Minecraft 1.21.11. This page distinguishes a
build dependency from a verified release combination.

| Minecraft | Loader | Fabric API | Builder tools | Evidence required |
| --- | --- | --- | --- | --- |
| 1.21.11 | 0.19.2 | 0.141.3+1.21.11 | None | Integrated and dedicated history, exact reopen, idle A/B |
| 1.21.11 | 0.19.2 | 0.141.3+1.21.11 | WorldEdit 7.4.2 | Real player edits, Save, Compare, Restore, exact reopen |
| 1.21.11 | 0.19.2 | 0.141.3+1.21.11 | Axiom 5.4.2 | Real integrated tool edits, Save, Compare, Restore, exact reopen |
| 1.21.11 | 0.19.2 | 0.141.3+1.21.11 | Both versions above | Combined tool workflows and two TCP clients |

JDK 21 is the runtime baseline. Tool artifacts are pinned in `gradle.properties`;
the test log records their resolved versions. No other Minecraft version has a
verified row. Newer compatible Loader/API releases permitted by installation
constraints need their own evidence; a successful compile does not add a row.

Run `scripts/run-release-gates.ps1` from a committed candidate. The runner pins
its Git SHA, records OS/CPU/GPU/dependencies, and keeps each gate's logs and
reports under a unique `build/reports/lumi-release/` directory. It runs core
checks, exact Restore, recovery, two clients, all four process-crash matrices,
load-regression checks, repeated Restore, and a 512×512×16 Save memory probe.
Pass `-FixtureManifest` and `-ExistingWorldSource` for the repeated Restore gate;
its source must be an immutable disposable fixture. `-Gate Multiplayer` selects
a focused run. Omitted gates remain unverified, and the overall run exits with a
failure until every required gate has evidence.

Automated Save memory sampling does not prove retained memory or a universal
size limit. Automated idle observation does not prove foreground cooling or
normal gameplay performance. Four reviewed gates remain explicit:

- `IdleAB`: repeat the same world, route, JVM, render settings and builder tools
  with Lumi and with the control client. Preserve warm-up, raw CPU/GPU/heap/FPS
  samples, distributions and the declared regression limits.
- `ForegroundCooling`: run at least 20 Initial ↔ Latest cycles on a real world,
  then 30 minutes of ordinary play. Record whether the measured client owns the
  foreground window, and measure time to return to the control distribution.
- `SaveRetainedBound`: compare multiple capture sizes at a fixed heap, analyze
  allocation peaks and live heap roots/dominators, and report the tested size
  envelope. Any capture redesign requires evidence that the current approach
  exceeds the agreed budget.
- `BuilderToolsIntegrated`: record the exact rows above with real tool entry
  points, complete state checks, reopen results, and all loaded mod versions.

Store each reviewed gate's measurements and conclusion in a JSON artifact. A
`-ReviewedEvidence` index has the candidate `gitSha` and a `gates` array. Each
entry has `name`, `status` (`passed`), `reviewedBy`, an absolute `artifact` path,
and its `sha256`. The runner validates candidate and artifact hashes and copies
the evidence. A reviewer must inspect the measurements; the index alone does
not establish a performance result. No reviewed evidence means no stable-release
claim, even when every automated command passes.

The [testing page](testing.md) records focused runs and their scope. A release
report must name failures and unverified gates and link the candidate manifest.
