# GBreak Dev

Dev-only Fabric mod for Lumi capture scenarios.

## Lumi demo UI

- Press `O` in-game to open the Lumi-style demo project screen.
- Run `/gbreakui` on the client to open the same screen without the keybind.
- Use `/gbreak off` as the single command for disabling injected bugs.
- Use `/corrupt on` to plan a stable world-space simplex-noise density mask projected onto the visible terrain surface, apply those ground changes in batches with restorable missing-texture blocks that leak random light levels, spawn upward ash from the ground, spawn slow upward-floating non-glowing mobs/entities and 3x3/5x5 rising terrain slices, offset the current time of day by -2000 to +2000 ticks with a visible particle flash every 1-3 seconds, and spawn jittering display-only `block_display` glitches in the sky with both missing-texture and normal block states; `/corrupt off` removes sky glitches and restores the captured original block states as an outward wave from the corruption center, briefly drawing a client-side white shader fade over restored block surfaces.
- Press `K` or run `/corruptsettings` to open the live corruption settings menu. It does not pause the game, slider changes apply immediately, and the menu exposes the current world-space mask, ash, sky display, `/corrupt off` cleanup delay, cleanup spread speed, and white fade duration settings.
- Most action buttons are intentionally fake and only close the UI.
- `Restore` and `Restore last save` also trigger `/gbreak fakerestore`, which replaces random nearby blocks with glass over several ticks.

## Notes

- The screen ships with two fake commits by default so the history view looks populated.
- The restore effect is a visual demo only. It does not use Lumi project history or real rollback data.
- Interaction ghost blocks suppress client-side vanilla placement prediction and render as slightly oversized `block_display` overlays to avoid z-fighting flicker with the real block face.
