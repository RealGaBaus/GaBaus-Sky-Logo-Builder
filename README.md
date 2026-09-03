<div align="center">

<!-- Logo and Title -->
<img src="https://raw.githubusercontent.com/RealGaBaus/GaBaus-Sky-Logo-Builder/master/src/main/resources/assets/template/icon.png" alt="logo" width="20%"/>

<h1>GaBaus Sky-logo Builder</h1>

<p>
GaBaus Sky-logo Builder is an addon for the Meteor Client that allows you to automatically build sky-logos from schematics.
</p>

<p>
  <img src="https://img.shields.io/github/v/release/RealGaBaus/GaBaus-Sky-Logo-Builder">
  <img src="https://img.shields.io/github/downloads/RealGaBaus/GaBaus-Sky-Logo-Builder/total?cacheSeconds=60">
  <img src="https://img.shields.io/github/stars/RealGaBaus/GaBaus-Sky-Logo-Builder">
  <img src="https://img.shields.io/github/forks/RealGaBaus/GaBaus-Sky-Logo-Builder">
  <img src="https://img.shields.io/github/last-commit/RealGaBaus/GaBaus-Sky-Logo-Builder">
  <img src="https://img.shields.io/github/issues/RealGaBaus/GaBaus-Sky-Logo-Builder">
</p>

</div>

# GaBaus Sky-logo Builder (For minecraft 1.21.1 - 1.21.4 - 1.21.11)

#### If you have any questions, open an issue or DM me on Discord: `bautiii.__.`

**Currently works on 8b8t, and 6b6t kitbots.**

## Features

### Modules

- **LogoBuilder**
  - The core module for building logos using Litematica schematics.
  - Supports automatic material management, Baritone integration, and hunger management.
  - Focuses on finishing one chunk at a time for efficiency.

- **AutoRestock (Advanced)**
  - Dual-mode restocker:

  - **Base Guardian Mode**
    - Standard stasis restocker. Handles pearl throwing, TP detection, and looting designated chests (Obsidian/Crying Obsidian).

  - **Kitbot Mode**
    - Modern restock via commands.
    - **Safe Chunk Search**: Automatically finds a nearby 16x16 solid chunk.
    - **Custom Commands**: (e.g., `$kit obsidian 1`)
    - **Smart Timing**: Delay + repeat until shulker detected.

- **Logo Breaker**
  - Breaks logos or wrong schematic blocks.
  - Chunk-based snake pattern.
  - Litematica mode: removes extra blocks, wrong types, and unwanted air.
  - Block filter support.

- **BaseGuardian**
  - Automated button interaction system.
  - Remote restock trigger and Anti-AFK.

## Installation & Setup (8b8t)

To use the addon correctly:

1. Go to the `extras/` folder  
2. Download `Stasis Chamber x10.litematic`  
3. Place it in `.minecraft/schematics`  
4. Load it using Litematica  

### Schematic Previews

**Outside View:**  
![Outside](extras/Stasis%20Chamber%20x10%20(1).png)

**Inside View:**  
![Inside](extras/Stasis%20Chamber%20x10%20(2).png)

## FAQ

- Q: How do I install this / where is the jar file?  
  - A: Build with `./gradlew build` or download the latest release.

- Q: Why isn't mod x showing up?  
  - A: Make sure you have Meteor Client, Baritone, and Litematica installed.

- Q: Why is my game crashing?  
  - A: Send the crash report from `.minecraft/crash-reports`.

# [Meteor Client Snapshots](https://maven.meteordev.org/#/snapshots/meteordevelopment/)
