# JEI Tag Fix (unofficial)

Stops **JEI**'s client-side recipe fallback from wiping the tags your server sent you.

If a server does not sync recipes to JEI, JEI rebuilds its recipe list locally — and in doing
so it rebinds **every tag in every registry** to "vanilla only". Anything your server provided
through a datapack silently disappears from the client:

- server-provided dialog buttons vanish from the pause menu
  (`minecraft:pause_screen_additions` is one of the casualties)
- custom item / block / any-other tags stop working
- nothing is logged at ERROR level, which is why this is so hard to track down

This mod makes JEI's fallback **non-destructive**: JEI still gets its recipes, your tags stay.

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

Measured before/after on a test server (item registry tag count):

| | tags in `minecraft:item` | `minecraft:pause_screen_additions` |
|---|---|---|
| without JEI | 238 | 1 entry |
| with JEI | 236 | 0 entries |

The two missing ones were exactly the two provided by the server datapack.

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

- Minecraft **26.3**, Fabric Loader **0.19.5+**
- JEI **31.7 through 31.17** are all affected — the destructive code is not new, so
  **upgrading JEI will not fix this**. Verified against 31.3.0.17, 31.4.0.19, 31.7.0.38,
  31.16.0.83 and 31.17.0.109.
- Client-side only. Safe to install on a client joining any server; on servers that *do* sync
  recipes the fallback never runs and this mod does nothing at all.

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

Standard Fabric Loom setup:

```bash
./gradlew build        # jar lands in build/libs/
```

Without network access, `build-offline.ps1` reproduces the same jar with nothing but a JDK 25
and the jars already in your launcher's `libraries/` directory (this is how the released
artifact was built).

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
