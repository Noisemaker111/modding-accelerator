package ${package};

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.junit.jupiter.api.Test;

/**
 * A NeoForge mod's tooltip component factories answer Fabric API's tooltip callback: a component of the registered
 * class gets the factory's client component, made from that component; a component of another class gets none. Asked
 * through Fabric's own event (ClientTooltipComponentCallback on 26.x, TooltipComponentCallback on 1.21.1).
 */
class RegisterClientTooltipComponentFactoriesEventTest {

    record Bundle(int size) implements TooltipComponent {}

    record Other() implements TooltipComponent {}

    @Test
    void factoriesAnswerFabricsCallback() throws Exception {
        ClientTooltipComponent client = (ClientTooltipComponent) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ClientTooltipComponent.class }, (p, m, a) -> null);
        TooltipComponent[] seen = new TooltipComponent[1];
        RegisterClientTooltipComponentFactoriesEvent event = new RegisterClientTooltipComponentFactoriesEvent();
        event.register(Bundle.class, bundle -> {
            seen[0] = bundle;
            return client;
        });
        event.hook();

        Bundle bundle = new Bundle(3);
        assertSame(client, ask(bundle));
        assertSame(bundle, seen[0]);
        assertNull(ask(new Other()));
    }

    static Object ask(TooltipComponent component) throws Exception {
        Class<?> callback;
        try {
            callback = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback");
        } catch (ClassNotFoundException e) {
            callback = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback");
        }
        Object event = callback.getField("EVENT").get(null);
        Object invoker = event.getClass().getMethod("invoker").invoke(event);
        for (Method m : callback.getMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == TooltipComponent.class) return m.invoke(invoker, component);
        }
        throw new AssertionError("no tooltip method on " + callback);
    }
}
