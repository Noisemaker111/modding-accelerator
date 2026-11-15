package ${package};

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import ${package}.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import ${package}.fabric.api.client.command.v2.FabricClientCommandSource;
import ${package}.mixin.ClientPacketListenerCommands;

/**
 * Fabric's client commands on NeoForge's. Each time NeoForge posts RegisterClientCommandsEvent (when the client gets
 * the server's command tree), ClientCommandRegistrationCallback fills a new dispatcher of FabricClientCommandSource,
 * and each of its root literals is registered in NeoForge's dispatcher (of CommandSourceStack) as a literal of the same
 * name with a greedy argument: running it, or asking it for suggestions, parses the whole command line in the mod's own
 * dispatcher with a FabricClientCommandSource, so arguments, requirements, redirects and suggestions are the mod's
 * own. A command clicked in text (the game's sendUnattendedCommand, which NeoForge runs client commands from too) is
 * checked as Fabric checks it: when the game's restricted suggestion provider cannot run it and the player's can, the
 * game's confirmation screen asks the player first; a source reports attended() false while it is checked, true when it
 * runs. A command NeoForge does not know (an unknown root) goes to the server, as on Fabric. Fabric's /fcc help command
 * is not added.
 */
public final class ClientCommandForwarding {

    private static volatile CommandDispatcher<FabricClientCommandSource> active;
    private static volatile CommandBuildContext lastContext;

    private ClientCommandForwarding() {}

