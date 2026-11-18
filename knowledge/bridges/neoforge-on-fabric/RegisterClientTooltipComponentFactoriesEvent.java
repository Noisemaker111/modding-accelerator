package ${package};

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent, posted on the mod bus
 * when the client starts (ClientBridge.client): each factory is kept by its tooltip component's class, and Fabric API's
 * ClientTooltipComponentCallback () turns a tooltip component of exactly that class into the factory's client component, as
 * NeoForge looks a factory up by the component's own class.
 */
public class RegisterClientTooltipComponentFactoriesEvent extends Event implements IModBusEvent {

    private final Map<Class<? extends TooltipComponent>, Function<TooltipComponent, ClientTooltipComponent>> factories = new HashMap<>();

    public RegisterClientTooltipComponentFactoriesEvent() {}

    @SuppressWarnings("unchecked")
    public <T extends TooltipComponent> void register(Class<T> type, Function<? super T, ? extends ClientTooltipComponent> factory) {
        if (this.factories.put(type, (Function<TooltipComponent, ClientTooltipComponent>) factory) != null) throw new IllegalStateException("Duplicate registration for " + type);
    }

    /** After the event: Fabric asks this mod's factories for each tooltip component. */
    void hook() {
        if (this.factories.isEmpty()) return;
        Map<Class<? extends TooltipComponent>, Function<TooltipComponent, ClientTooltipComponent>> registered = Map.copyOf(this.factories);
        ClientTooltipComponentCallback.EVENT.register(data -> {
            Function<TooltipComponent, ClientTooltipComponent> factory = registered.get(data.getClass());
            return factory == null ? null : factory.apply(data);
        });
    }
}
