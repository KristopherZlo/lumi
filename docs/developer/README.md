# Lumi developer documentation

Lumi is a Fabric mod that gives Minecraft builders a durable history of their world.
The server owns that history in singleplayer and multiplayer.

This guide explains the code, the development tools, and the rules that protect saved worlds.
You can read every linked document and source file from a GitHub checkout.

## Start here

1. Follow [Getting started](getting-started.md) to compile the mod and run the development client.
2. Read the [Project map](project-map.md) to understand the main concepts and find the relevant package.
3. Follow [Save, Compare, and Restore](workflows.md) through the client, server, and storage code.
4. Use [Making a change](contributing.md) for a small example and the review checklist.

Java knowledge helps with the examples.
The guide explains Lumi terms before it uses them.
You do not need to understand the entire Minecraft engine before you make a small change.

## Find an answer

| Question | Document |
| --- | --- |
| How do I build or run Lumi? | [Getting started](getting-started.md) |
| Which class should I open? | [Project map](project-map.md) |
| What happens after a player clicks Save? | [Workflows](workflows.md#save) |
| How does Compare reach the overlay renderer? | [Workflows](workflows.md#compare) |
| How does the client show Compare preparation progress? | [Highlight preparation](highlight-preparation.md) |
| How does Restore protect the previous world state? | [Workflows](workflows.md#restore) |
| Where does Lumi store history? | [Storage and recovery](storage.md) |
| Which tests should I run? | [Testing](testing.md) |
| How do I change a name rule or add behavior? | [Making a change](contributing.md) |
| Where are logs and test reports? | [Troubleshooting](troubleshooting.md) |

## The shared-world rule

Each dimension has one live workspace, one active branch, and one mutating history operation at a time.
Two players see the same physical world.
A branch switch changes that world for both players.

Save captures one frozen boundary, then releases the dimension before it writes the captured history.
Restore keeps the dimension frozen until exact durable verification finishes.
Read-only Compare can run against immutable versions without freezing the dimension.

Keep these rules in mind when you follow a workflow.
The [Project map](project-map.md#rules-that-changes-must-preserve) explains the remaining constraints.

## Scope

These pages describe the source in this checkout.
The dependency versions come from [gradle.properties](../../gradle.properties).
The task definitions come from [build.gradle](../../build.gradle).

For installation and player controls, read the [product README](../../README.md).
Update the relevant developer page when behavior, storage, architecture, or verification commands change.
