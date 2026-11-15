package ${package};

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** NeoForge's net.neoforged.neoforge.event.RegisterCommandsEvent, posted from Fabric's CommandRegistrationCallback. */
public class RegisterCommandsEvent extends Event {

    final CommandDispatcher<CommandSourceStack> dispatcher;
    final Commands.CommandSelection selection;
    final CommandBuildContext context;

    RegisterCommandsEvent(CommandDispatcher<CommandSourceStack> dispatcher, Commands.CommandSelection selection, CommandBuildContext context) {
        this.dispatcher = dispatcher;
        this.selection = selection;
        this.context = context;
    }

    public CommandDispatcher<CommandSourceStack> getDispatcher() {
        return dispatcher;
    }

    public Commands.CommandSelection getCommandSelection() {
        return selection;
    }

    public CommandBuildContext getBuildContext() {
        return context;
    }
}
