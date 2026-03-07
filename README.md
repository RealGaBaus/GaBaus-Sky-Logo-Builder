# GaBaus Sky-logo Builder (For minecraft 1.21.1)
#### Make an issue or DM me on discord `bautti_.` with any questions (Check the FAQ first)
#### Pull Requests are welcome.

**Currently works on 8b8t, and 6b6t kitbots.**

## Features
### Modules
- **LogoBuilder**
  - The core module for building logos using Litematica schematics.
  - Supports automatic material management, Baritone integration, and hunger management.
  - Focuses on finishing one chunk at a time for efficiency.

- **AutoRestock (Advanced)**
  - Dual-mode restocker:
    - **Base Guardian Mode**: Standard stasis restocker. Handles pearl throwing, TP detection, and looting designated chests (Obsidian/Crying Obsidian).
    - **Kitbot Mode**: Modern restock via commands. 
      - **Safe Chunk Search**: Automatically finds a nearby 16x16 solid chunk to stand on before requesting the kit.
      - **Custom Commands**: Configurable command (e.g., `$kit obsidian 1`).
      - **Smart Timing**: Customizable delay (in minutes) and option to repeat the message until a new shulker is detected in the inventory.

- **Logo Breaker Beta**
  - Efficiently breaks logos.
  - Supports chunk-based operation and feet protection to avoid falling.
  - Filterable by block type (Obsidian/Crying Obsidian).

- **BaseGuardian**
  - Automated button interaction system.
  - Can be used for remote restock triggers and Anti-AFK movements.

## Installation & Setup
To use the addon correctly, it is recommended to use the provided schematic:
1. Go to the `extras/` folder in this repository.
2. Download `Stasis Chamber x10.litematic`.
3. Place the `.litematic` file in your `.minecraft/schematics` folder.
4. Use Litematica to load the schematic in-game.

### Schematic Previews
**Outside View:**
![Outside](extras/Stasis%20Chamber%20x10%20(1).png)

**Inside View:**
![Inside](extras/Stasis%20Chamber%20x10%20(2).png)

## FAQ
- Q: How do I install this / where is the jar file?
  - A: You can build the JAR using `./gradlew build` or download the latest release.
- Q: Why isn't mod x showing up?
  - A: Make sure you have the required dependencies. It is recommended to have [Meteor Client](https://meteorclient.com/), [Baritone](https://github.com/cabaletta/baritone), and [Litematica](https://www.curseforge.com/minecraft/mc-mods/litematica) installed.
- Q: Why is my game crashing?
  - A: Please make an issue or DM me the crash report found in .minecraft/crash-reports.

# [Meteor Client Snapshots](https://maven.meteordev.org/#/snapshots/meteordevelopment/)
