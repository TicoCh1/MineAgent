# MineAgent

Language / 语言: open the section you prefer below.

<details open>
<summary>English</summary>

## Overview

MineAgent is a Fabric mod for Minecraft 1.21.10 that experiments with agentic assisted building. It combines a WorldEdit-style sandbox/edit core with model-driven planning, visual feedback, block palette search, undo/redo, and a local MCP-compatible interface.

## Highlights

- Hard sandbox boundary for all agent-facing world edits.
- Built-in OpenAI/Claude host loop with stable prompt/tool ordering.
- Codex-style visible progress plan through the built-in `mineagent_update_plan` host tool.
- Local standard MCP endpoint for external hosts such as Codex or Claude.
- Tool-driven geometry edits: boxes, ellipsoids, cylinders, lines, curves, replace, move, copy, paste, stack, undo, and redo.
- Visual feedback through raycast channels, virtual camera screenshots, and eight-direction sandbox isometric captures.
- Block color/material palette query backed by `minecraft_block_color_palette.csv`.
- Read-on-demand MCP resources for sandbox, anchors, masks, recent history, capture index, and palette metadata.

## Basic Use

```text
//mineagent
//mineagent tool
//mineagent agent start <build task>
//mineagent agent mcp start
```

The built-in host uses your configured provider key for autonomous building. The external MCP endpoint exposes MineAgent's local world tools/resources to an MCP-capable host while preserving the sandbox boundary.

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

More detailed implementation notes are maintained in the workspace-level `docs/` directory during active development.

</details>

<details>
<summary>中文</summary>

## 概览

MineAgent 是一个面向 Minecraft 1.21.10 的 Fabric 建筑辅助 mod。它把类似 WorldEdit 的沙盒编辑能力和 agent loop 结合起来，让模型可以基于规划、视觉反馈、材质颜色表、撤销/重做和本地 MCP 兼容接口协助搭建。

## 主要能力

- 所有 agent 写入都会被硬限制在 MineAgent sandbox 内。
- 内置 OpenAI/Claude host loop，并保持稳定的 prompt 和工具顺序。
- 通过内置 host tool `mineagent_update_plan` 实现 Codex 风格的可见进度计划。
- 提供本地标准 MCP endpoint，可被 Codex、Claude 等外部 host 连接。
- 几何编辑工具：box、ellipsoid、cylinder、line、curve、replace、move、copy、paste、stack、undo、redo。
- 视觉反馈：raycast 通道图、虚拟相机截图、sandbox 八方向等距截图。
- 通过 `minecraft_block_color_palette.csv` 支持方块颜色/材质查询。
- MCP resources 按需提供 sandbox、anchors、masks、recent history、capture index、palette metadata 等状态。

## 基础使用

```text
//mineagent
//mineagent tool
//mineagent agent start <build task>
//mineagent agent mcp start
```

内置 host 使用你配置的模型供应商 key 驱动自动建筑。外部 MCP endpoint 会把 MineAgent 的本地世界工具和 resources 暴露给兼容 MCP 的 host，同时保留硬沙盒边界。

## 开发

固定技术栈：

- Minecraft 1.21.10
- Java 21
- Fabric Loader 0.19.2
- Fabric API 0.138.4+1.21.10
- Fabric Loom 1.16-SNAPSHOT
- Mojang mappings

编译：

```text
./gradlew compileJava
```

运行开发客户端：

```text
./gradlew runClient
```

更详细的实现说明会在活跃开发期间维护在工作区根目录的 `docs/` 目录下。

</details>
