# Post-Restore client load investigation

Date: 2026-10-12. Production baseline: `7360458a0` on
`perf/cold-restore-pipeline`.

Status: the reported sustained load in an ordinary visible client is not yet
explained. No production code was changed during this investigation.

## Reproduction requested by the builder

The problem begins after entering a world and repeatedly restoring history.
The comparison is the same development client without Lumi. A simple startup
or idle comparison does not reproduce the requested workload.

The original `run/logs/latest.log` identifies this sequence in world `710`:

1. Initial: `a8e895feaeeaf7ebc23ce8d6a445e0e57a5a5dc26848ecc628091e5b053340e9`.
2. `grass-color-test`: `87de576b68b89a86a8446d039659a1454ca418ebc7a6bde8fb7d4eb87fbb1dda`.
3. `prud`: `f4cc2381bd0d6a4bbf5d526e820649453e9ec17d1e414b9572c7a9a21a9829ef`.
4. Return to `grass-color-test`.

## Completed automatic diagnostic

An ordinary Knot client, rather than the client GameTest runner, opened an
isolated copy of the same world and sent normal Lumi prewarm/Restore packets.
All four requests succeeded. SHA-256 checks confirmed that the original world
files stayed unchanged.

The original options remain 260 FPS, VSync off, render distance 16 and simulation
distance 12. The isolated diagnostic kept ticking without focus, hid its window
to prevent input interference, and changed its own AFK policy to prevent a later
automatic FPS reduction. Its measured framebuffer was 1920 by 1061. These changes
limit any conclusion about foreground rendering, GPU clocks or cooling.

Each sample lasted approximately 20 seconds. The final sample followed a
30-second wait after the immediate post-Restore sample:

| Sample | Process CPU, normalized across 8 logical CPUs | Mean FPS | Mean process 3D utilization |
| --- | ---: | ---: | ---: |
| Before Restore sequence | 33.7% | 143.7 | 38.1% |
| Immediately after sequence | 12.4% | 175.4 | 47.4% |
| After waiting | 9.7% | 166.6 | 46.1% |

The number of server chunk holders temporarily rose from 3,481 to 3,946 and
returned to 3,481. These are holder counts, not counts of fully loaded terrain.
Lumi load tickets were zero at each sampled boundary. All dimension operation
queues were empty and no mutation was active after completion. Durability
tickets were still present: 13 at the start and 28 at the end of the final
sample. Their presence alone does not establish a leak.

In the final 20-second sample, the render thread consumed 11.625 CPU seconds,
the server thread 1.687, and the two Lumi durability threads 0.342 combined.
The CPU did not remain elevated in this automatic run. GPU utilization and FPS
both increased; this is not an energy measurement or proof of a rendering leak.

Earlier visible runs were contaminated by container/inventory/dashboard input
and additional Restore requests. They must not be used as an idle A/B result.
The first successful hidden run used a smaller third-commit difference and was
followed by the complete sequence above.

## Artifacts and next measurement

Raw profiles, scalar results, GPU samples, original-world hashes and the
disposable probe are under `build/reports/lumi-client-load/`. The complete
sequence is `restore-20261012-082047`; `summary.csv` contains its aggregate data.
These artifacts are removed by `clean` and are not shipped with Lumi.

`build/reports/lumi-client-load/watch-live.ps1` waits for the ordinary client
and three completed Restore operations, then records JFR and process CPU/GPU
for 60 seconds. It does not change graphics or close Minecraft. The script was
syntax checked; its ordinary-client end-to-end capture still needs to run while
the builder reproduces the sustained cooling behavior. No graphics workaround
or production fix is justified by the current evidence.
