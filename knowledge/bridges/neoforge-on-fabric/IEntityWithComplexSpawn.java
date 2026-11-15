package ${package};

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * NeoForge's net.neoforged.neoforge.entity.IEntityWithComplexSpawn: an entity that sends extra data when it is spawned
 * on a client. As on NeoForge, the server writes it (writeSpawnData) into a payload sent in the entity's spawn bundle
 * right after the game's add-entity packet (ComplexSpawnMixin in ServerEntity.sendPairingData, where NeoForge's patch
 * adds it), and the client reads it (readSpawnData) into the entity it just added. The payload is
 * AdvancedAddEntityPayload, sent only to clients that have it (a client with this converted mod).
 */
public interface IEntityWithComplexSpawn {

    void writeSpawnData(RegistryFriendlyByteBuf buffer);

    void readSpawnData(RegistryFriendlyByteBuf additionalData);
}
