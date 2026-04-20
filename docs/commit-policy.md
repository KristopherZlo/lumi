# Lumi commit policy

- Initialize the repository before any implementation work.
- Commit every 100-300 changed lines of code or earlier if a coherent vertical slice is complete.
- Split large changes into scaffold and implementation commits.
- Do not mix build, storage, UI and integration work in one commit when they can stand alone.
- Keep documentation updates in the same commit as the behavior or storage change they describe.
- Do not defer architecture notes, storage notes, or maintenance rules to a later cleanup commit.
- Prefer one coherent responsibility per commit to preserve OOP and SOLID boundaries in review history.
