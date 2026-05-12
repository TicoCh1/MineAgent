# MineAgent Project Instructions

## Project Purpose

MineAgent is a new large-scale Fabric mod for Minecraft builders. The goal is to build an agentic assisted-building mod for Minecraft 1.21.10, similar in spirit to WorldEdit, but driven by agentic workflows for planning, editing, and constructing structures.

## Mandatory Stack

These versions and choices are fixed for this project. Do not change them unless the user explicitly asks for a version change.

- Minecraft: `1.21.10`
- Java: `21` for compilation, with the local JDK expected to be Java `21.0.11`
- Loader: Fabric
- Fabric Loader: `0.19.2`
- Fabric API: `0.138.4+1.21.10`
- Fabric Loom: `1.16-SNAPSHOT`
- Mappings: official Mojang mappings via `loom.officialMojangMappings()`
- Mod version: `1.0.0`
- Maven group: `com.tico.mineagent`
- Root Gradle project name: `mineagent`
- Mod id: `mineagent`
- Mod display name: `MineAgent`

## Non-Negotiable Version Rule

Do not change Minecraft, Fabric Loader, Fabric API, mappings, Loom, Gradle wrapper, or Java target/source versions unless the user explicitly asks for that exact change.

If a tutorial, example, or reference uses Yarn mappings, translate every Minecraft symbol to Mojang mappings before using it in this codebase. Do not introduce Yarn mapping names into source code, comments, docs, mixins, or generated metadata unless the purpose is explicitly to document a mapping translation.

## Files To Inspect Before Code Edits

Before editing Java, JSON resources, Gradle files, mixins, or mod metadata, inspect these files in the project root:

- `build.gradle`
- `gradle.properties`
- `settings.gradle`
- `src/main/resources/fabric.mod.json`

Treat these files as the source of truth for project configuration.

## Current Configuration Notes

- `build.gradle` applies `net.fabricmc.fabric-loom-remap` with `${loom_version}` and `maven-publish`.
- `build.gradle` uses `loom.officialMojangMappings()`.
- `build.gradle` enables `splitEnvironmentSourceSets()`.
- The `mineagent` mod contains both `sourceSets.main` and `sourceSets.client`.
- `JavaCompile` uses `options.release = 21`.
- `sourceCompatibility` and `targetCompatibility` are both `JavaVersion.VERSION_21`.
- `processResources` expands `${version}` into `fabric.mod.json`.
- `fabric.mod.json` declares environment `"*"`.
- Main entrypoint: `com.tico.mineagent.MineAgent`.
- Client entrypoint: `com.tico.mineagent.client.MineAgentClient`.
- Main mixin config: `mineagent.mixins.json`.
- Client mixin config: `mineagent.client.mixins.json` with client environment.
- Runtime dependencies in `fabric.mod.json` require:
  - `fabricloader >=0.19.2`
  - `minecraft ~1.21.10`
  - `java >=21`
  - `fabric-api *`

## Source Set Boundaries

Respect the split environment source sets.

- Put shared/common or dedicated-server-safe logic under `src/main`.
- Put client-only code under `src/client`.
- Do not reference client-only Minecraft classes from common code.
- Keep entrypoints and mixins aligned with their declared environment.

## Reference Material

The workspace contains reference mods and Fabric reference material under `../git-reference`. These files are for learning and comparison only. Do not modify anything under `../git-reference` unless the user explicitly asks for a change there.

When borrowing from Fabric docs or examples, adapt the code to this project's versions, package names, mod id, and Mojang mappings.

## Development Discipline

- Preserve the existing Fabric project structure unless a requested feature requires a deliberate change.
- Keep code under the `com.tico.mineagent` package family unless the user requests otherwise.
- Prefer small, verifiable changes with focused tests or Gradle checks when practical.
- Avoid unrelated cleanup, metadata churn, or example-code rewrites during feature work.
- Never silently upgrade or downgrade dependencies to make an example compile.
