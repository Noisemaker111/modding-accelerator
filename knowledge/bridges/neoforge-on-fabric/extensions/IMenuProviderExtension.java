package ${package}.extensions;

/**
 * NeoForge's IMenuProviderExtension.shouldTriggerClientSideContainerClosingOnOpen: whether opening this menu while
 * another is open tells the client to close the old one first (true, the game's way) or closes it on the server only, so
 * the client's screen goes straight to the new one. Added to MenuProvider by MenuProviderMixin, honoured by
 * MenuOpeningMixin in ServerPlayer.openMenu, and for the compile by Loom's interface injection.
 */
public interface IMenuProviderExtension {

    default boolean shouldTriggerClientSideContainerClosingOnOpen() {
        return true;
    }
}
