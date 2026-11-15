package ${package}.mixin;

import com.mojang.brigadier.ParseResults;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The game's own pieces Fabric checks a clicked client command with (ClientCommandInternals.requiresConfirmation): the
 * connection's restricted suggestion provider, whose permissions are what server text may use without asking, and the
 * game's test of whether a parse is a whole valid command.
 */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerCommands {

    @Accessor("restrictedSuggestionsProvider")
    ClientSuggestionProvider mmtBridge$restrictedSuggestionsProvider();

    @Invoker("isValidCommand")
    static boolean mmtBridge$isValidCommand(ParseResults<?> parse) {
        throw new AssertionError("Mixin applies ClientPacketListenerCommands");
    }
}
