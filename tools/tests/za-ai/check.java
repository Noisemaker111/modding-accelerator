// Zombie Apocalypse Addon's own effects on the summoned zombie, taken from its source:
//   ZombieHunt.injectGoals adds ZombieEatFoodGoal (goal priority 1), ZombieMountZombieHorseGoal (4) and ZombieHuntGoal (target priority 3);
//   DifficultyManager.applyScaling tags it zombieapocalypseaddon_scaled;
//   ZombieHuntGoal makes it target animals once /zhunt turned hunting on: a vanilla zombie never targets a pig.
var server = mc.getSingleplayerServer();
return server.submit(() -> {
    var level = server.overworld();
    net.minecraft.world.entity.monster.Zombie zombie = null;
    net.minecraft.world.entity.Entity pig = null;
    for (var e : level.getAllEntities()) {
        if (e.getTags().contains("modaccel_za") && e instanceof net.minecraft.world.entity.monster.Zombie z) zombie = z;
        if (e.getTags().contains("modaccel_pig")) pig = e;
    }
    if (zombie == null || pig == null) throw new AssertionError("The summoned zombie or pig is gone (zombie " + zombie + ", pig " + pig + ").");
    java.util.List<String> goals = new java.util.ArrayList<>(), targets = new java.util.ArrayList<>();
    for (var g : zombie.goalSelector.getAvailableGoals()) goals.add(g.getPriority() + ":" + g.getGoal().getClass().getSimpleName());
    for (var g : zombie.targetSelector.getAvailableGoals()) targets.add(g.getPriority() + ":" + g.getGoal().getClass().getSimpleName());
    String seen = "hunting " + com.rique.zombieapocalypse.Config.COMMON.enableAnimalHunting.get() + ", berserker " + com.rique.zombieapocalypse.Config.COMMON.berserkerHunting.get()
            + ", pig health " + ((net.minecraft.world.entity.LivingEntity) pig).getHealth() + ", goals " + goals + ", target goals " + targets + ", tags " + zombie.getTags() + ", target " + (zombie.getTarget() == null ? "none" : zombie.getTarget().getType().toShortString());
    java.util.List<String> missing = new java.util.ArrayList<>();
    if (!goals.contains("1:ZombieEatFoodGoal")) missing.add("goal 1:ZombieEatFoodGoal");
    if (!goals.contains("4:ZombieMountZombieHorseGoal")) missing.add("goal 4:ZombieMountZombieHorseGoal");
    if (!targets.contains("3:ZombieHuntGoal")) missing.add("target goal 3:ZombieHuntGoal");
    if (!zombie.getTags().contains("zombieapocalypseaddon_scaled")) missing.add("tag zombieapocalypseaddon_scaled");
    if (zombie.getTarget() != pig) missing.add("the zombie targeting the pig");
    if (!missing.isEmpty()) throw new AssertionError("Zombie Apocalypse Addon's effects are missing: " + String.join(", ", missing) + ". Saw " + seen + ".");
    return "PASS " + seen;
}).join();
