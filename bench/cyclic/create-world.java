import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.*;
import net.minecraft.core.registries.Registries;
// Creates the experiment's starting save "cyclic-base": superflat, fixed seed, creative, peaceful,
// no mob spawning, no daylight or weather cycle. scene.java then builds the crafter base.
GameRules rules = new GameRules();
rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
LevelSettings settings = new LevelSettings("cyclic-base", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
mc.createWorldOpenFlows().createFreshLevel("cyclic-base", settings, new WorldOptions(20261115L, false, false),
    registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
return "creating";
