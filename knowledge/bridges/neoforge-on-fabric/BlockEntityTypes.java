package ${package};

import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * NeoForge's constructors of the game's BlockEntityType (its patch adds them; the game has only the one taking a Set):
 * convert writes new BlockEntityType<>(...) as BlockEntityTypes.of(...), and Java picks the overload as it picked the
 * constructor. NeoForge's onlyOpCanSetNbt flag has no counterpart on Fabric (the game keeps its own list) and is ignored.
 */
public final class BlockEntityTypes {

	private BlockEntityTypes() {}

	public static <T extends BlockEntity> BlockEntityType<T> of(BlockEntityType.BlockEntitySupplier<? extends T> factory, Set<Block> blocks) {
		return new BlockEntityType<>(factory, blocks);
	}

	public static <T extends BlockEntity> BlockEntityType<T> of(BlockEntityType.BlockEntitySupplier<? extends T> factory, Block... blocks) {
		return new BlockEntityType<>(factory, Set.of(blocks));
	}

	public static <T extends BlockEntity> BlockEntityType<T> of(BlockEntityType.BlockEntitySupplier<? extends T> factory, Set<Block> blocks, boolean onlyOpCanSetNbt) {
		return new BlockEntityType<>(factory, blocks);
	}

	public static <T extends BlockEntity> BlockEntityType<T> of(BlockEntityType.BlockEntitySupplier<? extends T> factory, boolean onlyOpCanSetNbt, Block... blocks) {
		return new BlockEntityType<>(factory, Set.of(blocks));
	}
}
