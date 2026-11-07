# JEI Tag Fix (unofficial)

**English** | [简体中文](README.zh-CN.md)

Stops **JEI**'s client-side recipe fallback from wiping the tags your server sent you.

If a server does not sync recipes to JEI, JEI rebuilds its recipe list locally — and in doing
so it rebinds **every tag in every registry** to "vanilla only". Anything your server provided
through a datapack silently disappears from the client:

- the pause-menu entry a server declares through `minecraft:pause_screen_additions` vanishes
  (`pause_screen_additions` is one of the casualties; a single dialog in that tag becomes one
  pause-menu button, several become the `minecraft:custom_options` list)
- custom item / block / any-other tags stop working
- nothing is logged at ERROR level, which is why this is so hard to track down

This mod makes JEI's fallback **non-destructive**: JEI still gets its recipes, your tags stay.

**Download:** [jeitagfix-1.0.0.jar](https://github.com/GardeniaBlow/jeitagfix/releases/latest/download/jeitagfix-1.0.0.jar) — drop it in `mods/`. Client-side only.

---

## Why I made this

This started as a player problem, not a modding one.

I play on a server that ships a datapack which adds a dialog and registers it in
`minecraft:pause_screen_additions`, so that an extra button shows up in the pause menu and the
same dialog is reachable through a hotkey as well (`minecraft:quick_actions`). The label on that
button is the dialog's own `external_title`, written by the server — on that server it reads
「服务器快捷指令」. That is the server's wording, not a vanilla feature name. On a vanilla client
it worked exactly as the server intended.

Then I switched to a modpack and the button was simply gone — same server, same version, same
account. The server was clearly still sending everything (other players saw the button, and the
same dialog would still pop up from an in-game item), so it had to be the client. I spent a long
time disabling UI mods, resource packs, renderers — none of it mattered, and nothing was ever
logged as an error.

It only came out once I stopped guessing and measured: a small probe mod dumped the dialog
registry and the tag state right after joining. The dialog **was** there; the tag that pointed at
it was empty. A vanilla item tag count of 238 on a client without JEI versus 236 with it made the
shape of the bug obvious, and bisecting the mod list pinned it on JEI in a few rounds.

So the goal of this mod is narrow and concrete: **let JEI do its job without it silently deleting
what the server sent.** If you found this page because you were staring at a missing button that
only exists when a certain mod is installed, you are in exactly the situation I was in.

---

## Symptoms

You are on a server that does not sync recipes to JEI. In `logs/latest.log`:

```
[Render thread/INFO]: Loaded 2042 vanilla recipes from the client recipe registry.
[Render thread/INFO]: [CHAT] This Leaf server does not provide recipes to JEI.
                              JEI is showing default recipes from your client, ...
```

and then some feature that depends on a server datapack tag is missing on the client, while
other players (or the same client without JEI) see it fine.

The pause-menu case, which is what led to this mod: a server datapack adds a
`minecraft:dialog` and references it from `minecraft:pause_screen_additions`. Vanilla shows a
button for it in the pause menu. With JEI installed, the button is gone.

![Same server, same account, same Minecraft 26.3 — left: modpack with JEI, the server's pause-menu button is absent; right: vanilla client, the button the server declared in `minecraft:pause_screen_additions` is there (its label 「服务器快捷指令」 is the dialog's own title)](docs/pause-menu-comparison.png)

Left is the modpack, right is the same player on the same server with a vanilla client. The
button the server asked for is in the red box on the right, and simply is not there on the left.
No error, no log line, nothing to grep for — which is exactly why this is worth a screenshot.

## Why it happens

`mezz.jei.common.recipes.VanillaClientRecipeLoader#loadVanillaRecipeRegistry` builds a
throwaway, **vanilla-only** resource manager and recomputes tags from it:

```java
MultiPackResourceManager rm = new MultiPackResourceManager(
        PackType.SERVER_DATA,
        List.of(ServerPacksSource.createVanillaPackSource().fullResources()));
...
List<Registry.PendingTags<?>> basePendingTags  = TagLoader.loadTagsForExistingRegistries(rm, frozen);
...
List<Registry.PendingTags<?>> worldPendingTags = TagLoader.loadTagsForExistingRegistries(rm, worldFrozen);
...
basePendingTags.forEach(Registry.PendingTags::apply);   // <-- wipes server tags
worldPendingTags.forEach(Registry.PendingTags::apply);  // <-- wipes server tags
```