    static void client() {
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, ClientCommandForwarding::register);
    }

    public static CommandDispatcher<FabricClientCommandSource> active() {
        return active;
    }

    /** Fills the mod's dispatcher again with the last build context (the roots NeoForge forwards stay as they were). */
    public static void refresh() {
        if (lastContext != null) active = build(lastContext);
    }

    private static CommandDispatcher<FabricClientCommandSource> build(CommandBuildContext context) {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        ClientCommandRegistrationCallback.EVENT.invoker().register(dispatcher, context);
        return dispatcher;
    }

    private static void register(RegisterClientCommandsEvent event) {
        lastContext = event.getBuildContext();
        CommandDispatcher<FabricClientCommandSource> mine = build(event.getBuildContext());
        active = mine;
        for (CommandNode<FabricClientCommandSource> root : mine.getRoot().getChildren()) {
            String name = root.getName();
            event.getDispatcher().register(
                LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                    .requires(source -> canUse(name))
                    .executes(ClientCommandForwarding::run)
                    .then(
                        RequiredArgumentBuilder.<CommandSourceStack, String>argument("arguments", StringArgumentType.greedyString())
                            .suggests(ClientCommandForwarding::suggest)
                            .executes(ClientCommandForwarding::run)
                    )
            );
        }
    }

    private static boolean canUse(String name) {
        CommandDispatcher<FabricClientCommandSource> dispatcher = active;
        Source source = Source.current();
        if (dispatcher == null || source == null) return false;
        CommandNode<FabricClientCommandSource> node = dispatcher.getRoot().getChild(name);
        return node != null && node.canUse(source);
    }

    /** Where the forwarded root starts in the input (after a slash, when NeoForge keeps one). */
    private static int rootStart(CommandContext<CommandSourceStack> context) {
        return context.getNodes().isEmpty() ? 0 : context.getNodes().get(0).getRange().getStart();
    }

    private static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Source source = Source.current();
        CommandDispatcher<FabricClientCommandSource> dispatcher = active;
        if (source == null || dispatcher == null) return 0;
        String command = context.getInput().substring(rootStart(context));
        if (UNATTENDED.get()) return runClicked(dispatcher, command, source);
        return dispatcher.execute(command, source);
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        Source source = Source.current();
        if (source == null || active == null) return Suggestions.empty();
        int start = rootStart(context);
        String command = builder.getInput().substring(start);
        ParseResults<FabricClientCommandSource> parse = active.parse(command, source);
        return active.getCompletionSuggestions(parse, command.length()).thenApply(found -> {
            List<Suggestion> shifted = new ArrayList<>();
            for (Suggestion s : found.getList()) shifted.add(new Suggestion(shift(s.getRange(), start), s.getText(), s.getTooltip()));
            return new Suggestions(shift(found.getRange(), start), shifted);
        });
    }

    private static StringRange shift(StringRange range, int by) {
        return StringRange.between(range.getStart() + by, range.getEnd() + by);
    }

    /**
     * A command clicked in text: the game's restricted suggestion provider (a command the server's text can run without
     * asking) cannot run it, and the player's own can. Fabric asks the player then (ClientCommandInternals); valid is the
     * game's own test of a parse (ClientPacketListener.isValidCommand).
     */
    static <S> boolean needsConfirmation(CommandDispatcher<S> dispatcher, String command, S source, S restricted, Predicate<ParseResults<S>> valid) {
        if (!valid.test(dispatcher.parse(command, source))) return false;
        return !valid.test(dispatcher.parse(command, restricted));
    }

    private static int runClicked(CommandDispatcher<FabricClientCommandSource> dispatcher, String command, Source source) throws CommandSyntaxException {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        Source restricted = connection == null ? null : new Source(Minecraft.getInstance(), ((ClientPacketListenerCommands) connection).mmtBridge$restrictedSuggestionsProvider(), false);
        if (restricted == null || !needsConfirmation(dispatcher, command, source, restricted, ClientPacketListenerCommands::mmtBridge$isValidCommand)) {
            return dispatcher.execute(command, source);
        }
        // As Fabric's openSendConfirmationWindow: the game's own confirmation screen, then the command as the player's.
        Minecraft minecraft = source.getClient();
        Screen previous = minecraft.gui.screen();
        minecraft.gui.setScreen(new ConfirmScreen(
            accepted -> {
                if (accepted) {
                    try {
                        dispatcher.execute(command, source);
                    } catch (CommandSyntaxException e) {
                        source.sendError(Component.literal(e.getMessage()));
                    }
                }
                minecraft.gui.setScreen(previous);
            },
            Component.translatable("multiplayer.confirm_command.title"),
            Component.translatable("multiplayer.confirm_command.permissions_required", Component.literal(command).withStyle(ChatFormatting.YELLOW)),
            Component.translatable("multiplayer.confirm_command.run_command"),
            previous != null ? CommonComponents.GUI_BACK : CommonComponents.GUI_CANCEL
        ));
        return 1;
    }

    /** Set while the game runs a command clicked in text (UnattendedCommandMixin around sendUnattendedCommand). */
    public static final ThreadLocal<Boolean> UNATTENDED = ThreadLocal.withInitial(() -> false);

    /**
     * The source of one client command: the connection's own suggestion provider, with Fabric's feedback to the chat.
     * attended is Fabric's: true for the player's own provider (what it typed, or a clicked command once confirmed), false
     * for the restricted one a click is checked with.
     */
    static final class Source implements FabricClientCommandSource {
        private final Minecraft minecraft;
        private final ClientSuggestionProvider provider;
        private final boolean attended;

        Source(Minecraft minecraft, ClientSuggestionProvider provider, boolean attended) {
            this.minecraft = minecraft;
            this.provider = provider;
            this.attended = attended;
        }

        static Source current() {
            Minecraft minecraft = Minecraft.getInstance();
            ClientPacketListener connection = minecraft.getConnection();
            return connection == null ? null : new Source(minecraft, connection.getSuggestionsProvider(), true);
        }

        @Override
        public void sendFeedback(Component message) {
            minecraft.gui.hud.getChat().addClientSystemMessage(message);
            minecraft.getNarrator().saySystemChatQueued(message);
        }

        @Override
        public void sendError(Component message) {
            sendFeedback(Component.empty().append(message).withStyle(ChatFormatting.RED));
        }

        @Override
        public Minecraft getClient() {
            return minecraft;
        }

        @Override
        public LocalPlayer getPlayer() {
            return minecraft.player;
        }

        @Override
        public ClientLevel getLevel() {
            return minecraft.level;
        }

        @Override
        public boolean attended() {
            return attended;
        }

        @Override
        public Collection<String> getOnlinePlayerNames() {
            return provider.getOnlinePlayerNames();
        }

        @Override
        public Collection<String> getCustomTabSuggestions() {
            return provider.getCustomTabSuggestions();
        }

        @Override
        public Collection<String> getSelectedEntities() {
            return provider.getSelectedEntities();
        }

        @Override
        public Collection<String> getAllTeams() {
            return provider.getAllTeams();
        }

        @Override
        public Stream<Identifier> getAvailableSounds() {
            return provider.getAvailableSounds();
        }

        @Override
        public Stream<Identifier> getAvailablePostEffects() {
            return provider.getAvailablePostEffects();
        }

        @Override
        public CompletableFuture<Suggestions> customSuggestion(CommandContext<?> context) {
            return provider.customSuggestion(context);
        }

        @Override
        public Collection<SharedSuggestionProvider.TextCoordinates> getRelevantCoordinates() {
            return provider.getRelevantCoordinates();
        }

        @Override
        public Collection<SharedSuggestionProvider.TextCoordinates> getAbsoluteCoordinates() {
            return provider.getAbsoluteCoordinates();
        }

        @Override
        public Set<ResourceKey<Level>> levels() {
            return provider.levels();
        }

        @Override
        public RegistryAccess registryAccess() {
            return provider.registryAccess();
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            return provider.enabledFeatures();
        }

        @Override
        public <E> CompletableFuture<Suggestions> suggestRegistryElements(
            ResourceKey<? extends Registry<E>> key,
            SharedSuggestionProvider.ElementSuggestionType elements,
            SuggestionsBuilder builder,
            CommandContext<?> context,
            Predicate<E> filter
        ) {
            return provider.suggestRegistryElements(key, elements, builder, context, filter);
        }

        @Override
        public PermissionSet permissions() {
            return provider.permissions();
        }
    }
}
