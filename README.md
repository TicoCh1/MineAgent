# MineAgent

MineAgent is a Fabric mod for Minecraft 1.21.10 that experiments with agentic assisted building. It combines a WorldEdit-style sandbox/edit core with model-driven planning, visual feedback, block palette search, project design docs, structure tracing, and local MCP-compatible tool access.

## Current Highlights

- Hard sandbox boundary for agent-facing world edits, with strict, manual-expand, and auto-expand-through-air permission modes.
- Local browser Web UI launched from `//mineagent`, with per-player projects, continuation state, provider configuration, run control, and global/project sandbox configuration.
- Built-in OpenAI/Claude host loop with Codex-style visible progress planning and provider-hosted web search when supported.
- External local HTTP MCP endpoint for hosts such as Codex.
- Geometry and region tools: boxes, ellipsoids, cylinders, lines, curves, replace, move, copy, paste, stack, rotate, flip, undo, redo, and bounded WorldEdit-style `mineagent_gen`.
- Perception tools: raycast channels, virtual camera screenshots, and eight-direction sandbox isometric captures.
- Structure/design tools for component tracing, design docs, reference features/images, sequence optimization, and bbox similarity checks.

## Basic Use

```text
//mineagent
//mineagent tool
//mineagent agent start <build task>
//mineagent agent mcp start
```

The built-in host uses configured provider credentials or environment variables. The external MCP endpoint exposes MineAgent's local world tools/resources to an MCP-capable host while preserving the sandbox boundary.

## Development

Fixed stack:

- Minecraft 1.21.10
- Java 21
- Fabric Loader 0.19.2
- Fabric API 0.138.4+1.21.10
- Fabric Loom 1.16-SNAPSHOT
- Mojang mappings

Compile:

```text
./gradlew compileJava
```

Run the development client:

```text
./gradlew runClient
```

Detailed active-development notes are maintained in the workspace-level `docs/` directory.
