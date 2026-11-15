import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.*;
import net.minecraft.core.registries.Registries;
// Creates the experiment's starting save "siege": superflat, fixed seed, no natural mob spawning.
GameRules rules = new GameRules();
rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
LevelSettings settings = new LevelSettings("siege", GameType.SURVIVAL, false, Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
mc.createWorldOpenFlows().createFreshLevel("siege", settings, new WorldOptions(20261114L, false, false),
    registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
return "creating";
