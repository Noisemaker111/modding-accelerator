package ${package};

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * A client command clicked in server text is checked as Fabric checks it: when the restricted source cannot run it and the
 * player's can, the player confirms first; a source checked for a click is not attended.
 */
class ClientCommandForwardingTest {

    /** A parse that reads the whole command and ends on an executable node. */
    private static final Predicate<ParseResults<String>> VALID = parse ->
        !parse.getReader().canRead() && parse.getExceptions().isEmpty() && parse.getContext().getCommand() != null;

    @Test
    void clickedCommandNeedingThePlayerIsConfirmed() {
        CommandDispatcher<String> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<String>literal("wipe").requires(source -> source.equals("player")).executes(context -> 1));
        dispatcher.register(LiteralArgumentBuilder.<String>literal("where").executes(context -> 1));
        assertTrue(ClientCommandForwarding.needsConfirmation(dispatcher, "wipe", "player", "restricted", VALID));
        assertFalse(ClientCommandForwarding.needsConfirmation(dispatcher, "where", "player", "restricted", VALID));
        assertFalse(ClientCommandForwarding.needsConfirmation(dispatcher, "unknown", "player", "restricted", VALID));
    }

    @Test
    void attendedIsFabricsNotAlwaysTrue() {
        assertTrue(new ClientCommandForwarding.Source(null, null, true).attended());
        assertFalse(new ClientCommandForwarding.Source(null, null, false).attended());
    }
}
