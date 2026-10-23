# TridentPlus

A complete Paper 26.2 plugin that makes the trident viable in PvP without turning it
into a better sword. It adds the vanilla **Sharpness** and **Looting** enchantments to
tridents, adds a new trident-only enchantment **Fork / Нанизывание**, localizes that
name for `en_us`, `ru_ru` and the pre-reform Russian `rpr` locale, and injects Fork
books into the three ocean loot tables that cannot roll it in vanilla.

- Paper only. No Spigot, no Folia, no NMS, no reflection.
- Paper plugin (`paper-plugin.yml` + bootstrapper) because the item/enchantment
  registries are changed during bootstrap.
- Java 25, Gradle 9.8.2, Paper API `26.2.build.134-stable` (the newest 26.2 build at
  the time of writing).
- Pure `DamageMath` is unit tested; the full acceptance matrix is represented in
  `DamageMathTest`.

---

## Platform verification

| Item | Verified value |
| --- | --- |
| Paper API | `io.papermc.paper:paper-api:26.2.build.134-stable` from `https://repo.papermc.io/repository/maven-public/` |
| Paper server used for integration testing | `paper-26.2-134.jar`, Minecraft 26.2, Mojang-mapped |
| Java | 25 (`openjdk 25.0.5`), toolchain `JavaLanguageVersion.of(25)` |
| Gradle | 9.8.2 (Kotlin DSL), wrapper committed |
| run-paper | `xyz.jpenilla.run-paper` 3.1.0 |
| Tests | JUnit Jupiter 5.13.4 |

The registry API used here is marked `@ApiStatus.Experimental` in 26.2. The exact
signatures were read from the `26.2.build.134-stable` sources jar before coding
(`RegistryEvents`, `EnchantmentRegistryEntry.Builder`, `RegistryKeySet`,
`LifecycleEvents.TAGS`, `PostFlattenTagRegistrar`). The only deprecated calls left
in the deliverable are avoided: `RegistryAccess` is used instead of
`Registry.ENCHANTMENT`, and `GameRules.PVP` instead of `World#getPVP`.

`./gradlew runServer` was executed successfully against Paper 26.2 build 134 and the
startup selfcheck reported all PASS.

---

## Phase 0: vanilla behaviour in 26.2

The build environment for this task has no Minecraft client, so Phase 0 was performed
against the shipped 26.2 server bytecode (`javap` on `paper-26.2.jar`, which contains
the Mojang-mapped server classes and vanilla data). Every fact below is therefore
measured from 26.2 itself rather than assumed. The debug reporter is included so the
developer can confirm the same facts with a real client and paste the lines back.

| Question | 26.2 answer | Evidence |
| --- | --- | --- |
| (a) Is Sharpness applied to melee tridents? | **Yes**, once Sharpness is allowed on the item. | `Player.attack` calls `getEnchantedDamage(target, base, source)` with the mainhand trident; the `minecraft:damage` effect of Sharpness has no item-type requirement. |
| (b) Is it applied to thrown tridents? | **Yes**, unscaled. | `ThrownTrident.onHitEntity` calls `EnchantmentHelper.modifyDamage(level, getWeaponItem(), target, source, baseDamage)`, and `ThrownTrident.getWeaponItem()` returns its pickup item stack (the trident). |
| (c) Is the bonus scaled by attack cooldown? | **Yes** for melee. | `bonus = h * (getEnchantedDamage(...) - base)` where `h = getAttackStrengthScale(0.5f)` / `Player#getAttackCooldown()`. Thrown damage is not scaled. |
| (d) Does a crit multiply only the base part? | **Yes.** | `base *= 1.5f` on a crit, then `total = base + bonus`. The enchantment bonus is **not** multiplied. |
| (e) How does a Riptide spin hit present? | Same `Player.attack` path, but the base is `autoSpinAttackDmg`; the damage source is `player_attack`, the damager is the player. | `Player.attack` selects `isAutoSpinAttack() ? autoSpinAttackDmg : ATTACK_DAMAGE`, then uses `createAttackSource(weapon)`. Classified as `RIPTIDE` in the debug output. |

