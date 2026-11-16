package ${package}.mixin;

import ${package}.extensions.IClientCommonPacketListenerExtension;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

/** Gives the client's connection NeoForge's send(payload) at runtime (the interface's defaults are the methods). */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientPayloadSendMixin implements IClientCommonPacketListenerExtension {}
