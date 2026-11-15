package ${package};

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * NeoForge 21.1's net.neoforged.neoforge.client.event.RegisterClientCommandsEvent, posted from Fabric API's
 * ClientCommandRegistrationCallback with a dispatcher of the game's CommandSourceStack, as NeoForge's. Fabric API's client
 * commands take its FabricClientCommandSource instead, so each command registered here becomes a Fabric client command of
 * the same name that hands its whole line (and its suggestions) to this dispatcher, run as the client player with the
 * player's permission level; its messages go to Fabric API's sendFeedback. Fabric's command tree shows one greedy argument
 * after the name where NeoForge shows the command's own arguments.
 */
public class RegisterClientCommandsEvent extends Event {

    private final CommandDispatcher<CommandSourceStack> dispatcher;
    private final CommandBuildContext context;

    public RegisterClientCommandsEvent(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        this.dispatcher = dispatcher;
        this.context = context;
    }

    public CommandDispatcher<CommandSourceStack> getDispatcher() {
        return dispatcher;
    }

    public CommandBuildContext getBuildContext() {
        return context;
    }

    /** From ClientGameEvents.hook(). */
    static void hook() {
        ClientCommandRegistrationCallback.EVENT.register((fabric, context) -> {
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            Bus.INSTANCE.post(new RegisterClientCommandsEvent(dispatcher, context));
            for (CommandNode<CommandSourceStack> node : dispatcher.getRoot().getChildren()) {
                fabric.register(
                    ClientCommandManager.literal(node.getName())
                        .executes(command -> dispatcher.execute(command.getInput(), source(command.getSource())))
                        .then(
                            ClientCommandManager.argument("arguments", StringArgumentType.greedyString())
                                .suggests((command, builder) -> dispatcher.getCompletionSuggestions(dispatcher.parse(builder.getInput(), source(command.getSource())), builder.getInput().length()))
                                .executes(command -> dispatcher.execute(command.getInput(), source(command.getSource())))
                        )
                );
            }
        });
    }

    /** The client player as a CommandSourceStack (as NeoForge's ClientCommandSourceStack), its messages to Fabric's source. */
    static CommandSourceStack source(FabricClientCommandSource fabric) {
        Player player = fabric.getPlayer();
        CommandSource out = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                fabric.sendFeedback(message);
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        int level = 0;
        while (level < 4 && player.hasPermissions(level + 1)) level++;
        return new CommandSourceStack(out, player.position(), player.getRotationVector(), null, level, player.getName().getString(), player.getDisplayName(), null, player);
    }
}
