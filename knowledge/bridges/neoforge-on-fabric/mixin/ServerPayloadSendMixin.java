package ${package}.mixin;

import ${package}.extensions.IServerCommonPacketListenerExtension;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

/** Gives a player's connection NeoForge's send(payload) at runtime (the interface's defaults are the methods). */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerPayloadSendMixin implements IServerCommonPacketListenerExtension {}
