package ${package}.extensions;

import java.lang.reflect.Proxy;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition;
import net.fabricmc.fabric.impl.datagen.FabricDataGenHelper;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.data.recipes.RecipeOutput;
import ${package}.conditions.Conditions;
import ${package}.conditions.ICondition;

/**
 * What NeoForge adds to the game's RecipeOutput: withConditions, an output whose recipes (and their advancements) are
 * written with load conditions. On Fabric they are written as Fabric API resource conditions, the way Fabric's own
 * FabricRecipeProvider.withConditions marks them (FabricDataGenHelper.addConditions on each recipe and advancement);
 * the output is a proxy, so it passes on every other method of the game's RecipeOutput whatever the game version has.
 */
public interface IRecipeOutputExtension {

    default RecipeOutput withConditions(ICondition... conditions) {
        RecipeOutput self = (RecipeOutput) this;
        ResourceCondition[] fabric = Conditions.fabricAll(conditions);
        if (fabric.length == 0) return self;
        return (RecipeOutput) Proxy.newProxyInstance(RecipeOutput.class.getClassLoader(), new Class<?>[] { RecipeOutput.class }, (proxy, method, args) -> {
            if (method.getName().equals("accept") && args != null && args.length >= 2) {
                FabricDataGenHelper.addConditions(args[1], fabric);
                if (args.length >= 3 && args[2] instanceof AdvancementHolder advancement) FabricDataGenHelper.addConditions(advancement.value(), fabric);
            }
            return method.invoke(self, args);
        });
    }
}
