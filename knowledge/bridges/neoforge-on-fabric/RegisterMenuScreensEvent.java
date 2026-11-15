package ${package};

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RegisterMenuScreensEvent, posted once when the client entrypoint ends
 * (ClientBridge.client, before FMLClientSetupEvent, as NeoForge posts it while the client mods load). Each screen goes to
 * the game's MenuScreens.register, which Fabric API's menu module makes accessible; a second screen for one menu type
 * throws, as on NeoForge.
 */
public class RegisterMenuScreensEvent extends Event implements IModBusEvent {

    private final Set<MenuType<?>> registered = new HashSet<>();

    RegisterMenuScreensEvent() {}

    public <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void register(MenuType<? extends M> menuType, MenuScreens.ScreenConstructor<M, U> screenConstructor) {
        if (!registered.add(menuType)) {
            throw new IllegalStateException("Duplicate attempt to register screen: " + BuiltInRegistries.MENU.getKey(menuType));
        }
        MenuScreens.register(menuType, screenConstructor);
    }
}
