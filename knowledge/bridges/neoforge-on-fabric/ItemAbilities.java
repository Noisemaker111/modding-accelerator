package ${package};

import java.util.Set;

/** NeoForge's net.neoforged.neoforge.common.ItemAbilities: the abilities NeoForge names, by the same names. */
public final class ItemAbilities {

    private ItemAbilities() {}

    public static final ItemAbility SHEARS_DIG = ItemAbility.get("shears_dig");
    public static final ItemAbility SHOVEL_DOUSE = ItemAbility.get("shovel_douse");
    public static final ItemAbility SWORD_SWEEP = ItemAbility.get("sword_sweep");
    public static final ItemAbility SHEARS_HARVEST = ItemAbility.get("shears_harvest");
    public static final ItemAbility SHEARS_REMOVE_ARMOR = ItemAbility.get("shears_remove_armor");
    public static final ItemAbility SHEARS_CARVE = ItemAbility.get("shears_carve");
    public static final ItemAbility SHEARS_DISARM = ItemAbility.get("shears_disarm");
    public static final ItemAbility SHEARS_TRIM = ItemAbility.get("shears_trim");
    public static final ItemAbility FISHING_ROD_CAST = ItemAbility.get("fishing_rod_cast");
    public static final ItemAbility TRIDENT_THROW = ItemAbility.get("trident_throw");
    public static final ItemAbility BRUSH_BRUSH = ItemAbility.get("brush_brush");
    public static final ItemAbility FIRESTARTER_LIGHT = ItemAbility.get("firestarter_light");
    public static final ItemAbility SPYGLASS_SCOPE = ItemAbility.get("spyglass_scope");

    public static final Set<ItemAbility> DEFAULT_SHEARS_ACTIONS = Set.of(SHEARS_DIG, SHEARS_HARVEST, SHEARS_REMOVE_ARMOR, SHEARS_CARVE, SHEARS_DISARM, SHEARS_TRIM);
    public static final Set<ItemAbility> DEFAULT_FISHING_ROD_ACTIONS = Set.of(FISHING_ROD_CAST);
    public static final Set<ItemAbility> DEFAULT_TRIDENT_ACTIONS = Set.of(TRIDENT_THROW);
    public static final Set<ItemAbility> DEFAULT_BRUSH_ACTIONS = Set.of(BRUSH_BRUSH);
    public static final Set<ItemAbility> DEFAULT_FLINT_ACTIONS = Set.of(FIRESTARTER_LIGHT);
    public static final Set<ItemAbility> DEFAULT_FIRECHARGE_ACTIONS = Set.of(FIRESTARTER_LIGHT);
    public static final Set<ItemAbility> DEFAULT_SPYGLASS_ACTIONS = Set.of(SPYGLASS_SCOPE);
}
