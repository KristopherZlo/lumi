# GBreak Dev

Dev-only Fabric mod for Lumi capture scenarios.

## Lumi demo UI

- Press `O` in-game to open the Lumi-style demo project screen.
- Run `/gbreakui` on the client to open the same screen without the keybind.
- Use `/gbreak off` as the single command for disabling injected bugs.
- Most action buttons are intentionally fake and only close the UI.
- `Restore` and `Restore last save` also trigger `/gbreak fakerestore`, which replaces random nearby blocks with glass over several ticks.

## Notes

- The screen ships with two fake commits by default so the history view looks populated.
- The restore effect is a visual demo only. It does not use Lumi project history or real rollback data.