`MappedRegistry$3#apply` does two things: it binds each pending `HolderSet.Named`, and then it
**replaces `MappedRegistry.allTags` wholesale** with the set it was given. Since that set was
computed from the vanilla pack alone, every server tag goes with it.

This only happens on the **fallback** branch. If the server syncs recipes, `loadVanillaRecipeRegistry`
is never entered and nothing is rebound — see *When this triggers* below.

Measured before/after on a test server (item registry tag count):

| | tags in `minecraft:item` | `minecraft:pause_screen_additions` |
|---|---|---|
| without JEI | 238 | 1 entry |
| with JEI | 236 | 0 entries |

The two missing ones were exactly the two provided by the server datapack.

## Minecraft 26.2 is not affected — this is a 26.3-only regression

Same server, same JEI fallback log line, 26.2 client on the left, 26.3 client on the right.
On 26.2 the pause-menu entry and the `quick_actions` hotkey both still work.

```
# 26.2 client, logs/latest.log
[11:02:27] Connecting to mckkk.com, 25565
[11:02:33] Loaded 1585 vanilla recipes from client resources.
[11:02:34] [CHAT] This Leaf server does not provide recipes to JEI. ...

# 26.3 client, logs/latest.log
[01:31:12] Loaded 2042 vanilla recipes from the client recipe registry.
[01:31:12] [CHAT] This Leaf server does not provide recipes to JEI. ...
```

Both fallbacks ran. Only one of them destroyed tags. The two JEI branches contain **completely
different implementations** of the same class:

| | 26.2 branch (`30.x`) | 26.3 branch (`31.x`) |
|---|---|---|
| method | `getVanillaRecipes` only | `getVanillaRecipes` + `loadVanillaRecipeRegistry` |
| how recipes are read | `SimpleJsonResourceReloadListener.scanDirectory(rm, RECIPE_LISTER, ops, Recipe.CODEC, map)` | `RegistryDataLoader.load(...)` re-reads the registries |
| `PendingTags::apply` | **none** | two calls |
| effect on the live registry | none — pending tags only feed a temporary `RegistryOps` | `allTags` replaced wholesale |
| log line | `Loaded {} vanilla recipes from client resources.` | `... from the client recipe registry.` |

