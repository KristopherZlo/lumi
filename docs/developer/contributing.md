# Making a change

[Documentation home](README.md) / Making a change

## Find the affected code

Use the [Project map](project-map.md) to find the owner.
Use the [workflows](workflows.md) to identify its callers and observable outcome.
Read the owner, its direct collaborators, and the matching tests.
Expand the search when a caller or test points to another package.

## Example: change a version name rule

1. Open [VersionDisplayName](../../src/main/java/io/github/lumi/domain/model/VersionDisplayName.java).
2. Open [VersionDisplayNameService](../../src/main/java/io/github/lumi/domain/service/VersionDisplayNameService.java).
3. Read [VersionDisplayNameServiceTest](../../src/test/java/io/github/lumi/domain/service/VersionDisplayNameServiceTest.java).
4. Inspect [VersionDisplayNameRepository](../../src/main/java/io/github/lumi/storage/repository/VersionDisplayNameRepository.java) before changing persisted bounds.
5. Read [VersionDisplayNameRepositoryTest](../../src/test/java/io/github/lumi/storage/repository/VersionDisplayNameRepositoryTest.java).
6. Find the rename callers in [LumiClientNetworking](../../src/main/java/io/github/lumi/client/LumiClientNetworking.java) and [LumiServerNetworking](../../src/main/java/io/github/lumi/network/LumiServerNetworking.java).

The model trims the value and checks length and control characters.
The service checks workspace ownership and rejects internal checkpoints.
The repository writes display metadata without rewriting the commit.
The protocol carries the request and its expected shared state.

A rule change may affect a form limit, wire limit, or persisted limit.
Trace those limits before editing only the model constant.

## Put each decision in its owner

| Decision | Owner |
| --- | --- |
| What makes a value valid? | Domain model |
| Is this operation allowed in the current project state? | Domain service |
| How does Minecraft capture or apply this state? | Minecraft adapter |
| How are bytes encoded and published? | Codec and repository |
| Which request and response cross the connection? | Network payload and handler |
| How does a screen collect input and show results? | UI controller and screen |

Use OOP and SOLID for new code and refactors.
Give each class one primary responsibility.
Prefer composition and explicit collaborators.
Keep interfaces narrow and mutable state focused.
Preserve abstraction contracts so another implementation can honor the same behavior.
Keep high-level workflow rules separate from low-level Minecraft and file details.

## Document the difficult parts

Write comments when they explain a reason or a constraint:

- Which thread may call a method.
- Who owns and closes a resource.
- Why a durable write must precede publication.
- Which generation, ref revision, or scope revision protects a change.
- What recovery must do after a partial failure.

Keep workflow explanations in these developer pages.
Keep player instructions in the product README.
Do not link public documentation to local notes or investigation files.
Use English for public documentation.

## Verify the change

1. Reproduce the old behavior with a focused test when you fix a bug.
2. Change the responsible class and its necessary collaborators.
3. Run the matching tests.
4. Compile all source sets after structural changes.
5. Run the relevant Minecraft gates from [Testing](testing.md).
6. Update documentation for behavior, architecture, storage, or command changes.
7. Inspect the diff for unrelated changes and generated files.

For shared-world changes, cover stale refs, permission loss, scope changes, and overlapping player requests.
Check disconnect behavior before and after world mutation starts.
For recovery, verify a second reopen after the operation completes.

## Commit and review

Commit every 100-300 changed lines of code, or earlier for a complete vertical slice.
Keep code, tests, and the documentation that describes them in the same commit.
Keep unrelated working changes outside that commit.

A review description should state:

- The trigger and the previous problem.
- The resulting behavior.
- The affected ownership or durability rules.
- The checks that ran and their results.
- Any required checks that did not run.

Before submitting, confirm that every documentation link opens from a clean Git checkout.
Check command names against `build.gradle` and `gradlew tasks --all` when verification instructions change.
