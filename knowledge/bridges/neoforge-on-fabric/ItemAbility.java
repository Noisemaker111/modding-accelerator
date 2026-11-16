package ${package};

import com.mojang.serialization.Codec;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge's net.neoforged.neoforge.common.ItemAbility: a named action a tool can perform (shears_dig, axe_strip), one
 * object per name. On Fabric no item reports abilities (NeoForge's canPerformAction is not there), so these are names.
 */
public final class ItemAbility {

    private static final Map<String, ItemAbility> ABILITIES = new ConcurrentHashMap<>();

    public static final Codec<ItemAbility> CODEC = Codec.STRING.xmap(ItemAbility::get, ItemAbility::name);

    private final String name;

    private ItemAbility(String name) {
        this.name = name;
    }

    public static Collection<ItemAbility> getActions() {
        return Collections.unmodifiableCollection(ABILITIES.values());
    }

    public static ItemAbility get(String name) {
        return ABILITIES.computeIfAbsent(name, ItemAbility::new);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "ItemAbility[" + name + "]";
    }
}
