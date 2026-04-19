<div align="center">

<img src="https://raw.githubusercontent.com/RealGaBaus/GaBaus-Sky-Logo-Builder/main/src/main/resources/assets/template/icon.png" width="20%"/>

# GaBaus Sky Logo Builder

<p>
Addon for Meteor Client that automatically builds logos/mapart from schematics with high reliability and anti-cheat compatibility.
</p>

<p>
  <img src="https://img.shields.io/github/stars/RealGaBaus/GaBaus-Sky-Logo-Builder">
  <img src="https://img.shields.io/github/forks/RealGaBaus/GaBaus-Sky-Logo-Builder">
  <img src="https://img.shields.io/github/issues/RealGaBaus/GaBaus-Sky-Logo-Builder">
  <img src="https://img.shields.io/github/last-commit/RealGaBaus/GaBaus-Sky-Logo-Builder">
  <img src="https://img.shields.io/github/downloads/RealGaBaus/GaBaus-Sky-Logo-Builder/total">
</p>

</div>

---

#### Make an issue or DM me on Discord `bautti_.` with any questions (check FAQ first)  
#### Pull Requests are welcome  

**Currently works on 8b8t and 6b6t kitbots.**

---

## ✨ Features

### Modules

- **LogoBuilder**
  - Core module for building logos using Litematica schematics  
  - Automatic material management  
  - Baritone integration  
  - Hunger management  
  - Chunk-by-chunk optimized building  

- **AutoRestock (Advanced)**
  - Dual-mode restocker:

  **Base Guardian Mode**
  - Stasis restock system  
  - Handles pearl throwing and TP detection  
  - Loots designated chests (Obsidian / Crying Obsidian)

  **Kitbot Mode**
  - Command-based restock  
  - Safe chunk finder (16x16 solid area)  
  - Custom commands (example: `$kit obsidian 1`)  
  - Smart delay + repeat until shulker detected  

- **Logo Breaker (Beta)**
  - Efficient logo breaking  
  - Chunk-based operation  
  - Feet protection  
  - Block filtering (Obsidian / Crying Obsidian)

- **BaseGuardian**
  - Automated button interaction  
  - Remote restock trigger  
  - Anti-AFK movement  

---

## ⚙️ Installation & Setup

To use the addon correctly, it is recommended to use the provided schematic:

1. Go to `extras/` folder  
2. Download `Stasis Chamber x10.litematic`  
3. Put it in `.minecraft/schematics`  
4. Load it using Litematica  

---

## 🖼️ Schematic Preview

### Outside
![Outside](extras/Stasis%20Chamber%20x10%20(1).png)

### Inside
![Inside](extras/Stasis%20Chamber%20x10%20(2).png)

---

## ❓ FAQ

- **How do I install / where is the jar?**  
  Build with:
  ```bash
  ./gradlew build
