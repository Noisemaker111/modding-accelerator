import squeek.appleskin.helpers.FoodHelper;
import squeek.appleskin.helpers.ConsumableFood;
import squeek.appleskin.helpers.ExhaustionHelper;
var player = mc.player;
var hunger = player.getHungerManager();
if (player.getHealth() != 2.0F || hunger.getFoodLevel() != 17 || hunger.getSaturationLevel() != 0.0F)
    throw new IllegalStateException("The client has not seen the set-up yet: health " + player.getHealth() + ", food " + hunger.getFoodLevel() + ", saturation " + hunger.getSaturationLevel() + ".");
var stack = new net.minecraft.item.ItemStack(net.minecraft.item.Items.GOLDEN_CARROT);
var result = FoodHelper.query(stack, player);
float estimate = FoodHelper.getEstimatedHealthIncrement(player, new ConsumableFood(result.modifiedFoodComponent, result.consumableComponent));
vars.put("estimate", estimate);
var server = mc.getServer();
var uuid = player.getUuid();
var food = stack.get(net.minecraft.component.DataComponentTypes.FOOD);
server.execute(() -> {
    var sp = server.getPlayerManager().getPlayer(uuid);
    vars.put("before", sp.getHealth());
    sp.getHungerManager().eat(food);
});
return "AppleSkin estimates +" + estimate + " health for a golden carrot (nutrition " + result.modifiedFoodComponent.nutrition()
    + ", saturation " + result.modifiedFoodComponent.saturation() + ", AppleSkin exhaustion " + ExhaustionHelper.getExhaustion(player) + "); the server player ate one";
