import squeek.appleskin.helpers.FoodHelper;
import squeek.appleskin.helpers.ConsumableFood;
import squeek.appleskin.helpers.HungerHelper;
var player = mc.player;
var hunger = player.getFoodData();
if (player.getHealth() != 2.0F || hunger.getFoodLevel() != 17 || hunger.getSaturationLevel() != 0.0F)
    throw new IllegalStateException("The client has not seen the set-up yet: health " + player.getHealth() + ", food " + hunger.getFoodLevel() + ", saturation " + hunger.getSaturationLevel() + ".");
var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_CARROT);
var result = FoodHelper.query(stack, player);
float estimate = FoodHelper.getEstimatedHealthIncrement(player, new ConsumableFood(result.modifiedFoodProperties, result.consumable));
vars.put("estimate", estimate);
var server = mc.getSingleplayerServer();
var uuid = player.getUUID();
var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
server.execute(() -> {
    var sp = server.getPlayerList().getPlayer(uuid);
    vars.put("before", sp.getHealth());
    sp.getFoodData().eat(food);
});
return "AppleSkin estimates +" + estimate + " health for a golden carrot (nutrition " + result.modifiedFoodProperties.nutrition()
    + ", saturation " + result.modifiedFoodProperties.saturation() + ", AppleSkin max exhaustion " + HungerHelper.getMaxExhaustion(player) + "); the server player ate one";
