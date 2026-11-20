package ${package};

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;

/** NeoForge 21.1's net.neoforged.neoforge.client.IArmPoseTransformer: how an added arm pose moves the arm (EnumProxy gives the game's own poses on Fabric, so it is not asked). */
@FunctionalInterface
public interface IArmPoseTransformer {

    void applyTransform(HumanoidModel<?> model, LivingEntity entity, HumanoidArm arm);
}