Additional vanilla data read from the 26.2 jar:

- Sharpness value: **1.0 base + 0.5 per level above I** (`0.5*L + 0.5`).
- Impaling value: **2.5 base + 2.5 per level above I** (12.5 at V), only against
  `#minecraft:sensitive_to_impaling` (drowned, guardians, etc.).
- Sweeping Edge registry values: weight **2**, anvil cost **4**, min cost
  **5 + 9*(L-1)**, max cost **min + 15**, slot **mainhand**.
- Looting is implemented by loot functions such as `minecraft:enchanted_count_increase`
  which call `EnchantmentHelper.getEnchantmentLevel(LOOTING, attacker)`. That reads
  the attacker's **equipped** item. A trident in flight is not equipped, so thrown
  kills do **not** get Looting; melee kills with a trident in the main hand do.
- `#minecraft:exclusive_set/damage` contains Sharpness, Smite, Bane of Arthropods,
  Impaling, Density and Breach. We remove Impaling from Sharpness and Sharpness from
  Impaling, and keep the rest.
- `chests/shipwreck_treasure` and `chests/buried_treasure` have **no** enchantment
  rolls in 26.2. `chests/shipwreck_supply` only rolls `enchant_randomly` with
  `#on_random_loot` on leather armour, which can never select a trident-only
  enchantment. The explicit book injection is therefore required.
- Item tags are **not bound during `entryAdd`** in 26.2: reading
  `builder.supportedItems().values()` throws
  `Trying to access unbound tag ...`. The bootstrapper detects this, rebuilds the
  sets from the vanilla 26.2 item list, adds the trident, and logs which path it
  took. The startup selfcheck proves the result.

### Phase 0 log format

With debug enabled, each trident damage event prints one line:

```
MELEE sharp=5 fork=3 cd=1.00 raw=12.000->13.000 final=13.000 target=HUSK
THROWN sharp=5 fork=0 cd=1.00 raw=11.000->3.000 final=3.000 target=HUSK
RIPTIDE sharp=0 fork=0 cd=1.00 raw=4.000->4.000 final=4.000 target=HUSK
FORK_SECONDARY sharp=5 fork=3 cd=1.00 raw=9.750->9.750 final=9.750 target=HUSK
```

---

## How it works

### Sharpness on tridents (1.1)

The bootstrapper registers an `entryAdd` handler for the enchantment registry and
patches three entries:

- **Sharpness**: add `minecraft:trident` to supported and primary items; rebuild the
  exclusive set without Impaling.
- **Looting**: add the trident to supported and primary items.
- **Impaling**: rebuild the exclusive set without Sharpness.

The trident's supported/primary sets are rebuilt from the vanilla 26.2 lists because
the tags are unbound during `entryAdd` (see above). The list contains every 26.2
sword, axe and spear (including copper), plus the trident. Looting's primary set is
explicitly the same as its supported set (vanilla leaves it `null`).

Damage numbers:

- `reference-max-damage = 11.0`, `cap-offset = 2.0` → trident cap **13.0**.
- `melee-base = 9.0` → headroom **4.0** (computed from config).
- `k = headroom / vanilla Sharpness V bonus = 4.0 / 3.0`.
- Desired bonus(L) = `k * (0.5L + 0.5)`.

| Sharpness | Vanilla bonus | Trident bonus | Melee damage |
| --- | ---: | ---: | ---: |
| none | 0.0 | 0.0 | **9.000** |
| I | 1.0 | 1.333 | **10.333** |
| II | 1.5 | 2.000 | **11.000** |
| III | 2.0 | 2.667 | **11.667** |
| IV | 2.5 | 3.333 | **12.333** |
| V | 3.0 | 4.000 | **13.000** |

Vanilla already applies its Sharpness bonus, so the listener adds
`(desired - vanilla) * cooldown` to the event's **BASE** damage at `HIGH` priority.
For the default config the delta is positive (k > 1). The code handles negative
deltas too, which is exercised by the `thrown-penalty-per-level` path and by a
zero-headroom config.

