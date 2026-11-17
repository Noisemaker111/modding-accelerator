package ${package};

import com.mojang.brigadier.arguments.ArgumentType;
import java.util.Map;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;

/**
 * The static method NeoForge's patch adds to the game's ArgumentTypeInfos: convert writes ArgumentTypeInfos.registerByClass(...)
 * as ArgumentTypeInfosExtension.registerByClass(...) (bridges[].statics). As NeoForge's, it records which info serializes
 * an argument class (the game's BY_CLASS map, opened by the mod's access widener); the mod registers the info itself.
 */
public final class ArgumentTypeInfosExtension {

    private ArgumentTypeInfosExtension() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized <A extends ArgumentType<?>, T extends ArgumentTypeInfo.Template<A>, I extends ArgumentTypeInfo<A, T>> I registerByClass(Class<A> infoClass, I argumentTypeInfo) {
        ((Map) ArgumentTypeInfos.BY_CLASS).put(infoClass, argumentTypeInfo);
        return argumentTypeInfo;
    }
}
