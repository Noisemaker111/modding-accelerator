package ${package};

import java.util.List;
import java.util.Map;
import net.minecraft.client.RecipeBookCategories;
import net.minecraft.client.model.HumanoidModel;

/**
 * NeoForge 21.1's net.neoforged.fml.common.asm.enumextension.EnumProxy: an enum constant a mod adds to a game enum
 * (declared in its enumextensions.json, built into the enum by FML at start). Fabric cannot add constants to the game's
 * enums, so getValue gives the game's nearest constant (RecipeBookCategories.UNKNOWN, HumanoidModel.ArmPose.ITEM,
 * RecipeBookType.CRAFTING, otherwise the enum's first): a mod's added recipe book tab or arm pose is the game's default one on Fabric.
 */
public class EnumProxy<T extends Enum<T>> {

    private static final Map<String, String> FALLBACK = Map.of(
        "net.minecraft.client.RecipeBookCategories", "UNKNOWN",
        "net.minecraft.client.model.HumanoidModel$ArmPose", "ITEM",
        "net.minecraft.world.inventory.RecipeBookType", "CRAFTING"
    );

    private final Class<T> enumClass;
    private final List<Object> parameters;

    public EnumProxy(Class<T> enumClass, Object... parameters) {
        this.enumClass = enumClass;
        this.parameters = List.of(parameters);
    }

    public EnumProxy(Class<T> enumClass, List<Object> parameters) {
        this.enumClass = enumClass;
        this.parameters = List.copyOf(parameters);
    }

    public T getValue() {
        return fallback(this.enumClass);
    }

    /** The game's constant that stands in for one a mod adds to the enum (mmt convert writes this for its valueOf lookups). */
    public static <T extends Enum<T>> T fallback(Class<T> enumClass) {
        String name = FALLBACK.get(enumClass.getName());
        if (name != null) return Enum.valueOf(enumClass, name);
        return enumClass.getEnumConstants()[0];
    }

    public Object getParameter(int index) {
        return this.parameters.get(index);
    }

    public int getParameterCount() {
        return this.parameters.size();
    }

    static void typesUsed(RecipeBookCategories categories, HumanoidModel.ArmPose pose) {}
}
