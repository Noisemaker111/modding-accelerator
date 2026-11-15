package com.prismlab.block;


import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;


import com.prismlab.PrismBlockEntities;
import com.prismlab.block.entity.PrismNodeEntity;

/** A block with a live block entity: it charges from light and answers a right click. */
public class PrismNode extends BaseEntityBlock {
	public PrismNode(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new PrismNodeEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return createTickerHelper(type, PrismBlockEntities.PRISM_NODE, PrismNodeEntity::tick);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof PrismNodeEntity node)) {
			return super.useWithoutItem(state, level, pos, player, hit);
		}

		node.addCharge(8);

		if (level.isClientSide()) {
			player.sendOverlayMessage(Component.literal("Prism node charge: " + node.charge()));
		}

		return InteractionResult.SUCCESS;
	}
}