The 26.2 implementation is exactly mezz's original fallback from
[`274fd56a`](https://github.com/mezz/JustEnoughItems/commit/274fd56a841d3d4d3fea703eb4b93e4eb4554437)
(July 2026), and it is still there in the newest 26.2 build (`30.48.0.244`, checked October 2026):
`javap -c` finds no `apply` call at all.

What happened on 26.3: the port to the new datapack-registry loading path
([`077f5c55`](https://github.com/mezz/JustEnoughItems/commit/077f5c55b9b377ae24f6fe5645967fc19a2ae26c))
had to rebuild recipes from `RegistryDataLoader` instead of scanning JSON, and the two `apply()`
calls were added so that the rebuilt data would take effect — but they were applied to the
**live** registries, not to a throwaway copy.

So "it works on 26.2, breaks on 26.3" is expected, not a modpack problem.

## Why "just skip the `apply()` calls" does not work

That was the first thing tried, and it makes things worse:

```
java.lang.IllegalStateException: Trying to access unbound tag
    'TagKey[minecraft:item / minecraft:clonable_maps]'
    at net.minecraft.core.HolderSet$Named.contents(HolderSet.java:161)
    ...
[Render thread/WARN]: Client disconnected with reason: Network Protocol Error
```

`prepareTagReload` also creates `HolderSet.Named` objects for tags that exist in the vanilla
pack but are not yet in the live registry — and **only `apply()` binds them**. Skip `apply()`
and those sets stay unbound forever; the next thing that walks a recipe touching such a tag
explodes and the client is kicked.

## What this mod does

`@Inject` at the head and tail of `loadVanillaRecipeRegistry`:

1. **HEAD** — snapshot the live tag state (`tag -> List<Holder>`) for every registry, copying
   holders out of each *bound* `HolderSet.Named`.
2. **RETURN** — read back whatever JEI installed, overlay the snapshot on top (server state
   wins), and push the merged result through the very same public API:
   `registry.prepareTagReload(new TagLoader.LoadResult<>(key, merged)).apply()`.

Every resulting `HolderSet.Named` is bound, so nothing is left dangling. JEI's `RecipeMap` is
built from a **separate** frozen `RegistryAccess` and is not touched — recipe search works
exactly as before.

Success looks like this in `logs/latest.log`:

```
[Render thread/INFO]: [jeitagfix] restored tags for 127 registries (2 server tag(s) re-added), 0 failed
```

## Compatibility

- Minecraft **26.3**, Fabric Loader **0.19.5+**. The mod declares `minecraft: ~26.3` and will
  not load on 26.2 — **26.2 does not need it** (see the branch comparison above).
- **Every 26.3 build of JEI is affected** — the destructive code shipped with the very first
  26.3 build and is still there in the newest one, so **upgrading JEI will not fix this**.
  Bytecode-verified on 31.0.0.3, 31.0.0.5, 31.0.0.7, 31.1.0.12, 31.3.0.15, 31.3.0.17,
  31.4.0.19, 31.7.0.31, 31.7.0.38, 31.14.0.72, 31.16.0.83 and 31.17.0.109; 31.0.0.3 is the
  oldest 26.3 build published, so there is no older 26.3 build to fall back to.
- Client-side only. Safe to install on a client joining any server; on servers that *do* sync
  recipes the fallback never runs and this mod does nothing at all.

## When this triggers (and when it does not)

All three must hold:

1. **26.3** with any JEI build from the `31.x` branch (31.0.0.3 through 31.17.0.109 measured —
   i.e. all of them).
2. JEI starts **without** a non-empty recipe set — i.e. the fallback branch in
   `JeiStarter#start` is taken. Vanilla's own `ClientboundUpdateRecipesPacket` carries only
   `itemSets` and `stonecutterRecipes`, never the recipe table itself, so a plain
   Paper/Leaf/vanilla server always lands here. (Fabric servers can push real recipes through
   `fabric-recipe-api-v1` — JEI registers `jei:jei_shaped` / `jei:jei_smelting` as synchronised
   serializers, but only when JEI is installed **on the server** too.)
3. The server does send tags from a datapack (for example a `minecraft:dialog` referenced from
   `minecraft:pause_screen_additions`).

If the server does sync recipes, `loadVanillaRecipeRegistry` is never called and no tags are
touched — verified by A/B against one server, same client, only the server-side JEI differing:

| | server has JEI (sync active) | server has no JEI (fallback) |
|---|---|---|
| `Internal.hasClientSyncedRecipes` | **true** | false |
| `Internal.hasClientFallbackRecipes` | false | **true** |
| `minecraft:pause_screen_additions` | **1 entry** | 0 entries |
| tags in `minecraft:item` | **508** | 236 |
| pause-menu button | **present** | absent |

The 508 → 236 drop is the vanilla-only recomputation, which is what makes this visible at all.

## Can the server fix it?

Partly — but there is no setting, command, or config option anywhere that switches this on.

**No config exists.** Minecraft's own protocol never sends the recipe table:
`ClientboundUpdateRecipesPacket` carries only `itemSets` and `stonecutterRecipes` (true on both
26.2 and 26.3). Nothing in `server.properties`, `spigot.yml`, `paper-global.yml` or
`purpur.yml` relates to recipe sync or tag sync. So "just turn on recipe sync server-side" is
not a thing on vanilla, Paper, Leaf or Purpur.

**But a server can fix it by re-sending the tag packet.** What JEI damages is the live
`Registry`'s tag set; one more `ClientboundUpdateTagsPacket` rebuilds it from the server's real
data. Measured on a Fabric 26.3 test server against a clean client (Fabric API + JEI 31.16.0.83,
**no jeitagfix**), datapack providing `pause_screen_additions`:

| moment | `pause_screen_additions` | tags in `minecraft:item` | button |
|---|---|---|---|
| tick 80, just joined | 0 | 236 | absent |
| tick 800, 40 s later | 0 | 236 | absent |
| after `/reload` | 0 | 236 | absent |
| after a bare tag re-broadcast | **1** | **507** | **present** |
| 60 s after that | 1 | 507 | still present |

`/reload` *does* re-broadcast the tags — `PlayerList#reloadResources` sends
`ClientboundUpdateTagsPacket` unconditionally — but the same reload also makes the client reload
its resource packs, which restarts JEI, which runs the fallback again and wipes the tags a second
time. The two effects cancel out. A bare tag packet triggers no resource reload, so what it
restores stays restored.

In practice:

| server type | can fix it? | how |
|---|---|---|
| Fabric, JEI installed server-side | yes, cleanest | the client gets real recipes, so the fallback never runs |
| Fabric, no JEI server-side | yes | a tiny mod or command that re-broadcasts `ClientboundUpdateTagsPacket` |
| Paper / Leaf / Purpur | only with a plugin | needs ProtocolLib or PacketEvents to send the packet by hand |
| Vanilla | no | no plugin API, no way to send it |

For a player on a server they do not control — the usual case — none of this is actionable, and
the client-side fix is the only option.

## Known limitations

- The mixin targets `mezz.jei.common.recipes.VanillaClientRecipeLoader#loadVanillaRecipeRegistry`
  by name. If JEI renames or restructures that method, this mod fails loudly (both injections
  are `require = 1`, and `defaultRequire` is 1) rather than silently doing nothing — you will
  see a mixin apply error, not a mystery.
- Tags merged back are the ones present at `HEAD`. Tags that a datapack adds *after* that
  moment (i.e. mid-session) are not covered.
- If the snapshot step itself throws, the mod logs a warning and steps aside, leaving stock
  JEI behaviour.

## Installation

Drop `jeitagfix-<version>.jar` into `.minecraft/mods/`. That's it — no config, no dependencies
beyond JEI itself.

## Building

Standard Fabric Loom setup. The Gradle wrapper is not committed, so generate it once (`gradle wrapper`, or let your IDE do it) before:

```bash
./gradlew build        # jar lands in build/libs/
```

Without network access, `build-offline.ps1` reproduces the same jar with nothing but a JDK 25
and the jars already in your launcher's `libraries/` directory (this is how the released
artifact — 6738 bytes, built with `--release 21` — was produced).

## This is not JEI-specific

The same class of breakage shows up with other recipe viewers. The clearest case is
[shedaniel/RoughlyEnoughItems#2059](https://github.com/shedaniel/RoughlyEnoughItems/issues/2059)
(`[Bug] Disables functionality of vanilla "pause_screen_additions"`, opened 2026-06-27, still
open): REI, as the only feature mod next to Fabric API / Architectury / Cloth Config, stops the
server's `pause_screen_additions` data from applying — it only shows up after `/reload`.

Worth knowing if you are diagnosing this yourself:

- Roughly **no one has reported this against JEI** — `pause_screen_additions` had zero hits in
  the JEI issue tracker when this mod was written.
- A GitHub-wide search for `pause_screen_additions` turns up only protocol implementations and
  server-plugin docs, never a player report. The symptom is easy to misattribute to the server,
  the modpack, or a resource pack.

## Reporting

If you can reproduce the underlying JEI behaviour independently of this mod, please also report
it upstream — a genuine fix there is better than a client-side workaround. See
[Issues](../../issues) for what to include (log excerpt, the tag that went missing, whether the
`does not provide recipes` line appears).

A useful detail when reporting to JEI: the two `PendingTags::apply` calls were **not** part of
the original fallback feature
([`274fd56a`](https://github.com/mezz/JustEnoughItems/commit/274fd56a841d3d4d3fea703eb4b93e4eb4554437),
issue [#4349](https://github.com/mezz/JustEnoughItems/issues/4349), which only built a temporary
serialization context and left the live registry alone). They were introduced later, by the 26.3
port ([`077f5c55`](https://github.com/mezz/JustEnoughItems/commit/077f5c55b9b377ae24f6fe5645967fc19a2ae26c),
*Port Fabric build to 26.3 snapshot 7*).

## Development note

The code in this repository was written with the help of an AI coding assistant working under
human direction. The problem was localised experimentally — a runtime probe mod dumped registry
and tag state, then the offending mod was narrowed down by bisecting the mod list against a
controlled test server. The final design came after an earlier, simpler approach failed in
testing (see *Why "just skip the `apply()` calls" does not work* above). All claims in this
README were verified on a real client/server pair; maintained and tested by the repository
owner.

## License

[MIT](LICENSE).
