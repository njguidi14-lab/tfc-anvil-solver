# Anvil Solver

A client-side [NeoForge](https://neoforged.net/) mod for [TerraFirmaCraft](https://modrinth.com/mod/terrafirmacraft) that solves the anvil forging minigame for you and shows the answer in the anvil screen.

It reads the anvil's live state — target, current work, the presses you've already made, and the recipe's rules — and computes the **fewest possible presses** to land exactly on target with every rule satisfied, which is what earns a *Perfectly Forged* result. The next press is highlighted directly on the anvil's own button, so you can just click what's lit.

Because it plans from your *current* state rather than from a fresh ingot, it keeps working mid-forge: press something wrong, and it re-plans from where you actually are.

![overlay](docs/screenshot.png)

<!-- TODO: add docs/screenshot.png -- an in-game shot of the overlay beside the anvil GUI -->

## Features

- **Fewest-press solve** for the current item, recomputed as you press
- **Next-press highlight** drawn on TFC's real anvil button
- **Step icons** — the overlay uses TFC's own button icons, not text names
- **Mid-forge aware** — resumes correctly from presses already made
- **Temperature aware** — shows the item's heat against its working temperature, warns when it's gone too cold to press at all, and estimates how long you have left
- **Toggle keybind** (default `H`, rebindable in Controls)
- **Configurable** position, gap, and how many upcoming presses to list
- **Client-side only** — never required on a server

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248+ |
| TerraFirmaCraft | 4.2.6 (`[4.2.6,5.0.0)`) |
| Java | 21 |

TFC itself requires [Patchouli](https://modrinth.com/mod/patchouli), which you'll already have if TFC is installed.

## Config

`config/anvilsolver-client.toml`, created on first run:

| Option | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Show the overlay at all |
| `highlightNextButton` | `true` | Ring the next button to press |
| `showTemperature` | `true` | Show the item's temperature, and warn when it's too cold to work |
| `overlayGap` | `4` | Pixel gap between the anvil GUI's edge and the overlay box |
| `overlayY` | `0` | Vertical offset from the GUI's top edge |
| `maxPresses` | `10` | Max upcoming presses listed (also auto-limited to what fits on screen) |

## Building

```bash
./gradlew build          # compile + run tests, jar lands in build/libs/
./gradlew runClient      # launch a dev client with TFC and Patchouli loaded
./gradlew test           # unit tests only
```

Requires a JDK 21. Everything else — NeoForge, Minecraft, TFC, Patchouli — is resolved by Gradle.

> **Note:** `runClient` generates a **fresh world with a random seed** every time you create one. Forge targets are derived from the world seed, so a target here will not match a target in your survival world. If you're cross-checking against another tool, run `/seed` first and use *that* seed.

## How it works

### The solver (`solver/`)

Pure Java, zero Minecraft dependencies, so it's unit-testable in isolation.

Each press changes a "work" value on a 0–150 bar:

| Press | Δ | Press | Δ |
|---|---|---|---|
| Punch | +2 | Light Hit | −3 |
| Bend | +7 | Medium Hit | −6 |
| Upset | +13 | Hard Hit | −9 |
| Shrink | +16 | Draw | −15 |

A recipe adds up to three rules constraining which press *family* must appear in which of the **last three** presses (`ANY`, `LAST`, `NOT_LAST`, `SECOND_LAST`, `THIRD_LAST`).

`ForgeSim.solve` runs a breadth-first search over states of `(work value, sliding window of the last three presses)`, seeded with the item's current state. Because every transition costs exactly one press, BFS's first-visit-wins property gives a genuinely shortest path. Seeding the window with real history is what makes mid-forge planning work: already-performed presses are fixed, and only the trailing three matter for rules.

### The client layer (`client/`)

`ClientEvents` subscribes to `ScreenEvent.Render.Post` and draws when the open screen is TFC's `AnvilScreen`. State is read the same way TFC reads it — `screen.getMenu().getBlockEntity().getMainInputForging()` — so it can't drift from the game's own source of truth. `TfcMapping` converts TFC's `ForgeStep`/`ForgeRule` enums to the solver's framework-independent types and back.

Solutions are memoized on `(target, work, history, rules)`; the BFS only re-runs when the anvil state actually changes, not every frame.

Temperature is read separately, fresh every frame, and is deliberately **not** part of that cache key — it changes several times a second, so keying on it would re-run the BFS on nearly every frame. `CoolingEstimator` keeps ~2 seconds of `(temperature, timestamp)` readings and fits a line through them to estimate when the item drops below its working temperature. It contains no model of TFC's cooling: if the readings aren't consistently falling, or the extrapolation lands somewhere implausible, it returns nothing and the overlay shows no estimate at all. Showing nothing beats showing a number the player would plan presses around.

## Notes for future maintainers

Things that cost real debugging time and are easy to get wrong again:

- **`AnvilScreen` does not declare `render()`.** It only declares `init()`, `renderBg()`, and `renderTooltip()` — `render` is inherited. Mixin only matches methods *declared* on the target class, so an `@Inject` into `render` fails with `InvalidInjectionException` and hard-crashes at startup. This mod deliberately uses `ScreenEvent.Render.Post` instead of a mixin; don't reintroduce one.
- **`ForgeRule` serialized names split on the FIRST underscore.** They're `<family>_<order>` where family is always one token, but orders like `second_last` contain underscores. Splitting on the last underscore breaks every positional rule.
- **`ForgeSteps.steps()` is oldest-first**, documented as `[thirdLast, secondLast, last]`, and never contains nulls (it's built with `List.of`). It never exceeds three entries.
- **Modrinth's maven does not resolve transitive mod dependencies.** TFC's own hard dependency on Patchouli must be declared explicitly here or the dev client dies at mod loading with TFC absent.
- **`KeyMapping.consumeClick()` never fires while a screen is open**, which is the only time this overlay exists. Key input is read from `ScreenEvent.KeyPressed.Post`. That event *does* fire for container screens on NeoForge — NeoForge patches `AbstractContainerScreen.keyPressed` to `return handled` rather than vanilla's unconditional `return true`.
- **`run/config/anvilsolver-client.toml` persists between dev runs.** Changing a config default won't appear to take effect until you delete it.
- **Sources are ASCII-only on purpose.** `build.gradle` sets no `compileJava.options.encoding`, so javac reads sources in the platform default charset — not UTF-8 on Windows. Non-ASCII characters that need to reach the screen (the `°` in the temperature line) are built from their code point in Java rather than typed into the file.
- **`HeatCapability.view(stack)` is `@Nullable`** and returns null for anything that can't hold heat, which the anvil can be holding. It's called from the render path, so an unguarded dereference throws out of `ScreenEvent.Render.Post` on every frame.

## Tests

```bash
./gradlew test
```

Covers bounds, rule satisfaction, mid-forge resumption, and a 300-iteration fuzz pass. Press-count **minimality** is checked against an independent oracle that uses a deliberately different algorithm — brute-forcing all 512 possible final-three sequences plus a plain work-only BFS for the prefix — so agreement between the two is a real cross-check rather than a tautology.

One honest limitation: the tests' rule-checking helper restates the solver's own rule logic and sits on both sides of every comparison, so they validate the *search strategy*, not the *rule semantics*. The semantics were verified separately by reading TFC's `ForgeRule.matches()` source directly.

## License

**LGPL-3.0-or-later** — see [LICENSE](LICENSE) (and [LICENSE.GPL-3.0](LICENSE.GPL-3.0), which the LGPL incorporates by reference). Copyright © njguidi14 — [github.com/njguidi14-lab](https://github.com/njguidi14-lab).

In plain terms: use it, bundle it in a modpack, and fork it freely. If you distribute a modified version, that version has to stay open under the same licence. That keeps closed-source reuploads off the table without getting in the way of anyone who just wants to use the mod.

LGPL-3.0 is also on the [EUPL-1.2 compatible-licence list](https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12), which matters here because TerraFirmaCraft — the mod this one builds against — is EUPL-1.2.

TerraFirmaCraft is by the TerraFirmaCraft team and is not affiliated with this mod.
