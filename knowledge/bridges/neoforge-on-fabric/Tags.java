package ${package};

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.material.Fluid;

/**
 * NeoForge's net.neoforged.neoforge.common.Tags (NeoForge 26.3.0.103): every TagKey constant with the tag id
 * NeoForge makes it from, for a converted mod on Fabric. Written by python bench/bridge_tags.py from NeoForge's
 * published sources; the tags' entries come from data packs (Fabric API's convention tags fill the c: ones).
 */
public class Tags {

    public static class Blocks {
        public static final TagKey<Block> ENDERMAN_PLACE_ON_BLACKLIST = tag("neoforge:enderman_place_on_blacklist");
        public static final TagKey<Block> NEEDS_WOOD_TOOL = tag("neoforge:needs_wood_tool");
        public static final TagKey<Block> NEEDS_GOLD_TOOL = tag("neoforge:needs_gold_tool");
        public static final TagKey<Block> NEEDS_NETHERITE_TOOL = tag("neoforge:needs_netherite_tool");
        public static final TagKey<Block> HIDDEN_FROM_RECIPE_VIEWERS = tag("c:hidden_from_recipe_viewers");
        public static final TagKey<Block> RELOCATION_NOT_SUPPORTED = tag("c:relocation_not_supported");
        public static final TagKey<Block> SKULLS = tag("c:skulls");
        public static final TagKey<Block> VILLAGER_JOB_SITES = tag("c:villager_job_sites");
        public static final TagKey<Block> VILLAGER_FARMLANDS = tag("neoforge:villager_farmlands");
        @Deprecated
        public static final TagKey<Block> BARRELS = tag("c:barrels");
        @Deprecated
        public static final TagKey<Block> BARRELS_WOODEN = tag("c:barrels/wooden");
        @Deprecated
        public static final TagKey<Block> BARS = tag("c:bars");
        @Deprecated
        public static final TagKey<Block> BARS_COPPER = tag("c:bars/copper");
        @Deprecated
        public static final TagKey<Block> BARS_IRON = tag("c:bars/iron");
        @Deprecated
        public static final TagKey<Block> BOOKSHELVES = tag("c:bookshelves");
        @Deprecated
        public static final TagKey<Block> BUDDING_BLOCKS = tag("c:budding_blocks");
        @Deprecated
        public static final TagKey<Block> BUDS = tag("c:buds");
        @Deprecated
        public static final TagKey<Block> CHAINS = tag("c:chains");
        @Deprecated
        public static final TagKey<Block> CHESTS = tag("c:chests");
        @Deprecated
        public static final TagKey<Block> CHESTS_ENDER = tag("c:chests/ender");
        @Deprecated
        public static final TagKey<Block> CHESTS_TRAPPED = tag("c:chests/trapped");
        @Deprecated
        public static final TagKey<Block> CHESTS_WOODEN = tag("c:chests/wooden");
        @Deprecated
        public static final TagKey<Block> CLUSTERS = tag("c:clusters");
        @Deprecated
        public static final TagKey<Block> COBBLESTONES = tag("c:cobblestones");
        @Deprecated
        public static final TagKey<Block> COBBLESTONES_NORMAL = tag("c:cobblestones/normal");
        @Deprecated
        public static final TagKey<Block> COBBLESTONES_INFESTED = tag("c:cobblestones/infested");
        @Deprecated
        public static final TagKey<Block> COBBLESTONES_MOSSY = tag("c:cobblestones/mossy");
        @Deprecated
        public static final TagKey<Block> COBBLESTONES_DEEPSLATE = tag("c:cobblestones/deepslate");
        @Deprecated
        public static final TagKey<Block> CONCRETES = tag("c:concretes");
        @Deprecated
        public static final TagKey<Block> DYED = tag("c:dyed");
        @Deprecated
        public static final TagKey<Block> DYED_BLACK = tag("c:dyed/black");
        @Deprecated
        public static final TagKey<Block> DYED_BLUE = tag("c:dyed/blue");
        @Deprecated
        public static final TagKey<Block> DYED_BROWN = tag("c:dyed/brown");
        @Deprecated
        public static final TagKey<Block> DYED_CYAN = tag("c:dyed/cyan");
        @Deprecated
        public static final TagKey<Block> DYED_GRAY = tag("c:dyed/gray");
        @Deprecated
        public static final TagKey<Block> DYED_GREEN = tag("c:dyed/green");
        @Deprecated
        public static final TagKey<Block> DYED_LIGHT_BLUE = tag("c:dyed/light_blue");
        @Deprecated
        public static final TagKey<Block> DYED_LIGHT_GRAY = tag("c:dyed/light_gray");
        @Deprecated
        public static final TagKey<Block> DYED_LIME = tag("c:dyed/lime");
        @Deprecated
        public static final TagKey<Block> DYED_MAGENTA = tag("c:dyed/magenta");
        @Deprecated
        public static final TagKey<Block> DYED_ORANGE = tag("c:dyed/orange");
        @Deprecated
        public static final TagKey<Block> DYED_PINK = tag("c:dyed/pink");
        @Deprecated
        public static final TagKey<Block> DYED_PURPLE = tag("c:dyed/purple");
        @Deprecated
        public static final TagKey<Block> DYED_RED = tag("c:dyed/red");
        @Deprecated
        public static final TagKey<Block> DYED_WHITE = tag("c:dyed/white");
        @Deprecated
        public static final TagKey<Block> DYED_YELLOW = tag("c:dyed/yellow");
        @Deprecated
        public static final TagKey<Block> DYEABLE_UNDYED_SIMPLE = tag("c:dyeable/simple/undyed");
        @Deprecated
        public static final TagKey<Block> DYEABLE_REDYEABLE_SIMPLE = tag("c:dyeable/simple/redyeable");
        @Deprecated
        public static final TagKey<Block> DYEABLE_SIMPLE = tag("c:dyeable/simple");
        @Deprecated
        public static final TagKey<Block> DYEABLE_DYNAMIC = tag("c:dyeable/dynamic");
        @Deprecated
        public static final TagKey<Block> DYEABLE = tag("c:dyeable");
        @Deprecated
        public static final TagKey<Block> END_STONES = tag("c:end_stones");
        @Deprecated
        public static final TagKey<Block> FENCE_GATES = tag("c:fence_gates");
        @Deprecated
        public static final TagKey<Block> FENCE_GATES_WOODEN = tag("c:fence_gates/wooden");
        @Deprecated
        public static final TagKey<Block> FENCES = tag("c:fences");
        @Deprecated
        public static final TagKey<Block> FENCES_NETHER_BRICK = tag("c:fences/nether_brick");
        @Deprecated
        public static final TagKey<Block> FENCES_WOODEN = tag("c:fences/wooden");
        @Deprecated
        public static final TagKey<Block> FLOWERS_SMALL = tag("c:flowers/small");
        @Deprecated
        public static final TagKey<Block> FLOWERS_TALL = tag("c:flowers/tall");
        @Deprecated
        public static final TagKey<Block> FLOWERS = tag("c:flowers");
        @Deprecated
        public static final TagKey<Block> FROGLIGHTS = tag("c:froglights");
        @Deprecated
        public static final TagKey<Block> GLASS_BLOCKS = tag("c:glass_blocks");
        @Deprecated
        public static final TagKey<Block> GLASS_BLOCKS_COLORLESS = tag("c:glass_blocks/colorless");
        @Deprecated
        public static final TagKey<Block> GLASS_BLOCKS_CHEAP = tag("c:glass_blocks/cheap");
        @Deprecated
        public static final TagKey<Block> GLASS_BLOCKS_TINTED = tag("c:glass_blocks/tinted");
        @Deprecated
        public static final TagKey<Block> GLASS_PANES = tag("c:glass_panes");
        @Deprecated
        public static final TagKey<Block> GLASS_PANES_COLORLESS = tag("c:glass_panes/colorless");
        @Deprecated
        public static final TagKey<Block> GLAZED_TERRACOTTAS = tag("c:glazed_terracottas");
        @Deprecated
        public static final TagKey<Block> GRAVELS = tag("c:gravels");
        @Deprecated
        public static final TagKey<Block> NETHERRACKS = tag("c:netherracks");
        @Deprecated
        public static final TagKey<Block> OBSIDIANS = tag("c:obsidians");
        @Deprecated
        public static final TagKey<Block> OBSIDIANS_NORMAL = tag("c:obsidians/normal");
        @Deprecated
        public static final TagKey<Block> OBSIDIANS_CRYING = tag("c:obsidians/crying");
        @Deprecated
        public static final TagKey<Block> ORE_BEARING_GROUND_DEEPSLATE = tag("c:ore_bearing_ground/deepslate");
        @Deprecated
        public static final TagKey<Block> ORE_BEARING_GROUND_NETHERRACK = tag("c:ore_bearing_ground/netherrack");
        @Deprecated
        public static final TagKey<Block> ORE_BEARING_GROUND_STONE = tag("c:ore_bearing_ground/stone");
        @Deprecated
        public static final TagKey<Block> ORE_RATES_DENSE = tag("c:ore_rates/dense");
        @Deprecated
        public static final TagKey<Block> ORE_RATES_SINGULAR = tag("c:ore_rates/singular");
        @Deprecated
        public static final TagKey<Block> ORE_RATES_SPARSE = tag("c:ore_rates/sparse");
        @Deprecated
        public static final TagKey<Block> ORES = tag("c:ores");
        @Deprecated
        public static final TagKey<Block> ORES_COAL = tag("c:ores/coal");
        @Deprecated
        public static final TagKey<Block> ORES_COPPER = tag("c:ores/copper");
        @Deprecated
        public static final TagKey<Block> ORES_DIAMOND = tag("c:ores/diamond");
        @Deprecated
        public static final TagKey<Block> ORES_EMERALD = tag("c:ores/emerald");
        @Deprecated
        public static final TagKey<Block> ORES_GOLD = tag("c:ores/gold");
        @Deprecated
        public static final TagKey<Block> ORES_IRON = tag("c:ores/iron");
        @Deprecated
        public static final TagKey<Block> ORES_LAPIS = tag("c:ores/lapis");
        @Deprecated
        public static final TagKey<Block> ORES_NETHERITE_SCRAP = tag("c:ores/netherite_scrap");
        @Deprecated
        public static final TagKey<Block> ORES_QUARTZ = tag("c:ores/quartz");
        @Deprecated
        public static final TagKey<Block> ORES_REDSTONE = tag("c:ores/redstone");
        @Deprecated
        public static final TagKey<Block> ORES_IN_GROUND_DEEPSLATE = tag("c:ores_in_ground/deepslate");
        @Deprecated
        public static final TagKey<Block> ORES_IN_GROUND_NETHERRACK = tag("c:ores_in_ground/netherrack");
        @Deprecated
        public static final TagKey<Block> ORES_IN_GROUND_STONE = tag("c:ores_in_ground/stone");
        @Deprecated
        public static final TagKey<Block> PUMPKINS = tag("c:pumpkins");
        @Deprecated
        public static final TagKey<Block> PUMPKINS_NORMAL = tag("c:pumpkins/normal");
        @Deprecated
        public static final TagKey<Block> PUMPKINS_CARVED = tag("c:pumpkins/carved");
        @Deprecated
        public static final TagKey<Block> PUMPKINS_JACK_O_LANTERNS = tag("c:pumpkins/jack_o_lanterns");
        @Deprecated
        public static final TagKey<Block> PLAYER_WORKSTATIONS_CRAFTING_TABLES = tag("c:player_workstations/crafting_tables");
        @Deprecated
        public static final TagKey<Block> PLAYER_WORKSTATIONS_FURNACES = tag("c:player_workstations/furnaces");
        @Deprecated
        public static final TagKey<Block> ROPES = tag("c:ropes");
        @Deprecated
        public static final TagKey<Block> SANDS = tag("c:sands");
        @Deprecated
        public static final TagKey<Block> SANDS_COLORLESS = tag("c:sands/colorless");
        @Deprecated
        public static final TagKey<Block> SANDS_RED = tag("c:sands/red");
        @Deprecated
        public static final TagKey<Block> SANDSTONE_BLOCKS = tag("c:sandstone/blocks");
        @Deprecated
        public static final TagKey<Block> SANDSTONE_SLABS = tag("c:sandstone/slabs");
        @Deprecated
        public static final TagKey<Block> SANDSTONE_STAIRS = tag("c:sandstone/stairs");
        @Deprecated
        public static final TagKey<Block> SANDSTONE_RED_BLOCKS = tag("c:sandstone/red_blocks");
        @Deprecated
        public static final TagKey<Block> SANDSTONE_RED_SLABS = tag("c:sandstone/red_slabs");
        @Deprecated
        public static final TagKey<Block> SANDSTONE_RED_STAIRS = tag("c:sandstone/red_stairs");
        @Deprecated
        public static final TagKey<Block> SANDSTONE_UNCOLORED_BLOCKS = tag("c:sandstone/uncolored_blocks");
        @Deprecated
        public static final TagKey<Block> SANDSTONE_UNCOLORED_SLABS = tag("c:sandstone/uncolored_slabs");
        @Deprecated
        public static final TagKey<Block> SANDSTONE_UNCOLORED_STAIRS = tag("c:sandstone/uncolored_stairs");
        @Deprecated
        public static final TagKey<Block> STONES = tag("c:stones");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS = tag("c:storage_blocks");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_BONE_MEAL = tag("c:storage_blocks/bone_meal");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_COAL = tag("c:storage_blocks/coal");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_COPPER = tag("c:storage_blocks/copper");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_DIAMOND = tag("c:storage_blocks/diamond");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_DRIED_KELP = tag("c:storage_blocks/dried_kelp");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_EMERALD = tag("c:storage_blocks/emerald");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_GOLD = tag("c:storage_blocks/gold");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_IRON = tag("c:storage_blocks/iron");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_LAPIS = tag("c:storage_blocks/lapis");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_NETHERITE = tag("c:storage_blocks/netherite");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_RAW_COPPER = tag("c:storage_blocks/raw_copper");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_RAW_GOLD = tag("c:storage_blocks/raw_gold");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_RAW_IRON = tag("c:storage_blocks/raw_iron");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_REDSTONE = tag("c:storage_blocks/redstone");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_RESIN = tag("c:storage_blocks/resin");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_SLIME = tag("c:storage_blocks/slime");
        @Deprecated
        public static final TagKey<Block> STORAGE_BLOCKS_WHEAT = tag("c:storage_blocks/wheat");
        @Deprecated
        public static final TagKey<Block> OVERWORLD_NATURAL_LOGS = tag("c:natural_logs/overworld");
        @Deprecated
        public static final TagKey<Block> NETHER_NATURAL_LOGS = tag("c:natural_logs/nether");
        @Deprecated
        public static final TagKey<Block> NATURAL_LOGS = tag("c:natural_logs");
        @Deprecated
        public static final TagKey<Block> NATURAL_WOODS = tag("c:natural_woods");
        @Deprecated
        public static final TagKey<Block> STRIPPED_LOGS = tag("c:stripped_logs");
        @Deprecated
        public static final TagKey<Block> STRIPPED_WOODS = tag("c:stripped_woods");

