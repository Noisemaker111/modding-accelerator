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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
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

/**
 * Fabric's client commands on NeoForge's. Each time NeoForge posts RegisterClientCommandsEvent (when the client gets
 * the server's command tree), ClientCommandRegistrationCallback fills a new dispatcher of FabricClientCommandSource,
 * and each of its root literals is registered in NeoForge's dispatcher (of CommandSourceStack) as a literal of the same
 * name with a greedy argument: running it, or asking it for suggestions, parses the whole command line in the mod's own
 * dispatcher with a FabricClientCommandSource, so arguments, requirements, redirects and suggestions are the mod's
 * own. Differences from Fabric: NeoForge runs client commands typed in chat and clicked in text alike, without Fabric's
 * confirmation screen for a clicked command that needs more permissions, so attended() is always true; a command
 * NeoForge does not know (an unknown root) goes to the server, as on Fabric; Fabric's /fcc help command is not added.
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
        if (source == null || active == null) return 0;
        return active.execute(context.getInput().substring(rootStart(context)), source);
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

    /** The source of one client command: the connection's own suggestion provider, with Fabric's feedback to the chat. */
    static final class Source implements FabricClientCommandSource {
        private final Minecraft minecraft;
        private final ClientSuggestionProvider provider;

        private Source(Minecraft minecraft, ClientSuggestionProvider provider) {
            this.minecraft = minecraft;
            this.provider = provider;
        }

        static Source current() {
            Minecraft minecraft = Minecraft.getInstance();
            ClientPacketListener connection = minecraft.getConnection();
            return connection == null ? null : new Source(minecraft, connection.getSuggestionsProvider());
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
            return true;
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
