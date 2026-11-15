// Behaviour assertion on the live siege after both captures: every zombie still inside the pen,
// nearly all hunting the player, and the siege AI produced at least one zombie tower or edit.
var level = mc.getSingleplayerServer().overworld();
return mc.getSingleplayerServer().submit(() -> {
    var zs = level.getEntities(net.minecraft.world.entity.EntityType.ZOMBIE, z -> true);
    int inside = 0, targeted = 0, riding = 0;
    for (var z : zs) {
        if (Math.abs(z.getX()) < 21 && Math.abs(z.getZ()) < 21) inside++;
        if (z.getTarget() != null) targeted++;
        if (z.isPassenger() || z.isVehicle()) riding++;
    }
    boolean pillarChanged = !level.getBlockState(new net.minecraft.core.BlockPos(0, -57, 0)).is(net.minecraft.world.level.block.Blocks.DIRT);
    boolean playerAlive = level.getServer().getPlayerList().getPlayers().stream().allMatch(p -> p.isAlive());
    boolean ok = playerAlive && zs.size() >= 200 && inside == zs.size() && targeted * 10 >= zs.size() * 8 && (riding > 0 || pillarChanged);
    return ok ? "true" : "false playerAlive=" + playerAlive + " zombies=" + zs.size() + " inside=" + inside + " targeted=" + targeted + " riding=" + riding + " pillarChanged=" + pillarChanged;
}).join();
