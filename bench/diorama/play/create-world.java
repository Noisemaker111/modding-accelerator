import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.*;
import net.minecraft.core.registries.Registries;
// Creates the experiment's starting save "diorama-play": default terrain, fixed seed, creative,
// frozen time and weather, no natural mob spawning (the flight stays the same every launch).
GameRules rules = new GameRules();
rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
LevelSettings settings = new LevelSettings("diorama-play", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
mc.createWorldOpenFlows().createFreshLevel("diorama-play", settings, new WorldOptions(20261114L, true, false),
    registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), null);
return "creating";
