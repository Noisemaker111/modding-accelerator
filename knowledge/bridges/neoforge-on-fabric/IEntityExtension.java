package ${package};

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * NeoForge's net.neoforged.neoforge.common.extensions.IEntityExtension, whose methods NeoForge adds to Entity: here
 * as static methods taking the entity first, which modaccel convert's rules write in place of the calls
 * (entity.getPersistentData() becomes IEntityExtension.getPersistentData(entity)). The persistent data is kept, as on
 * NeoForge, in the entity's (or block entity's) saved data: a Fabric API persistent data attachment
 * (neoforge:persistent_data/MODID, NeoForge's data, as NeoForge writes it under NeoForgeData) holds the tag, so each
 * converted mod has its own tag rather than one shared by all mods, and the mod registers nothing of its own for it. A player's PlayerPersisted section is carried to the respawned player, as NeoForge's ServerPlayer
 * patch does (GameEvents).
 */
public final class IEntityExtension {

    /** NeoForge's Player.PERSISTED_NBT_TAG: the part of a player's persistent data kept through respawn. */
    static final String PERSISTED_NBT_TAG = "PlayerPersisted";

    static AttachmentType<CompoundTag> PERSISTENT_DATA;

    private IEntityExtension() {}

    /** Registers the persistent data attachment; the bridge's start-up (GameEvents.hook) calls it before any level loads, so saved data is read back. */
    static synchronized void register() {
        if (PERSISTENT_DATA != null) return;
        PERSISTENT_DATA = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("neoforge", "persistent_data/${modid}"),
            builder -> builder.persistent(CompoundTag.CODEC).initializer(CompoundTag::new)
        );
    }

    /** The entity's persistent data, created empty the first time; changes to the tag are saved with the entity. */
    public static CompoundTag getPersistentData(Entity entity) {
        return ((AttachmentTarget) entity).getAttachedOrCreate(PERSISTENT_DATA);
    }

    /** NeoForge's IBlockEntityExtension.getPersistentData: a block entity's persistent data, saved with it (call setChanged after a change). */
    public static CompoundTag getPersistentData(BlockEntity blockEntity) {
        return ((AttachmentTarget) blockEntity).getAttachedOrCreate(PERSISTENT_DATA);
    }

    /** NeoForge's ServerPlayer.restoreFrom: the respawned player keeps only the PlayerPersisted section. */
    static void copyPersisted(Player oldPlayer, Player newPlayer) {
        CompoundTag old = ((AttachmentTarget) oldPlayer).getAttached(PERSISTENT_DATA);
        CompoundTag kept = new CompoundTag();
        if (old != null && old.contains(PERSISTED_NBT_TAG)) kept.put(PERSISTED_NBT_TAG, old.get(PERSISTED_NBT_TAG));
        ((AttachmentTarget) newPlayer).setAttached(PERSISTENT_DATA, kept);
    }
}
