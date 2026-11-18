package ${package};

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** NeoForge 21.1's net.neoforged.neoforge.common.EffectCure: what can end an effect early (milk, a totem, honey), one object per name. */
public final class EffectCure {

    private static final Map<String, EffectCure> CURES = new ConcurrentHashMap<>();
    private final String name;

    private EffectCure(String name) {
        this.name = name;
    }

    public static EffectCure get(String name) {
        return CURES.computeIfAbsent(name, EffectCure::new);
    }

    public String name() {
        return this.name;
    }

    @Override
    public String toString() {
        return "EffectCure[" + this.name + "]";
    }
}
