package jeitagfix.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops JEI's client-side recipe fallback from wiping tags that came from the server.
 *
 * <p>When a server does not sync recipes to JEI (the log shows
 * {@code This <server> does not provide recipes to JEI}), JEI rebuilds a recipe map on the
 * client from a hand-made, vanilla-only {@code MultiPackResourceManager}. As part of that it
 * calls {@code Registry.PendingTags::apply} for every registry. {@code PendingTags.apply}
 * replaces {@code MappedRegistry.allTags} wholesale with whatever was computed, so every tag
 * that arrived from the server's datapacks disappears from the live registry.
 *
 * <p>Visible symptoms: server-provided dialog buttons vanish from the pause menu
 * ({@code minecraft:pause_screen_additions} is one of the tags that gets wiped), and any
 * other custom datapack tag goes with it.
 *
 * <p>Skipping the {@code apply} calls is not a valid fix. {@code prepareTagReload} also
 * creates {@code HolderSet.Named} instances for tags that exist in the vanilla pack, and only
 * {@code apply} binds them; leaving them unbound makes JEI throw
 * {@code IllegalStateException: Trying to access unbound tag} while walking recipes, which
 * ends in a {@code Network Protocol Error} disconnect.
 *
 * <p>Instead this mixin snapshots the live tag state before JEI runs and merges it back on
 * top of JEI's result afterwards, using the same public API
 * ({@code prepareTagReload} + {@code apply}). Every resulting {@code HolderSet.Named} is
 * bound, so nothing is left dangling, and JEI keeps the recipe map it built - that map comes
 * from a separate frozen {@code RegistryAccess} and is unaffected by this.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(targets = "mezz.jei.common.recipes.VanillaClientRecipeLoader")
public class VanillaClientRecipeLoaderMixin {

    @Unique
    private static final Logger JEITAGFIX$LOGGER = LoggerFactory.getLogger("jeitagfix");

    @Unique
    private static Map<ResourceKey, Map<TagKey, List<Holder>>> jeitagfix$snapshot;

    @Inject(method = "loadVanillaRecipeRegistry", at = @At("HEAD"), require = 1)
    private static void jeitagfix$captureTags(RegistryAccess access, CallbackInfoReturnable cir) {
        Map<ResourceKey, Map<TagKey, List<Holder>>> snap = new HashMap<>();
        try {
            access.registries().forEach(entry -> {
                Map<TagKey, List<Holder>> tags = new HashMap<>();
                ((Registry) entry.value()).getTags().forEach(o -> {
                    HolderSet.Named named = (HolderSet.Named) o;
                    if (!named.isBound()) {
                        return;
                    }
                    List<Holder> list = new ArrayList<>();
                    for (Object h : named) {
                        list.add((Holder) h);
                    }
                    tags.put(named.key(), list);
                });
                snap.put((ResourceKey) entry.key(), tags);
            });
            JEITAGFIX$LOGGER.debug("captured tag state for {} registries", snap.size());
        } catch (Throwable t) {
            JEITAGFIX$LOGGER.warn("could not capture tag state; tags may be lost", t);
            snap.clear();
        }
        jeitagfix$snapshot = snap;
    }

    @Inject(method = "loadVanillaRecipeRegistry", at = @At("RETURN"), require = 1)
    private static void jeitagfix$mergeTagsBack(RegistryAccess access, CallbackInfoReturnable cir) {
        Map<ResourceKey, Map<TagKey, List<Holder>>> snap = jeitagfix$snapshot;
        jeitagfix$snapshot = null;
        if (snap == null || snap.isEmpty()) {
            JEITAGFIX$LOGGER.debug("nothing to restore");
            return;
        }
        int touched = 0;
        int added = 0;
        int failed = 0;
        List<RegistryAccess.RegistryEntry> entries = new ArrayList<>();
        access.registries().forEach(e -> entries.add((RegistryAccess.RegistryEntry) e));
        for (RegistryAccess.RegistryEntry entry : entries) {
            Map<TagKey, List<Holder>> saved = snap.get((ResourceKey) entry.key());
            try {
                Registry registry = (Registry) entry.value();
                Map<TagKey, List<Holder>> merged = new HashMap<>();
                // Whatever JEI installed: vanilla-only, but every set is bound.
                registry.getTags().forEach(o -> {
                    HolderSet.Named named = (HolderSet.Named) o;
                    List<Holder> list = new ArrayList<>();
                    for (Object h : named) {
                        list.add((Holder) h);
                    }
                    merged.put(named.key(), list);
                });
                // The live / server-synced state wins over JEI's vanilla-only view.
                if (saved != null) {
                    int before = merged.size();
                    merged.putAll(saved);
                    added += merged.size() - before;
                }
                TagLoader.LoadResult result = new TagLoader.LoadResult((ResourceKey) entry.key(), merged);
                registry.prepareTagReload(result).apply();
                touched++;
            } catch (Throwable t) {
                failed++;
                JEITAGFIX$LOGGER.warn("could not restore tags for {}", entry.key(), t);
            }
        }
        JEITAGFIX$LOGGER.info("restored tags for {} registries ({} server tag(s) re-added), {} failed",
                touched, added, failed);
    }
}
