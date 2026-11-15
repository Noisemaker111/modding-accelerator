package ${package};

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.fabricmc.api.EnvType;

/**
 * NeoForge's net.neoforged.fml.common.EventBusSubscriber. NeoForge finds these classes by scanning the mod; on Fabric,
 * modaccel convert lists them at conversion time and the mod's entrypoint registers each one's static @SubscribeEvent
 * methods (value CLIENT: the client entrypoint). Dist became Fabric's EnvType with the rest of the mod.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventBusSubscriber {
    EnvType[] value() default { EnvType.CLIENT, EnvType.SERVER };

    String modid() default "";

    /** NeoForge before 26.1 named the bus; here both deliver to the mod's one bus. */
    Bus bus() default Bus.GAME;

    enum Bus {
        GAME,
        MOD
    }
}
