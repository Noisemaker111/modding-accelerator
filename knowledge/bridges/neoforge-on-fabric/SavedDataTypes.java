package ${package};

import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * NeoForge's constructors of the game's SavedDataType: convert writes new SavedDataType<>(...) as SavedDataTypes.of(...),
 * and Java picks the overload as it picked the constructor. NeoForge lets a mod's data have no data fixer type (null);
 * the game always runs one, so the bridge gives such data the fixer type of random sequences, whose fixes never touch a
 * mod's data (data saved by this game version is not fixed at all).
 */
public final class SavedDataTypes {

	private SavedDataTypes() {}

	public static <T extends SavedData> SavedDataType<T> of(Identifier id, Supplier<T> constructor, Codec<T> codec, DataFixTypes dataFixType) {
		return new SavedDataType<>(id, constructor, codec, dataFixType == null ? DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES : dataFixType);
	}

	public static <T extends SavedData> SavedDataType<T> of(Identifier id, Supplier<T> constructor, Codec<T> codec) {
		return new SavedDataType<>(id, constructor, codec, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);
	}
}
