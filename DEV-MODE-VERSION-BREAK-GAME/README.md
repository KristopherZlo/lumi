# GBreak Dev

Dev-only Fabric mod for Lumi capture scenarios.

## Lumi demo UI

- Press `O` in-game to open the Lumi-style demo project screen.
- Run `/gbreakui` on the client to open the same screen without the keybind.
- Use `/gbreak off` as the single command for disabling injected bugs.
- Use `/corrupt on` to maintain a stable world-space simplex-noise mask of missing-texture world blocks out to the active server view distance, spawn basalt-delta-style ash particles, and spawn jittering display-only `block_display` glitches in the sky with both missing-texture and normal block states; `/corrupt off` removes sky glitches and queues restoration of the captured original block states.
- Press `K` or run `/corruptsettings` to open the live corruption settings menu. It does not pause the game, and slider changes apply immediately.
- Most action buttons are intentionally fake and only close the UI.
- `Restore` and `Restore last save` also trigger `/gbreak fakerestore`, which replaces random nearby blocks with glass over several ticks.

## Notes

- The screen ships with two fake commits by default so the history view looks populated.
- The restore effect is a visual demo only. It does not use Lumi project history or real rollback data.