**Thrown tridents**: each Sharpness level subtracts `thrown-penalty-per-level`
(default 1.0), clamped to `thrown-min-damage` (default 1.0):

| Sharpness | Thrown damage (penalty 1.0) | Thrown damage (penalty 0) |
| --- | ---: | ---: |
| none | **8.0** | **8.0** |
| I | **7.0** | **8.0** |
| II | **6.0** | **8.0** |
| III | **5.0** | **8.0** |
| IV | **4.0** | **8.0** |
| V | **3.0** | **8.0** |

Impaling is never touched: a thrown trident against a drowned at Impaling V gets
+12.5 on top of the Sharpness-adjusted damage.

The attack cooldown is captured in `PrePlayerAttackEntityEvent` and reused for the
damage event of the same tick (`Bukkit.getCurrentTick()`), as specified.

**Riptide decision**: Riptide spin attacks are logged as `RIPTIDE` and keep **vanilla**
Sharpness damage; they do not use the 9.0 melee table and never trigger Fork. A
mobility tool with a different base damage should not inherit the duelling damage
curve, and the acceptance matrix does not cover it. This is a deliberate balance
decision, not a limitation of the implementation.

### Fork / Нанизывание (1.2)

`tridentplus:fork` is registered in `RegistryEvents.ENCHANTMENT.compose()`:

| Property | Value |
| --- | --- |
| Display name | translatable key `enchantment.tridentplus.fork`; `en_us` = Fork, `ru_ru` = Нанизывание, `rpr` = Многонасажденіе. The configured `fork.display-name` is the fallback when the pack is not loaded. |
| Max level | 3 |
| Weight | 2 |
| Anvil cost | 4 |
| Min cost | 5 + 9 per level above I |
| Max cost | 20 + 9 per level above I (min + 15) |
| Active slot | `EquipmentSlotGroup.MAINHAND` |
| Supported / primary | `#minecraft:enchantable/trident` (trident only) |
| Exclusive with | nothing (compatible with every trident enchantment) |

Behaviour is melee-only in v1:

1. The damage must be a real `ENTITY_ATTACK` by the player (not thrown, not our own
   secondary hit), with a trident in the main hand carrying Fork.
2. Configurable sweep conditions: cooldown ≥ 0.9 (`require-full-cooldown`), not a
   crit (`allow-on-crit`), not a sprint-knockback hit (`allow-sprint-knockback`).
3. **N = L + 2** entities are hit in total, including the primary target. The other
   `N-1` are selected from living entities within 1.5 horizontal / 0.5 vertical of
   the primary and 4.5 of the attacker, sorted by distance to the primary.
4. Excluded: attacker, primary, dead/invulnerable/spectator, non-living,
   ArmorStand/Marker/Interaction/Display/ItemFrame, the attacker's tamed animals,
   the attacker's vehicle, same-team players when friendly fire is off, and players
   when the world PVP game rule is false.
5. Secondary damage = `ratio(L) * primaryNonCritRawDamage + secondary-flat-bonus`
   (default 0); default `L/(L+1)` = 50 / 66.7 / 75%, configurable per level. No crit
   is applied. The primary raw
   value is taken after the Sharpness compensation, with the crit multiplier removed
   from the base only (matching 26.2).
6. The hit uses `LivingEntity#damage(double, DamageSource)` with a
   `PLAYER_ATTACK` source whose causing and direct entity are the attacker, so
   protection plugins see a normal attack and can cancel it. `Player#attack` is
   never called.
7. A `ThreadLocal` guard prevents Fork and Sharpness compensation from re-entering
   on secondary hits. Knockback is scaled through `EntityKnockbackEvent`.
   Particles/sound are toggleable. Durability is only paid by the primary swing.

Thrown Fork is not implemented. `ForkService` keeps the candidate filter and the
secondary damage calculation separate so a future thrown implementation can reuse
them (the intended design is a thrown trident passing through N-1 extra targets).

### Localization of the Fork name

