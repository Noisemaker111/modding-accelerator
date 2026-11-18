package ${package};

import java.util.Set;

/** NeoForge 21.1's net.neoforged.neoforge.common.EffectCures: the game's cures, and the ones every effect has unless it says otherwise. */
public final class EffectCures {

    private EffectCures() {}

    public static final EffectCure MILK = EffectCure.get("milk");
    public static final EffectCure PROTECTED_BY_TOTEM = EffectCure.get("protected_by_totem");
    public static final EffectCure HONEY = EffectCure.get("honey");
    public static final Set<EffectCure> DEFAULT_CURES = Set.of(MILK, PROTECTED_BY_TOTEM);
}
