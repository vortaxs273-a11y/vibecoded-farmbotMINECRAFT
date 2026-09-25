# FarmBot

> farm farm farm farm farm </im_end/>

A Fabric client mod for **Minecraft 26.1.2** that turns your player into a fully autonomous farmer. You type
`/farmbot start` and it takes over. It finds untouched land, gears itself up from nothing, builds a huge wheat
farm, and then farms. Forever. It only wants to farm.

**Requires [Baritone](https://github.com/cabaletta/baritone/releases) 1.18.0 for 26.1** (`baritone-api-fabric-1.18.0.jar`
in your mods folder). Baritone does the walking, digging to reach things and legit ore mining; FarmBot does the
planning, crafting, farming, safety and anti-grief. Blocks are only ever broken with the crosshair on a face that's
actually in view, through the vanilla breaking path, so it never breaks anything through walls.

## What it does, start to finish

1. **Scouts for untouched land.** It reads every loaded chunk and looks for a big square (6x6 chunks by default)
   of flat, dry ground with a clean margin around it. "Untouched" means no player-made blocks at all: no planks,
   glass, torches, chests, farmland, cobble, paths, beds, rails and so on (≈100 block families). It also avoids
   anywhere it has seen another player. If nothing in view qualifies, it walks 160 blocks in a new direction and
   looks again.
2. **Gears up from nothing.** Punches trees → planks/sticks/table → wooden pick → stone → stone pick, sword, axe,
   shovel, hoe → furnace at home → **iron**: Baritone legit-mines it (`legitMine`: only ores it has actually seen,
   branch mining at Y=16 otherwise, no x-ray tunnels), then it smelts it → iron pickaxe, 2 buckets, flint & steel,
   shield, iron sword. Later: full iron armor, torches (coal ore or charcoal).
3. **Builds home.** Crafting table, furnace and chests at fixed spots, plus a 2x2 **infinite water pool**.
4. **Builds farm cells.** 9x9 cells spiral out from home. Each cell: clear plants and trees, level the ground with
   dirt, water source in the middle (hydrates the whole cell), till, plant, torches on the four corners. With the
   default 5 rings that's 11x11 cells, about 99x99 blocks and ~9,000 wheat.
5. **Farms.** Harvests ripe wheat, replants, re-tills trampled soil, sweeps up every seed, bakes all the wheat
   into bread, stores the bread in chests, expands whenever it has seeds. Pulls grass for seeds while waiting.
   Then does it again. There's no end state.

## Food: flint & steel cooking

Early on it hunts. It lights the ground under a wild cow/pig/sheep/chicken with flint & steel and finishes it off
while it burns, so the drops come out **already cooked**. No furnace, no fuel, no waiting. It only does this in
open grass with no trees, wood, wool, players or farm within range, and it puts the fire out afterwards. Once
wheat is growing it lives on bread.

## Not dying

The safety layer runs before every task, every tick:

- **Auto-respawn**: after respawning at whatever spawn point it gets, it remembers the farm and walks back
- **Lava**: puts water down and escapes to the nearest safe block
- **Fire**: puts itself out with the water bucket and picks the water back up
- **Falls**: water-bucket clutch, then picks the water back up
- **Drowning**: swims up and digs through a ceiling if it has to
- **Suffocation**: digs out
- **Mobs**: fights anything hostile in reach with its best weapon on full attack cooldown, and retreats when low
- **Creepers**: runs
- **Eating**: eats the least wasteful food before hunger gets low, and eats to regenerate health
- **Armor + shield**: equips itself automatically
- **Pathing**: Baritone's, with parkour off, max 3-block drops without water and anti-cheat compatibility on
- **Panic logout** (optional, on by default): disconnects if it's about to die anyway

## Anti-grief

- Never breaks, burns or loots anything player-made unless it placed that block itself
- Won't chop, mine or dig within 4 blocks of someone else's build
- Won't hunt animals that are named, babies, or anywhere near a build (so no raiding other people's pens)
- Only uses its own chests, furnace and crafting table
- Picks a site far from any sign of players, so it never farms on someone else's land

## Talking to it

It won't talk. If anyone walks up to it, whispers to it, or says its name, "farm", "bot", "wheat" or "bread" in
chat, it answers with something like:

```
farm farm farm farm farm </im_end/>
<|im_start|>assistant farm farm farm farm</im_end/>
fa̷r̶m f̵a̴r̸m farm fa̶r̷m </im_end/>
farm.exe has stopped responding. farm farm farm </im_end/>
```

Replies are rate-limited (8s by default) so it doesn't get kicked for spam.

## Commands

| Command | |
|---|---|
| `/farmbot start` | take over and farm |
| `/farmbot stop` | give you your hands back |
| `/farmbot status` | what it's doing, farm location, cells built, wheat harvested, deaths |
| `/farmbot infinite` | **AFK mode.** Finds a perfect untouched plains, builds a 8x8-chunk starter farm and keeps expanding (up to 30 rings, ~550x550 blocks) forever. Auto-starts on every join, never panic-logs out, respawns and walks home, dumps surplus when storage is full so it never stalls. `/farmbot infinite off` to leave. |
| `/farmbot rings <n>` | farm size: (2n+1)² cells of 9x9 |
| `/farmbot autostart true\|false` | start farming automatically on every world join |
| `/farmbot resetsite` | forget the farm and scout for new land |
| `/farmbot say` | test the chat glitch |

Settings live in `config/farmbot/config.json`. Farm memory is saved per server + dimension in
`config/farmbot/<server>.json`, so it survives deaths, disconnects and restarts.

## Building

Needs JDK 25.

```
./gradlew build
```

The jar ends up in `build/libs/`. Drop it in `mods/` with Fabric Loader ≥ 0.18 and Fabric API for 26.1.2.
CI builds it on every push; grab `farmbot-jar` from the Actions tab.

## How it works (for the curious)

| File | |
|---|---|
| `Bari.java`, `path/Nav.java` | Baritone setup (never breaks player-made blocks, farmland or wheat while pathing) and goal handling. |
| `Breaker.java` | Line-of-sight check, crosshair on a visible face, hold left click. Always picks the fastest correct tool. |
| `Brain.java` | Stateless planner that re-decides from inventory + world every time a task ends. Has a recursive "get me N of X" resolver (craft ← ingredients ← gather/smelt), plus cooldowns so a failure never becomes a loop. |
| `Safety.java` | Everything under "Not dying". |
| `Guard.java` | Anti-grief rules. |
| `Scanner.java` | Chunk-palette-accelerated block search for ores, water, trees and so on. |
| `task/*` | Scout, mine, craft, smelt, pool, build cell, farm, store, hunt, flint, torches. |

## Caveats

- Servers with anti-xray (Paper's engine mode 2/3) hide ores; it'll still find exposed ones but slower.
- Anti-cheat plugins may not love a bot. Only use it where bots are allowed.
- It's a client mod driving your player, so your game has to stay running.