The registry description is `Component.translatable("enchantment.tridentplus.fork")`
with the configured name as a fallback. The plugin builds a tiny resource pack from
resources inside its own jar:

```
pack.mcmeta                          pack_format 88 (26.2), supported_formats 1..999
assets/tridentplus/lang/en_us.json   Fork
assets/tridentplus/lang/ru_ru.json   Нанизывание
assets/tridentplus/lang/rpr.json     Многонасажденіе
```

`rpr` is the real 26.2 locale **«Русскій дореформенный»** (`language.code = qpr`).
The pre-reform `і`, `ѣ` and `ъ` glyphs are present in the client's unifont.

The pack is written to `plugins/TridentPlus/TridentPlus-resourcepack.zip` on every
start and served by the plugin on port **8123** at
`/tridentplus-pack.zip`. The URL host is taken from the hostname each player actually
used to connect (Paper's virtual host), with a LAN fallback. For hosted servers,
reverse proxies or blocked ports, upload the generated zip somewhere and set
`resource-pack.public-url`; the local HTTP server is not started in that mode. If the
client declines the pack, the enchantment falls back to `fork.display-name`.

Changing the three translations requires rebuilding the plugin jar; the
`resource-pack.enabled/port/host/public-url/prompt` values are hot-reloadable.

### Looting on tridents (1.3)

The bootstrapper adds the trident to Looting's supported/primary items, so melee
kills use the normal vanilla Looting loot functions.

Thrown Looting is **not** applied. 26.2 resolves Looting from the attacker's equipped
item, and a trident in flight is not equipped. Bukkit's
`LootContext#lootingModifier` is still present in the API but is ignored by the 26.2
loot implementation (`CraftLootTable#convertContext` never reads it), so there is no
clean API-only way to add the drops. The `looting.apply-to-thrown` option is kept
for forward compatibility; when it is on and a thrown Looting kill happens, the
plugin logs one warning and leaves vanilla drops unchanged. This is documented under
known limitations.

### Acquisition (1.4)

Fork is added to the enchantment tags via
`LifecycleEvents.TAGS.postFlatten(RegistryKey.ENCHANTMENT)`:

- `#minecraft:in_enchanting_table`
- `#minecraft:tradeable`
- `#minecraft:on_random_loot`

It is deliberately **not** in `#minecraft:treasure` and **not** in
`#minecraft:curse`. Tridents keep their low enchantability; table rolls are rare.

Chest injection is registered on `LootGenerateEvent`:

| Table | Chance | Level weights |
| --- | ---: | --- |
| `minecraft:chests/shipwreck_treasure` | 0.10 | 60 / 30 / 10 |
| `minecraft:chests/shipwreck_supply` | 0.05 | 70 / 25 / 5 |
| `minecraft:chests/buried_treasure` | 0.12 | 60 / 30 / 10 |

The injected item is an `ENCHANTED_BOOK` with the Fork stored enchantment, so it can
be used in an anvil. All values come from `config.yml`.

---

## Commands

Permission: `tridentplus.admin` (default op).

| Command | Description |
| --- | --- |
| `/tridentplus reload` | Reload `config.yml`; prints which values are live and which need a restart |
| `/tridentplus debug <on\|off>` | Per-player debug lines |
| `/tridentplus give` | Test trident (Sharp V, Fork III, Loot III, Impaling V, Loyalty III, Unbreaking III, Mending) + netherite sword (Sharp V) |
| `/tridentplus dummy [count] [armored]` | Spawn 1–100 no-AI, silent, persistent husks with 1024 HP, zero armor or full netherite; the name shows the last damage taken |
| `/tridentplus selfcheck` | PASS/FAIL registry checks; also runs automatically on startup |

---

## Build and run

Requirements: a JDK 25 (the Foojay resolver will download one if necessary) and
network access to Paper's Maven repository and the Paper download API.

```bash
cd TridentPlus
./gradlew build          # compiles + runs the JUnit suite + creates build/libs/TridentPlus-1.0.0.jar
./gradlew test           # only the unit tests
./gradlew runServer      # Paper 26.2 build 134, local dev server
```

`runServer` writes `run/eula.txt` (`eula=true`) and this `run/server.properties`
before starting:

```properties
online-mode=false
level-type=flat
gamemode=creative
difficulty=easy
spawn-monsters=false
spawn-protection=0
max-players=5
view-distance=6
```

A prebuilt jar is committed at `dist/TridentPlus-1.0.0.jar` (the matching
`dist/TridentPlus-1.0.0.jar.sha256` is next to it), or build it yourself with
`./gradlew build`. The exact pack generated at runtime is committed at
`dist/TridentPlus-resourcepack.zip` (SHA-1 `b61798854743e49df331b1fc7d70c57e0ebb899b`),
which is useful for `resource-pack.public-url` on hosts that block port 8123.
Rebuild that file whenever the three lang files change. To use the plugin on a real server, drop the jar into `plugins/`. The first start creates
`plugins/TridentPlus/config.yml` and
`plugins/TridentPlus/TridentPlus-resourcepack.zip`, and serves the pack on TCP
**8123**. Open that port in the firewall, or set `resource-pack.public-url` to an
external copy of the generated zip. `/reload` is not supported because registry
changes require a restart.

If the server is behind Velocity/BungeeCord, a NAT or a hosting panel that blocks
arbitrary ports, set `resource-pack.public-url`; the plugin then sends that URL and
does not start its own HTTP listener. Upload the exact generated zip so the SHA-1
matches.

---

## Manual acceptance checklist

Use a flat world, creative mode, no potion effects, standing on the ground. Spawn
fresh husks with `/tridentplus dummy ...` and read the custom name (last damage
taken). Kill leftovers between tests with `/kill @e[type=husk]`.

### 1. Startup / registry

- [ ] Server log contains `TridentPlus ... Startup selfcheck: PASS`.
- [ ] `/tridentplus selfcheck` prints all PASS lines.
- [ ] `/tridentplus give` gives the trident and the netherite sword.
- [ ] `/enchant` on a trident offers Sharpness, Looting and Fork; an anvil accepts a
      Sharpness/Looting/Fork book on a trident.
- [ ] The client receives a resource-pack prompt; accept it. In **English** the
      enchantment is `Fork`, in **Русский** it is `Нанизывание`, in
      **Русскій дореформенный** it is `Многонасажденіе`.
- [ ] `curl http://<server>:8123/tridentplus-pack.zip -o pack.zip` returns a valid
      zip (when `public-url` is not used).

### 2. Melee (zero-armor husk, full cooldown, no crit, no sprint)

- [ ] `/tridentplus dummy 1` and hit with an unenchanted trident → **9.000**.
- [ ] Repeat with Sharpness I / II / III / IV / V → **10.333 / 11.000 / 11.667 /
      12.333 / 13.000**.
- [ ] The numbers do not change when the husk is armored (the name shows final
      damage; the raw BASE is what the matrix specifies, so for the armored check
      compare the damage before armor in the debug line).

### 3. Thrown (zero-armor husk)

- [ ] Throw an unenchanted trident → **8.000**.
- [ ] Throw Sharpness I..V → **7 / 6 / 5 / 4 / 3**.
- [ ] Set `sharpness.thrown-penalty-per-level: 0.0`, `/tridentplus reload`, throw
      Sharpness V → **8.000**.

### 4. Fork (cluster of 10 husks, no Sharpness, full cooldown, no crit, no sprint)

- [ ] Fork I → exactly **3** husks damaged; primary **9.000**, secondaries **4.500**.
- [ ] Fork II → exactly **4**; primary **9.000**, secondaries **6.000** (config
      0.6667 gives 6.000 after 3-decimal display).
- [ ] Fork III → exactly **5**; primary **9.000**, secondaries **6.750**.
- [ ] Fork I/II/III + Sharpness V → primary **13.000**; secondaries **6.500 /
      8.667 / 9.750**.
- [ ] The Fork hit does not damage the attacker, the primary again, or any excluded
      entity type.
- [ ] A crit (jump + hit) does not trigger Fork with defaults; setting
      `allow-on-crit: true` triggers it and removes the crit from the secondary
      base.

### 5. Impaling

- [ ] `/summon drowned ~ ~ ~2 {NoAI:1b,Silent:1b,PersistenceRequired:1b}` (or a
      drowned from a bucket), hit with the Sharp V + Impaling V test trident →
      **25.500** (13.0 melee + 12.5 Impaling).
- [ ] The same trident without Impaling does **13.000**.

### 6. Looting

- [ ] Kill 50 normal husks with Looting III and count rotten flesh/iron drops.
- [ ] Kill 50 with no Looting and compare. Looting III should be statistically
      higher.
- [ ] Thrown Looting kills: the server logs the documented limitation once and
      leaves vanilla drops unchanged.

### 7. Loot injection

- [ ] Set `loot-injection.tables."minecraft:chests/shipwreck_treasure".chance: 1.0`,
      `/tridentplus reload`.
- [ ] `/setblock ~ ~ ~ minecraft:chest{LootTable:"minecraft:chests/shipwreck_treasure"}`
- [ ] `/setblock ~ ~-1 ~ minecraft:hopper`
- [ ] Wait one second, then `/data get block ~ ~-1 ~ Items`: an
      `minecraft:enchanted_book` with `tridentplus:fork` appears.
- [ ] Restore chance 0.10. (A naturally generated shipwreck chest also works.)

### 8. Unchanged vanilla

- [ ] A netherite sword still accepts Sharpness, and Sweeping Edge still works on
      swords (hit 3+ mobs and see sweep damage).
- [ ] Riptide still conflicts with Loyalty and Channeling in an anvil.
- [ ] Sharpness still conflicts with Smite / Bane of Arthropods.

### 9. Debug

- [ ] `/tridentplus debug on`, hit a husk, paste the `MELEE ...` line.
- [ ] Throw a trident and paste the `THROWN ...` line.
- [ ] Trigger a Fork and paste the `FORK_SECONDARY ...` lines.

---

## Tests

`./gradlew test` runs 22 JUnit 5 tests. `DamageMathTest` covers every number from
the acceptance matrix:

- vanilla Sharpness bonus, headroom, `k`, trident bonus and melee damage;
- positive and negative compensation deltas, cooldown scaling, zero-headroom;
- thrown damage/penalty/clamp, penalty 0, thrown delta;
- Fork ratio, target count, secondary damage with/without Sharpness V, flat bonus;
- crit removal (base only), cooldown clamp, Impaling sanity number.

`ResourcePackContentTest` builds the real zip in memory and verifies:
- the four expected entries exist;
- `pack.mcmeta` uses resource format 88 and accepts older formats;
- `en_us` = Fork, `ru_ru` = Нанизывание, `rpr` = Многонасажденіе, with the
  pre-reform `і` and no UTF-8 replacement characters.

---

## Project layout

```
TridentPlus/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/wrapper/gradle-wrapper.jar
├── gradle/wrapper/gradle-wrapper.properties
├── README.md
├── .gitignore
├── src/main/resources/
│   ├── paper-plugin.yml
│   ├── config.yml
│   └── resourcepack/
│       ├── pack.mcmeta
│       └── assets/tridentplus/lang/{en_us,ru_ru,rpr}.json
├── src/main/java/dev/tridentplus/
│   ├── TridentPlusBootstrap.java
│   ├── TridentPlusPlugin.java
│   ├── TridentPlusKeys.java
│   ├── BootstrapConfig.java
│   ├── balance/DamageMath.java
│   ├── config/Settings.java
│   ├── listener/DamageListener.java
│   ├── listener/ForkListener.java
│   ├── listener/LootListener.java
│   ├── fork/ForkService.java
│   ├── command/TridentPlusCommand.java
│   ├── resourcepack/ResourcePackBuilder.java
│   ├── resourcepack/ResourcePackService.java
│   ├── debug/DebugReporter.java
│   └── debug/SelfCheck.java
└── src/test/java/dev/tridentplus/
    ├── balance/DamageMathTest.java
    └── resourcepack/ResourcePackContentTest.java
```

`gradle.properties` and the wrapper files are generated by Gradle; the wrapper is
included so `./gradlew` works without a system Gradle.

---

## Known limitations and deviations

1. **Registry changes need a restart.** Paper disables `/reload` when a plugin uses
   lifecycle registry/tag handlers. `/tridentplus reload` only reloads balance,
   injection and the resource-pack host/port/prompt. The Fork translation key and its
   fallback name are baked into the registry and need a restart.
2. **Item sets are rebuilt explicitly.** In 26.2 the vanilla item tags are unbound
   during `entryAdd`, so the existing `RegistryKeySet` cannot be read. The
   bootstrapper rebuilds the Sharpness/Looting sets from the 26.2 vanilla item list
   (all swords, axes, spears + trident) and logs this. If a future Minecraft version
   adds a new sword/axe/spear to those tags, update the fallback lists. The selfcheck
   proves 26.2 sword/axe/spear coverage.
3. **Thrown Looting is not applied.** 26.2's loot functions read the attacker's
   equipped item; a trident in flight is not equipped. The Bukkit
   `lootingModifier` is ignored by the 26.2 implementation, so the clean API has no
   way to add the drops. The `looting.apply-to-thrown` flag is accepted and logs a
   one-time warning; melee Looting works normally.
4. **Riptide spin attacks do not use the trident melee table** and never trigger
   Fork. They are logged as `RIPTIDE` and keep vanilla Sharpness behaviour.
5. **Fork is melee-only.** Thrown Fork is deliberately not implemented in v1.
   `ForkService` keeps its candidate filter and damage calculation reusable.
6. **Fork ratio rounding.** The spec's sample config uses `0.6667`; the exact
   `L/(L+1)` ratio is `2/3`. With `0.6667`, secondary damage is 6.0003 / 8.6671,
   which displays as 6.000 / 8.667 at three decimals. The unit tests cover both the
   exact formula and the config value.
7. **Secondary hits are normal `LivingEntity#damage` calls.** They do not reset the
   attack cooldown, do not cost extra durability, and can be cancelled by protection
   plugins. Armor/resistance apply to them as usual (the acceptance matrix uses
   zero-armor husks). No crit or sweep is applied.
8. **Fork candidate distance uses entity locations, not hitbox intersections**, and
   the 1.5/0.5 radius is measured around the primary target's location. This is
   deterministic and matches the spec closely; extreme hitbox edge cases can differ
   by a few centimetres.
9. **`/tridentplus debug` is per player.** Console cannot toggle per-player debug;
   the default is `debug.default` in the config.
10. **Dummy tracking is in-memory.** The husks themselves persist, but their names
    stop updating after a server restart because the UUID set is rebuilt empty.
11. **Paper only.** The `ThreadLocal` re-entrancy guard and the `LivingEntity#damage`
    approach assume a single owning region thread; Folia is not supported.
12. **The resource pack can be declined or unreachable.** The Fork name then falls
    back to `fork.display-name` (default Нанизывание). The plugin logs the failure and
    the `PlayerResourcePackStatusEvent` status. On hosted servers, port 8123 is often
    firewalled; set `resource-pack.public-url` to an externally hosted copy of
    `plugins/TridentPlus/TridentPlus-resourcepack.zip` instead.
13. **Pack translations are baked into the jar.** The three lang files are resources;
    changing them requires `./gradlew build` and a plugin update. The pack host, port,
    public URL and prompt are config values and reload live.
14. **The pack URL uses each player's virtual host** (the hostname they typed). Behind
    a proxy this may be the proxy's backend address; use `public-url` there. IPv6
    literals are bracketed automatically. The pack is HTTP, so clients on restrictive
    networks may need to allow it.
15. **`rpr` is the client locale code, not `ru_old`.** 26.2 names the pre-reform
    Russian locale `Русскій дореформенный` with `language.code = qpr` and the file
    `rpr.json`; the pack uses exactly those names.
