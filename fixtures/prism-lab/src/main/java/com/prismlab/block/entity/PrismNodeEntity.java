package com.prismlab.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.prismlab.PrismBlockEntities;

/** Holds charge; gains one point per second of light and keeps it across saves. */
public class PrismNodeEntity extends BlockEntity {
	public static final int MAX_CHARGE = 64;

	private int charge;
	private int ticks;

	public PrismNodeEntity(BlockPos pos, BlockState state) {
		super(PrismBlockEntities.PRISM_NODE, pos, state);
	}

	public int charge() {
		return this.charge;
	}

	public void addCharge(int amount) {
		this.charge = Math.min(MAX_CHARGE, this.charge + amount);
		this.setChanged();
	}

	public static void tick(Level level, BlockPos pos, BlockState state, PrismNodeEntity node) {
		if (level.isClientSide()) {
			return;
		}
		node.ticks++;
		if (node.ticks >= 20) {
			node.ticks = 0;
			node.addCharge(level.getMaxLocalRawBrightness(pos) > 7 ? 2 : 1);
		}
	}

	@Override
	public void setChanged() {
		super.setChanged();
		if (this.level == null) {
			return;
		}
		BlockState state = this.getBlockState();
		this.level.sendBlockUpdated(this.worldPosition, state, state, Block.UPDATE_ALL);
	}

	@Override
	public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
		// Without this a dedicated server never sends charge to clients: block state updates carry no
		// block entity data. Multiplayer caught it; singleplayer hid it because the server and client
		// share one object.
		return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
		// 26.3's default update tag is empty, so without this a client never sees charge.
		return this.saveCustomOnly(registries);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		output.putInt("charge", this.charge);
		super.saveAdditional(output);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.charge = input.getIntOr("charge", 0);

	}
}