        private static TagKey<Block> tag(String id) {
            return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(id.substring(0, id.indexOf(':')), id.substring(id.indexOf(':') + 1)));
        }
    }

    public static class EntityTypes {
        public static final TagKey<EntityType<?>> BOSSES = tag("c:bosses");
        public static final TagKey<EntityType<?>> MINECARTS = tag("c:minecarts");
        public static final TagKey<EntityType<?>> BOATS = tag("c:boats");
        public static final TagKey<EntityType<?>> ITEM_FRAMES = tag("c:item_frames");
        public static final TagKey<EntityType<?>> CAPTURING_NOT_SUPPORTED = tag("c:capturing_not_supported");
        public static final TagKey<EntityType<?>> TELEPORTING_NOT_SUPPORTED = tag("c:teleporting_not_supported");

        private static TagKey<EntityType<?>> tag(String id) {
            return TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(id.substring(0, id.indexOf(':')), id.substring(id.indexOf(':') + 1)));
        }
    }

    public static class Items {
        public static final TagKey<Item> ENCHANTING_FUELS = tag("neoforge:enchanting_fuels");
        public static final TagKey<Item> PIGLIN_USABLE_CROSSBOWS = tag("neoforge:piglin_usable_crossbows");
        public static final TagKey<Item> PILLAGER_USABLE_CROSSBOWS = tag("neoforge:pillager_usable_crossbows");
        public static final TagKey<Item> SKELETON_USABLE_BOWS = tag("neoforge:skeleton_usable_bows");
        public static final TagKey<Item> BONES = tag("c:bones");
        public static final TagKey<Item> BRICKS = tag("c:bricks");
        public static final TagKey<Item> BRICKS_NORMAL = tag("c:bricks/normal");
        public static final TagKey<Item> BRICKS_NETHER = tag("c:bricks/nether");
        public static final TagKey<Item> BRICKS_RESIN = tag("c:bricks/resin");
        public static final TagKey<Item> BUCKETS = tag("c:buckets");
        public static final TagKey<Item> BUCKETS_EMPTY = tag("c:buckets/empty");
        public static final TagKey<Item> BUCKETS_WATER = tag("c:buckets/water");
        public static final TagKey<Item> BUCKETS_LAVA = tag("c:buckets/lava");
        public static final TagKey<Item> BUCKETS_MILK = tag("c:buckets/milk");
        public static final TagKey<Item> BUCKETS_POWDER_SNOW = tag("c:buckets/powder_snow");
        public static final TagKey<Item> BUCKETS_ENTITY_WATER = tag("c:buckets/entity_water");
        public static final TagKey<Item> BUCKETS_ENTITY_DRY = tag("c:buckets/entity_dry");
        public static final TagKey<Item> CONCRETE_POWDERS = tag("c:concrete_powders");
        public static final TagKey<Item> CLUMPS = tag("c:clumps");
        public static final TagKey<Item> CLUMPS_RESIN = tag("c:clumps/resin");
        public static final TagKey<Item> CROPS = tag("c:crops");
        public static final TagKey<Item> CROPS_BEETROOT = tag("c:crops/beetroot");
        public static final TagKey<Item> CROPS_CACTUS = tag("c:crops/cactus");
        public static final TagKey<Item> CROPS_CARROT = tag("c:crops/carrot");
        public static final TagKey<Item> CROPS_COCOA_BEAN = tag("c:crops/cocoa_bean");
        public static final TagKey<Item> CROPS_MELON = tag("c:crops/melon");
        public static final TagKey<Item> CROPS_NETHER_WART = tag("c:crops/nether_wart");
        public static final TagKey<Item> CROPS_POTATO = tag("c:crops/potato");
        public static final TagKey<Item> CROPS_PUMPKIN = tag("c:crops/pumpkin");
        public static final TagKey<Item> CROPS_SUGAR_CANE = tag("c:crops/sugar_cane");
        public static final TagKey<Item> CROPS_WHEAT = tag("c:crops/wheat");
        public static final TagKey<Item> DRINKS = tag("c:drinks");
        public static final TagKey<Item> DRINKS_WATER = tag("c:drinks/water");
        public static final TagKey<Item> DRINKS_WATERY = tag("c:drinks/watery");
        public static final TagKey<Item> DRINKS_MILK = tag("c:drinks/milk");
        public static final TagKey<Item> DRINKS_HONEY = tag("c:drinks/honey");
        public static final TagKey<Item> DRINKS_MAGIC = tag("c:drinks/magic");
        public static final TagKey<Item> DRINKS_OMINOUS = tag("c:drinks/ominous");
        public static final TagKey<Item> DRINKS_JUICE = tag("c:drinks/juice");
        public static final TagKey<Item> DRINK_CONTAINING_BOTTLE = tag("c:drink_containing/bottle");
        public static final TagKey<Item> DRINK_CONTAINING_BUCKET = tag("c:drink_containing/bucket");
        public static final TagKey<Item> DUSTS = tag("c:dusts");
        public static final TagKey<Item> DUSTS_REDSTONE = tag("c:dusts/redstone");
        public static final TagKey<Item> DUSTS_GLOWSTONE = tag("c:dusts/glowstone");
        public static final TagKey<Item> DYES = tag("c:dyes");
        public static final TagKey<Item> DYES_BLACK = tag("c:dyes/black");
        public static final TagKey<Item> DYES_RED = tag("c:dyes/red");
        public static final TagKey<Item> DYES_GREEN = tag("c:dyes/green");
        public static final TagKey<Item> DYES_BROWN = tag("c:dyes/brown");
        public static final TagKey<Item> DYES_BLUE = tag("c:dyes/blue");
        public static final TagKey<Item> DYES_PURPLE = tag("c:dyes/purple");
        public static final TagKey<Item> DYES_CYAN = tag("c:dyes/cyan");
        public static final TagKey<Item> DYES_LIGHT_GRAY = tag("c:dyes/light_gray");
        public static final TagKey<Item> DYES_GRAY = tag("c:dyes/gray");
        public static final TagKey<Item> DYES_PINK = tag("c:dyes/pink");
        public static final TagKey<Item> DYES_LIME = tag("c:dyes/lime");
        public static final TagKey<Item> DYES_YELLOW = tag("c:dyes/yellow");
        public static final TagKey<Item> DYES_LIGHT_BLUE = tag("c:dyes/light_blue");
        public static final TagKey<Item> DYES_MAGENTA = tag("c:dyes/magenta");
        public static final TagKey<Item> DYES_ORANGE = tag("c:dyes/orange");
        public static final TagKey<Item> DYES_WHITE = tag("c:dyes/white");
        public static final TagKey<Item> EGGS = tag("c:eggs");
        public static final TagKey<Item> ENDER_PEARLS = tag("c:ender_pearls");
        public static final TagKey<Item> FEATHERS = tag("c:feathers");
        public static final TagKey<Item> FERTILIZERS = tag("c:fertilizers");
        public static final TagKey<Item> FOODS = tag("c:foods");
        public static final TagKey<Item> FOODS_FRUIT = tag("c:foods/fruit");
        public static final TagKey<Item> FOODS_VEGETABLE = tag("c:foods/vegetable");
        public static final TagKey<Item> FOODS_BERRY = tag("c:foods/berry");
        public static final TagKey<Item> FOODS_BREAD = tag("c:foods/bread");
        public static final TagKey<Item> FOODS_COOKIE = tag("c:foods/cookie");
        public static final TagKey<Item> FOODS_DOUGH = tag("c:foods/dough");
        public static final TagKey<Item> FOODS_RAW_MEAT = tag("c:foods/raw_meat");
        public static final TagKey<Item> FOODS_COOKED_MEAT = tag("c:foods/cooked_meat");
        public static final TagKey<Item> FOODS_RAW_FISH = tag("c:foods/raw_fish");
        public static final TagKey<Item> FOODS_COOKED_FISH = tag("c:foods/cooked_fish");
        public static final TagKey<Item> FOODS_SOUP = tag("c:foods/soup");
        public static final TagKey<Item> FOODS_CANDY = tag("c:foods/candy");
        public static final TagKey<Item> FOODS_PIE = tag("c:foods/pie");
        public static final TagKey<Item> FOODS_GOLDEN = tag("c:foods/golden");
        public static final TagKey<Item> FOODS_EDIBLE_WHEN_PLACED = tag("c:foods/edible_when_placed");
        public static final TagKey<Item> FOODS_FOOD_POISONING = tag("c:foods/food_poisoning");
        public static final TagKey<Item> ANIMAL_FOODS = tag("c:animal_foods");
        public static final TagKey<Item> GEMS = tag("c:gems");
        public static final TagKey<Item> GEMS_DIAMOND = tag("c:gems/diamond");
        public static final TagKey<Item> GEMS_EMERALD = tag("c:gems/emerald");
        public static final TagKey<Item> GEMS_AMETHYST = tag("c:gems/amethyst");
        public static final TagKey<Item> GEMS_LAPIS = tag("c:gems/lapis");
        public static final TagKey<Item> GEMS_PRISMARINE = tag("c:gems/prismarine");
        public static final TagKey<Item> GEMS_QUARTZ = tag("c:gems/quartz");
        public static final TagKey<Item> GUNPOWDERS = tag("c:gunpowders");
        public static final TagKey<Item> HIDDEN_FROM_RECIPE_VIEWERS = tag("c:hidden_from_recipe_viewers");
        public static final TagKey<Item> INGOTS = tag("c:ingots");
        public static final TagKey<Item> INGOTS_COPPER = tag("c:ingots/copper");
        public static final TagKey<Item> INGOTS_GOLD = tag("c:ingots/gold");
        public static final TagKey<Item> INGOTS_IRON = tag("c:ingots/iron");
        public static final TagKey<Item> INGOTS_NETHERITE = tag("c:ingots/netherite");
        public static final TagKey<Item> LEATHERS = tag("c:leathers");
        public static final TagKey<Item> MUSHROOMS = tag("c:mushrooms");
        public static final TagKey<Item> MUSIC_DISCS = tag("c:music_discs");
        public static final TagKey<Item> NETHER_STARS = tag("c:nether_stars");
        public static final TagKey<Item> NUGGETS = tag("c:nuggets");
        public static final TagKey<Item> NUGGETS_GOLD = tag("c:nuggets/gold");
        public static final TagKey<Item> NUGGETS_IRON = tag("c:nuggets/iron");
        public static final TagKey<Item> NUGGETS_COPPER = tag("c:nuggets/copper");
        public static final TagKey<Item> POTIONS = tag("c:potions");
        public static final TagKey<Item> POTIONS_BOTTLE = tag("c:potions/bottle");
        public static final TagKey<Item> RAW_MATERIALS = tag("c:raw_materials");
        public static final TagKey<Item> RAW_MATERIALS_COPPER = tag("c:raw_materials/copper");
        public static final TagKey<Item> RAW_MATERIALS_GOLD = tag("c:raw_materials/gold");
        public static final TagKey<Item> RAW_MATERIALS_IRON = tag("c:raw_materials/iron");
        public static final TagKey<Item> RODS = tag("c:rods");
        public static final TagKey<Item> RODS_BLAZE = tag("c:rods/blaze");
        public static final TagKey<Item> RODS_BREEZE = tag("c:rods/breeze");
        public static final TagKey<Item> RODS_WOODEN = tag("c:rods/wooden");
        public static final TagKey<Item> SEEDS = tag("c:seeds");
        public static final TagKey<Item> SEEDS_BEETROOT = tag("c:seeds/beetroot");
        public static final TagKey<Item> SEEDS_MELON = tag("c:seeds/melon");
        public static final TagKey<Item> SEEDS_PUMPKIN = tag("c:seeds/pumpkin");
        public static final TagKey<Item> SEEDS_TORCHFLOWER = tag("c:seeds/torchflower");
        public static final TagKey<Item> SEEDS_PITCHER_PLANT = tag("c:seeds/pitcher_plant");
        public static final TagKey<Item> SEEDS_WHEAT = tag("c:seeds/wheat");
        public static final TagKey<Item> SHULKER_BOXES = tag("c:shulker_boxes");
        public static final TagKey<Item> SLIME_BALLS = tag("c:slime_balls");
        public static final TagKey<Item> STRINGS = tag("c:strings");
        public static final TagKey<Item> VILLAGER_JOB_SITES = tag("c:villager_job_sites");
        public static final TagKey<Item> TOOLS = tag("c:tools");
        public static final TagKey<Item> TOOLS_SHIELD = tag("c:tools/shield");
        public static final TagKey<Item> TOOLS_BOW = tag("c:tools/bow");
        public static final TagKey<Item> TOOLS_CROSSBOW = tag("c:tools/crossbow");
        public static final TagKey<Item> TOOLS_FISHING_ROD = tag("c:tools/fishing_rod");
        public static final TagKey<Item> TOOLS_TRIDENT = tag("c:tools/trident");
        public static final TagKey<Item> TOOLS_SHEAR = tag("c:tools/shear");
        public static final TagKey<Item> TOOLS_BRUSH = tag("c:tools/brush");
        public static final TagKey<Item> TOOLS_IGNITER = tag("c:tools/igniter");
        public static final TagKey<Item> TOOLS_MACE = tag("c:tools/mace");
        public static final TagKey<Item> TOOLS_WRENCH = tag("c:tools/wrench");
        public static final TagKey<Item> MELEE_WEAPON_TOOLS = tag("c:tools/melee_weapon");
        public static final TagKey<Item> RANGED_WEAPON_TOOLS = tag("c:tools/ranged_weapon");
        public static final TagKey<Item> MINING_TOOL_TOOLS = tag("c:tools/mining_tool");
        public static final TagKey<Item> ARMORS = tag("c:armors");
        public static final TagKey<Item> ARMORS_HUMANOID = tag("c:armors/humanoid");
        public static final TagKey<Item> ARMORS_HORSE = tag("c:armors/horse");
        public static final TagKey<Item> ARMORS_NAUTILUS = tag("c:armors/nautilus");
        public static final TagKey<Item> ARMORS_WOLF = tag("c:armors/wolf");
        public static final TagKey<Item> ENCHANTABLES = tag("c:enchantables");
        @Deprecated
        public static final TagKey<Item> BARRELS = tag("c:barrels");
        @Deprecated
        public static final TagKey<Item> BARRELS_WOODEN = tag("c:barrels/wooden");
        @Deprecated
        public static final TagKey<Item> BARS = tag("c:bars");
        @Deprecated
        public static final TagKey<Item> BARS_COPPER = tag("c:bars/copper");
        @Deprecated
        public static final TagKey<Item> BARS_IRON = tag("c:bars/iron");
        @Deprecated
        public static final TagKey<Item> BOOKSHELVES = tag("c:bookshelves");
        @Deprecated
        public static final TagKey<Item> BUDDING_BLOCKS = tag("c:budding_blocks");
        @Deprecated
        public static final TagKey<Item> BUDS = tag("c:buds");
        @Deprecated
        public static final TagKey<Item> CHAINS = tag("c:chains");
        @Deprecated
        public static final TagKey<Item> CHESTS = tag("c:chests");
        @Deprecated
        public static final TagKey<Item> CHESTS_ENDER = tag("c:chests/ender");
        @Deprecated
        public static final TagKey<Item> CHESTS_TRAPPED = tag("c:chests/trapped");
        @Deprecated
        public static final TagKey<Item> CHESTS_WOODEN = tag("c:chests/wooden");
        @Deprecated
        public static final TagKey<Item> COBBLESTONES = tag("c:cobblestones");
        @Deprecated
        public static final TagKey<Item> COBBLESTONES_NORMAL = tag("c:cobblestones/normal");
        @Deprecated
        public static final TagKey<Item> COBBLESTONES_INFESTED = tag("c:cobblestones/infested");
        @Deprecated
        public static final TagKey<Item> COBBLESTONES_MOSSY = tag("c:cobblestones/mossy");
        @Deprecated
        public static final TagKey<Item> COBBLESTONES_DEEPSLATE = tag("c:cobblestones/deepslate");
        @Deprecated
        public static final TagKey<Item> CONCRETES = tag("c:concretes");
        @Deprecated
        public static final TagKey<Item> CLUSTERS = tag("c:clusters");
        @Deprecated
        public static final TagKey<Item> DYED = tag("c:dyed");
        @Deprecated
        public static final TagKey<Item> DYED_BLACK = tag("c:dyed/black");
        @Deprecated
        public static final TagKey<Item> DYED_BLUE = tag("c:dyed/blue");
        @Deprecated
        public static final TagKey<Item> DYED_BROWN = tag("c:dyed/brown");
        @Deprecated
        public static final TagKey<Item> DYED_CYAN = tag("c:dyed/cyan");
        @Deprecated
        public static final TagKey<Item> DYED_GRAY = tag("c:dyed/gray");
        @Deprecated
        public static final TagKey<Item> DYED_GREEN = tag("c:dyed/green");
        @Deprecated
        public static final TagKey<Item> DYED_LIGHT_BLUE = tag("c:dyed/light_blue");
        @Deprecated
        public static final TagKey<Item> DYED_LIGHT_GRAY = tag("c:dyed/light_gray");
        @Deprecated
        public static final TagKey<Item> DYED_LIME = tag("c:dyed/lime");
        @Deprecated
        public static final TagKey<Item> DYED_MAGENTA = tag("c:dyed/magenta");
        @Deprecated
        public static final TagKey<Item> DYED_ORANGE = tag("c:dyed/orange");
        @Deprecated
        public static final TagKey<Item> DYED_PINK = tag("c:dyed/pink");
        @Deprecated
        public static final TagKey<Item> DYED_PURPLE = tag("c:dyed/purple");
        @Deprecated
        public static final TagKey<Item> DYED_RED = tag("c:dyed/red");
        @Deprecated
        public static final TagKey<Item> DYED_WHITE = tag("c:dyed/white");
        @Deprecated
        public static final TagKey<Item> DYED_YELLOW = tag("c:dyed/yellow");
        @Deprecated
        public static final TagKey<Item> DYEABLE_UNDYED_SIMPLE = tag("c:dyeable/simple/undyed");
        @Deprecated
        public static final TagKey<Item> DYEABLE_REDYEABLE_SIMPLE = tag("c:dyeable/simple/redyeable");
        @Deprecated
        public static final TagKey<Item> DYEABLE_SIMPLE = tag("c:dyeable/simple");
        @Deprecated
        public static final TagKey<Item> DYEABLE_DYNAMIC = tag("c:dyeable/dynamic");
        @Deprecated
        public static final TagKey<Item> DYEABLE = tag("c:dyeable");
        @Deprecated
        public static final TagKey<Item> END_STONES = tag("c:end_stones");
        @Deprecated
        public static final TagKey<Item> FENCE_GATES = tag("c:fence_gates");
        @Deprecated
        public static final TagKey<Item> FENCE_GATES_WOODEN = tag("c:fence_gates/wooden");
        @Deprecated
        public static final TagKey<Item> FENCES = tag("c:fences");
        @Deprecated
        public static final TagKey<Item> FENCES_NETHER_BRICK = tag("c:fences/nether_brick");
        @Deprecated
        public static final TagKey<Item> FENCES_WOODEN = tag("c:fences/wooden");
        @Deprecated
        public static final TagKey<Item> FLOWERS_SMALL = tag("c:flowers/small");
        @Deprecated
        public static final TagKey<Item> FLOWERS_TALL = tag("c:flowers/tall");
        @Deprecated
        public static final TagKey<Item> FLOWERS = tag("c:flowers");
        @Deprecated
        public static final TagKey<Item> FROGLIGHTS = tag("c:froglights");
        @Deprecated
        public static final TagKey<Item> GLASS_BLOCKS = tag("c:glass_blocks");
        @Deprecated
        public static final TagKey<Item> GLASS_BLOCKS_COLORLESS = tag("c:glass_blocks/colorless");
        @Deprecated
        public static final TagKey<Item> GLASS_BLOCKS_CHEAP = tag("c:glass_blocks/cheap");
        @Deprecated
        public static final TagKey<Item> GLASS_BLOCKS_TINTED = tag("c:glass_blocks/tinted");
        @Deprecated
        public static final TagKey<Item> GLASS_PANES = tag("c:glass_panes");
        @Deprecated
        public static final TagKey<Item> GLASS_PANES_COLORLESS = tag("c:glass_panes/colorless");
        @Deprecated
        public static final TagKey<Item> GLAZED_TERRACOTTAS = tag("c:glazed_terracottas");
        @Deprecated
        public static final TagKey<Item> GRAVELS = tag("c:gravels");
        @Deprecated
        public static final TagKey<Item> NETHERRACKS = tag("c:netherracks");
        @Deprecated
        public static final TagKey<Item> OBSIDIANS = tag("c:obsidians");
        @Deprecated
        public static final TagKey<Item> OBSIDIANS_NORMAL = tag("c:obsidians/normal");
        @Deprecated
        public static final TagKey<Item> OBSIDIANS_CRYING = tag("c:obsidians/crying");
        @Deprecated
        public static final TagKey<Item> ORE_BEARING_GROUND_DEEPSLATE = tag("c:ore_bearing_ground/deepslate");
        @Deprecated
        public static final TagKey<Item> ORE_BEARING_GROUND_NETHERRACK = tag("c:ore_bearing_ground/netherrack");
        @Deprecated
        public static final TagKey<Item> ORE_BEARING_GROUND_STONE = tag("c:ore_bearing_ground/stone");
        @Deprecated
        public static final TagKey<Item> ORE_RATES_DENSE = tag("c:ore_rates/dense");
        @Deprecated
        public static final TagKey<Item> ORE_RATES_SINGULAR = tag("c:ore_rates/singular");
        @Deprecated
        public static final TagKey<Item> ORE_RATES_SPARSE = tag("c:ore_rates/sparse");
        @Deprecated
        public static final TagKey<Item> ORES = tag("c:ores");
        @Deprecated
        public static final TagKey<Item> ORES_COAL = tag("c:ores/coal");
        @Deprecated
        public static final TagKey<Item> ORES_COPPER = tag("c:ores/copper");
        @Deprecated
        public static final TagKey<Item> ORES_DIAMOND = tag("c:ores/diamond");
        @Deprecated
        public static final TagKey<Item> ORES_EMERALD = tag("c:ores/emerald");
        @Deprecated
        public static final TagKey<Item> ORES_GOLD = tag("c:ores/gold");
        @Deprecated
        public static final TagKey<Item> ORES_IRON = tag("c:ores/iron");
        @Deprecated
        public static final TagKey<Item> ORES_LAPIS = tag("c:ores/lapis");
        @Deprecated
        public static final TagKey<Item> ORES_NETHERITE_SCRAP = tag("c:ores/netherite_scrap");
        @Deprecated
        public static final TagKey<Item> ORES_QUARTZ = tag("c:ores/quartz");
        @Deprecated
        public static final TagKey<Item> ORES_REDSTONE = tag("c:ores/redstone");
        @Deprecated
        public static final TagKey<Item> ORES_IN_GROUND_DEEPSLATE = tag("c:ores_in_ground/deepslate");
        @Deprecated
        public static final TagKey<Item> ORES_IN_GROUND_NETHERRACK = tag("c:ores_in_ground/netherrack");
        @Deprecated
        public static final TagKey<Item> ORES_IN_GROUND_STONE = tag("c:ores_in_ground/stone");
        @Deprecated
        public static final TagKey<Item> PLAYER_WORKSTATIONS_CRAFTING_TABLES = tag("c:player_workstations/crafting_tables");
        @Deprecated
        public static final TagKey<Item> PLAYER_WORKSTATIONS_FURNACES = tag("c:player_workstations/furnaces");
        @Deprecated
        public static final TagKey<Item> PUMPKINS = tag("c:pumpkins");
        @Deprecated
        public static final TagKey<Item> PUMPKINS_NORMAL = tag("c:pumpkins/normal");
        @Deprecated
        public static final TagKey<Item> PUMPKINS_CARVED = tag("c:pumpkins/carved");
        @Deprecated
        public static final TagKey<Item> PUMPKINS_JACK_O_LANTERNS = tag("c:pumpkins/jack_o_lanterns");
        @Deprecated
        public static final TagKey<Item> ROPES = tag("c:ropes");
        @Deprecated
        public static final TagKey<Item> SANDS = tag("c:sands");
        @Deprecated
        public static final TagKey<Item> SANDS_COLORLESS = tag("c:sands/colorless");
        @Deprecated
        public static final TagKey<Item> SANDS_RED = tag("c:sands/red");
        @Deprecated
        public static final TagKey<Item> SANDSTONE_BLOCKS = tag("c:sandstone/blocks");
        @Deprecated
        public static final TagKey<Item> SANDSTONE_SLABS = tag("c:sandstone/slabs");
        @Deprecated
        public static final TagKey<Item> SANDSTONE_STAIRS = tag("c:sandstone/stairs");
        @Deprecated
        public static final TagKey<Item> SANDSTONE_RED_BLOCKS = tag("c:sandstone/red_blocks");
        @Deprecated
        public static final TagKey<Item> SANDSTONE_RED_SLABS = tag("c:sandstone/red_slabs");
        @Deprecated
        public static final TagKey<Item> SANDSTONE_RED_STAIRS = tag("c:sandstone/red_stairs");
        @Deprecated
        public static final TagKey<Item> SANDSTONE_UNCOLORED_BLOCKS = tag("c:sandstone/uncolored_blocks");
        @Deprecated
        public static final TagKey<Item> SANDSTONE_UNCOLORED_SLABS = tag("c:sandstone/uncolored_slabs");
        @Deprecated
        public static final TagKey<Item> SANDSTONE_UNCOLORED_STAIRS = tag("c:sandstone/uncolored_stairs");
        @Deprecated
        public static final TagKey<Item> STONES = tag("c:stones");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS = tag("c:storage_blocks");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_BONE_MEAL = tag("c:storage_blocks/bone_meal");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_COAL = tag("c:storage_blocks/coal");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_COPPER = tag("c:storage_blocks/copper");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_DIAMOND = tag("c:storage_blocks/diamond");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_DRIED_KELP = tag("c:storage_blocks/dried_kelp");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_EMERALD = tag("c:storage_blocks/emerald");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_GOLD = tag("c:storage_blocks/gold");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_IRON = tag("c:storage_blocks/iron");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_LAPIS = tag("c:storage_blocks/lapis");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_NETHERITE = tag("c:storage_blocks/netherite");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_RAW_COPPER = tag("c:storage_blocks/raw_copper");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_RAW_GOLD = tag("c:storage_blocks/raw_gold");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_RAW_IRON = tag("c:storage_blocks/raw_iron");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_REDSTONE = tag("c:storage_blocks/redstone");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_RESIN = tag("c:storage_blocks/resin");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_SLIME = tag("c:storage_blocks/slime");
        @Deprecated
        public static final TagKey<Item> STORAGE_BLOCKS_WHEAT = tag("c:storage_blocks/wheat");
        @Deprecated
        public static final TagKey<Item> OVERWORLD_NATURAL_LOGS = tag("c:natural_logs/overworld");
        @Deprecated
        public static final TagKey<Item> NETHER_NATURAL_LOGS = tag("c:natural_logs/nether");
        @Deprecated
        public static final TagKey<Item> NATURAL_LOGS = tag("c:natural_logs");
        @Deprecated
        public static final TagKey<Item> NATURAL_WOODS = tag("c:natural_woods");
        @Deprecated
        public static final TagKey<Item> STRIPPED_LOGS = tag("c:stripped_logs");
        @Deprecated
        public static final TagKey<Item> STRIPPED_WOODS = tag("c:stripped_woods");

        private static TagKey<Item> tag(String id) {
            return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(id.substring(0, id.indexOf(':')), id.substring(id.indexOf(':') + 1)));
        }
    }

    public static class Fluids {
        public static final TagKey<Fluid> WATER = tag("c:water");
        public static final TagKey<Fluid> LAVA = tag("c:lava");
        public static final TagKey<Fluid> MILK = tag("c:milk");
        public static final TagKey<Fluid> GASEOUS = tag("c:gaseous");
        public static final TagKey<Fluid> HONEY = tag("c:honey");
        public static final TagKey<Fluid> EXPERIENCE = tag("c:experience");
        public static final TagKey<Fluid> POTION = tag("c:potion");
        public static final TagKey<Fluid> SUSPICIOUS_STEW = tag("c:suspicious_stew");
        public static final TagKey<Fluid> MUSHROOM_STEW = tag("c:mushroom_stew");
        public static final TagKey<Fluid> RABBIT_STEW = tag("c:rabbit_stew");
        public static final TagKey<Fluid> BEETROOT_SOUP = tag("c:beetroot_soup");
        public static final TagKey<Fluid> HIDDEN_FROM_RECIPE_VIEWERS = tag("c:hidden_from_recipe_viewers");

        private static TagKey<Fluid> tag(String id) {
            return TagKey.create(Registries.FLUID, Identifier.fromNamespaceAndPath(id.substring(0, id.indexOf(':')), id.substring(id.indexOf(':') + 1)));
        }
    }

    public static class Enchantments {
        public static final TagKey<Enchantment> INCREASE_BLOCK_DROPS = tag("c:increase_block_drops");
        public static final TagKey<Enchantment> INCREASE_ENTITY_DROPS = tag("c:increase_entity_drops");
        public static final TagKey<Enchantment> WEAPON_DAMAGE_ENHANCEMENTS = tag("c:weapon_damage_enhancements");
        public static final TagKey<Enchantment> ENTITY_SPEED_ENHANCEMENTS = tag("c:entity_speed_enhancements");
        public static final TagKey<Enchantment> ENTITY_AUXILIARY_MOVEMENT_ENHANCEMENTS = tag("c:entity_auxiliary_movement_enhancements");
        public static final TagKey<Enchantment> ENTITY_DEFENSE_ENHANCEMENTS = tag("c:entity_defense_enhancements");
        public static final TagKey<Enchantment> HIDDEN_FROM_RECIPE_VIEWERS = tag("c:hidden_from_recipe_viewers");

        private static TagKey<Enchantment> tag(String id) {
            return TagKey.create(Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(id.substring(0, id.indexOf(':')), id.substring(id.indexOf(':') + 1)));
        }
    }

    public static class Potions {
        public static final TagKey<Potion> HIDDEN_FROM_RECIPE_VIEWERS = tag("c:hidden_from_recipe_viewers");

        private static TagKey<Potion> tag(String id) {
            return TagKey.create(Registries.POTION, Identifier.fromNamespaceAndPath(id.substring(0, id.indexOf(':')), id.substring(id.indexOf(':') + 1)));
        }
    }

    public static class Biomes {
        public static final TagKey<Biome> NO_DEFAULT_MONSTERS = tag("c:no_default_monsters");
        public static final TagKey<Biome> HIDDEN_FROM_LOCATOR_SELECTION = tag("c:hidden_from_locator_selection");
        public static final TagKey<Biome> IS_VOID = tag("c:is_void");
        public static final TagKey<Biome> IS_HOT = tag("c:is_hot");
        public static final TagKey<Biome> IS_HOT_OVERWORLD = tag("c:is_hot/overworld");
        public static final TagKey<Biome> IS_HOT_NETHER = tag("c:is_hot/nether");
        public static final TagKey<Biome> IS_HOT_END = tag("c:is_hot/end");
        public static final TagKey<Biome> IS_TEMPERATE = tag("c:is_temperate");
        public static final TagKey<Biome> IS_TEMPERATE_OVERWORLD = tag("c:is_temperate/overworld");
        public static final TagKey<Biome> IS_TEMPERATE_NETHER = tag("c:is_temperate/nether");
        public static final TagKey<Biome> IS_TEMPERATE_END = tag("c:is_temperate/end");
        public static final TagKey<Biome> IS_COLD = tag("c:is_cold");
        public static final TagKey<Biome> IS_COLD_OVERWORLD = tag("c:is_cold/overworld");
        public static final TagKey<Biome> IS_COLD_NETHER = tag("c:is_cold/nether");
        public static final TagKey<Biome> IS_COLD_END = tag("c:is_cold/end");
        public static final TagKey<Biome> IS_SPARSE_VEGETATION = tag("c:is_sparse_vegetation");
        public static final TagKey<Biome> IS_SPARSE_VEGETATION_OVERWORLD = tag("c:is_sparse_vegetation/overworld");
        public static final TagKey<Biome> IS_SPARSE_VEGETATION_NETHER = tag("c:is_sparse_vegetation/nether");
        public static final TagKey<Biome> IS_SPARSE_VEGETATION_END = tag("c:is_sparse_vegetation/end");
        public static final TagKey<Biome> IS_DENSE_VEGETATION = tag("c:is_dense_vegetation");
        public static final TagKey<Biome> IS_DENSE_VEGETATION_OVERWORLD = tag("c:is_dense_vegetation/overworld");
        public static final TagKey<Biome> IS_DENSE_VEGETATION_NETHER = tag("c:is_dense_vegetation/nether");
        public static final TagKey<Biome> IS_DENSE_VEGETATION_END = tag("c:is_dense_vegetation/end");
        public static final TagKey<Biome> IS_WET = tag("c:is_wet");
        public static final TagKey<Biome> IS_WET_OVERWORLD = tag("c:is_wet/overworld");
        public static final TagKey<Biome> IS_WET_NETHER = tag("c:is_wet/nether");
        public static final TagKey<Biome> IS_WET_END = tag("c:is_wet/end");
        public static final TagKey<Biome> IS_DRY = tag("c:is_dry");
        public static final TagKey<Biome> IS_DRY_OVERWORLD = tag("c:is_dry/overworld");
        public static final TagKey<Biome> IS_DRY_NETHER = tag("c:is_dry/nether");
        public static final TagKey<Biome> IS_DRY_END = tag("c:is_dry/end");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE = tag("c:primary_wood_type");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_OAK = tag("c:primary_wood_type/oak");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_BIRCH = tag("c:primary_wood_type/birch");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_SPRUCE = tag("c:primary_wood_type/spruce");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_JUNGLE = tag("c:primary_wood_type/jungle");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_ACACIA = tag("c:primary_wood_type/acacia");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_DARK_OAK = tag("c:primary_wood_type/dark_oak");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_POPLAR = tag("c:primary_wood_type/poplar");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_MANGROVE = tag("c:primary_wood_type/mangrove");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_CHERRY = tag("c:primary_wood_type/cherry");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_PALE_OAK = tag("c:primary_wood_type/pale_oak");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_BAMBOO = tag("c:primary_wood_type/bamboo");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_CRIMSON = tag("c:primary_wood_type/crimson");
        public static final TagKey<Biome> PRIMARY_WOOD_TYPE_WARPED = tag("c:primary_wood_type/warped");
        public static final TagKey<Biome> IS_OVERWORLD = tag("c:is_overworld");
        public static final TagKey<Biome> IS_CONIFEROUS_TREE = tag("c:is_tree/coniferous");
        public static final TagKey<Biome> IS_SAVANNA_TREE = tag("c:is_tree/savanna");
        public static final TagKey<Biome> IS_JUNGLE_TREE = tag("c:is_tree/jungle");
        public static final TagKey<Biome> IS_DECIDUOUS_TREE = tag("c:is_tree/deciduous");
        public static final TagKey<Biome> IS_MOUNTAIN = tag("c:is_mountain");
        public static final TagKey<Biome> IS_MOUNTAIN_PEAK = tag("c:is_mountain/peak");
        public static final TagKey<Biome> IS_MOUNTAIN_SLOPE = tag("c:is_mountain/slope");
        public static final TagKey<Biome> IS_PLAINS = tag("c:is_plains");
        public static final TagKey<Biome> IS_SNOWY_PLAINS = tag("c:is_snowy_plains");
        public static final TagKey<Biome> IS_FOREST = tag("c:is_forest");
        public static final TagKey<Biome> IS_BIRCH_FOREST = tag("c:is_birch_forest");
        public static final TagKey<Biome> IS_DARK_FOREST = tag("c:is_dark_forest");
        public static final TagKey<Biome> IS_FLOWER_FOREST = tag("c:is_flower_forest");
        public static final TagKey<Biome> IS_TAIGA = tag("c:is_taiga");
        public static final TagKey<Biome> IS_OLD_GROWTH = tag("c:is_old_growth");
        public static final TagKey<Biome> IS_HILL = tag("c:is_hill");
        public static final TagKey<Biome> IS_WINDSWEPT = tag("c:is_windswept");
        public static final TagKey<Biome> IS_JUNGLE = tag("c:is_jungle");
        public static final TagKey<Biome> IS_SAVANNA = tag("c:is_savanna");
        public static final TagKey<Biome> IS_SWAMP = tag("c:is_swamp");
        public static final TagKey<Biome> IS_DESERT = tag("c:is_desert");
        public static final TagKey<Biome> IS_BADLANDS = tag("c:is_badlands");
        public static final TagKey<Biome> IS_BEACH = tag("c:is_beach");
        public static final TagKey<Biome> IS_STONY_SHORES = tag("c:is_stony_shores");
        public static final TagKey<Biome> IS_MUSHROOM = tag("c:is_mushroom");
        public static final TagKey<Biome> IS_RIVER = tag("c:is_river");
        public static final TagKey<Biome> IS_OCEAN = tag("c:is_ocean");
        public static final TagKey<Biome> IS_DEEP_OCEAN = tag("c:is_deep_ocean");
        public static final TagKey<Biome> IS_SHALLOW_OCEAN = tag("c:is_shallow_ocean");
        public static final TagKey<Biome> IS_UNDERGROUND = tag("c:is_underground");
        public static final TagKey<Biome> IS_CAVE = tag("c:is_cave");
        public static final TagKey<Biome> IS_LUSH = tag("c:is_lush");
        public static final TagKey<Biome> IS_MAGICAL = tag("c:is_magical");
        public static final TagKey<Biome> IS_RARE = tag("c:is_rare");
        public static final TagKey<Biome> IS_PLATEAU = tag("c:is_plateau");
        public static final TagKey<Biome> IS_SPOOKY = tag("c:is_spooky");
        public static final TagKey<Biome> IS_WASTELAND = tag("c:is_wasteland");
        public static final TagKey<Biome> IS_DEAD = tag("c:is_dead");
        public static final TagKey<Biome> IS_FLORAL = tag("c:is_floral");
        public static final TagKey<Biome> IS_SANDY = tag("c:is_sandy");
        public static final TagKey<Biome> IS_SNOWY = tag("c:is_snowy");
        public static final TagKey<Biome> IS_ICY = tag("c:is_icy");
        public static final TagKey<Biome> IS_AQUATIC = tag("c:is_aquatic");
        public static final TagKey<Biome> IS_AQUATIC_ICY = tag("c:is_aquatic_icy");
        public static final TagKey<Biome> IS_NETHER = tag("c:is_nether");
        public static final TagKey<Biome> IS_NETHER_FOREST = tag("c:is_nether_forest");
        public static final TagKey<Biome> IS_END = tag("c:is_end");
        public static final TagKey<Biome> IS_OUTER_END_ISLAND = tag("c:is_outer_end_island");

        private static TagKey<Biome> tag(String id) {
            return TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath(id.substring(0, id.indexOf(':')), id.substring(id.indexOf(':') + 1)));
        }
    }

    public static class Structures {
        public static final TagKey<Structure> HIDDEN_FROM_DISPLAYERS = tag("c:hidden_from_displayers");
        public static final TagKey<Structure> HIDDEN_FROM_LOCATOR_SELECTION = tag("c:hidden_from_locator_selection");

        private static TagKey<Structure> tag(String id) {
            return TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath(id.substring(0, id.indexOf(':')), id.substring(id.indexOf(':') + 1)));
        }
    }

    public static class DamageTypes {
        public static final TagKey<DamageType> IS_MAGIC = tag("neoforge:is_magic");
        public static final TagKey<DamageType> IS_POISON = tag("neoforge:is_poison");
        public static final TagKey<DamageType> IS_WITHER = tag("neoforge:is_wither");
        public static final TagKey<DamageType> IS_ENVIRONMENT = tag("neoforge:is_environment");
        public static final TagKey<DamageType> IS_PHYSICAL = tag("neoforge:is_physical");
        public static final TagKey<DamageType> IS_TECHNICAL = tag("neoforge:is_technical");
        public static final TagKey<DamageType> NO_FLINCH = tag("neoforge:no_flinch");

        private static TagKey<DamageType> tag(String id) {
            return TagKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(id.substring(0, id.indexOf(':')), id.substring(id.indexOf(':') + 1)));
        }
    }
}
