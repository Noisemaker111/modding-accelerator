package ${package};

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;

/** NeoForge's client events, each posted from the Fabric API callback that fires at the same moment. */
final class ClientGameEvents {

    private ClientGameEvents() {}

    static void hook() {
        ClientTickEvents.START_LEVEL_TICK.register(level -> Bus.INSTANCE.post(new LevelTickEvent.Pre(() -> true, level)));
        ClientTickEvents.END_LEVEL_TICK.register(level -> Bus.INSTANCE.post(new LevelTickEvent.Post(() -> true, level)));
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> Bus.INSTANCE.post(new ItemTooltipEvent(stack, Minecraft.getInstance().player, lines, flag, context)));
    }

    static void keyMappings() {
        Bus.INSTANCE.post(new RegisterKeyMappingsEvent(KeyMappingHelper::registerKeyMapping));
    }
}
