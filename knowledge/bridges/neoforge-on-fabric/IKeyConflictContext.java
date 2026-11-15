package ${package};

/**
 * NeoForge's net.neoforged.neoforge.client.settings.IKeyConflictContext: when a key mapping is active, and which other
 * contexts its key may not share. On Fabric the bridge's key mappings (KeyConflictContext.keyMapping) read it in
 * isDown() and consumeClick(); the game's own conflict check in the controls screen does not.
 */
public interface IKeyConflictContext {

    boolean isActive();

    boolean conflicts(IKeyConflictContext other);
}
