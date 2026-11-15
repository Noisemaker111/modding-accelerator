package ${package};

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import java.lang.reflect.Constructor;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * NeoForge's net.neoforged.neoforge.client.event.RegisterClientCommandsEvent, posted from Fabric API's
 * ClientCommandRegistrationCallback with a dispatcher of the game's CommandSourceStack, as NeoForge's. Fabric API's client
 * commands take its FabricClientCommandSource instead, so each command registered here becomes a Fabric client command of
 * the same name that hands its whole line (and its suggestions) to this dispatcher, run as the client player with the
 * player's permissions; its messages go to Fabric API's sendFeedback. Fabric's command tree shows one greedy argument
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
                    ClientCommands.literal(node.getName())
                        .executes(command -> dispatcher.execute(command.getInput(), source(command.getSource())))
                        .then(
                            ClientCommands.argument("arguments", StringArgumentType.greedyString())
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
        // The game's constructors differ between its versions; the one that takes the executing entity is used.
        for (Constructor<?> constructor : CommandSourceStack.class.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 0 || types[types.length - 1] != Entity.class) continue;
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == CommandSource.class) args[i] = out;
                else if (types[i] == Vec3.class) args[i] = player.position();
                else if (types[i] == Vec2.class) args[i] = player.getRotationVector();
                else if (types[i] == PermissionSet.class) args[i] = player.permissions();
                else if (types[i] == String.class) args[i] = player.getName().getString();
                else if (types[i] == Component.class) args[i] = player.getDisplayName();
                else if (types[i] == Entity.class) args[i] = player;
            }
            try {
                return (CommandSourceStack) constructor.newInstance(args);
            } catch (ReflectiveOperationException failed) {
                throw new IllegalStateException(failed);
            }
        }
        throw new IllegalStateException("No CommandSourceStack constructor takes an entity");
    }
}
