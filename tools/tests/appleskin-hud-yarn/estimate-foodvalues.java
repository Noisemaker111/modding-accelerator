import squeek.appleskin.helpers.FoodHelper;
// The same estimate as estimate.java, through AppleSkin's older API (2.5.x, from its 1.20.1 code) as a port of that code
// keeps it: FoodValues instead of ConsumableFood. Its exhaustion is read through the accessor mixin the port wrote.
var player = mc.player;
var hunger = player.getHungerManager();
if (player.getHealth() != 2.0F || hunger.getFoodLevel() != 17 || hunger.getSaturationLevel() != 0.0F)
    throw new IllegalStateException("The client has not seen the set-up yet: health " + player.getHealth() + ", food " + hunger.getFoodLevel() + ", saturation " + hunger.getSaturationLevel() + ".");
var stack = new net.minecraft.item.ItemStack(net.minecraft.item.Items.GOLDEN_CARROT);
var values = FoodHelper.getModifiedFoodValues(stack, player);
float estimate = FoodHelper.getEstimatedHealthIncrement(stack, values, player);
vars.put("estimate", estimate);
var server = mc.getServer();
var uuid = player.getUuid();
var food = stack.get(net.minecraft.component.DataComponentTypes.FOOD);
server.execute(() -> {
    var sp = server.getPlayerManager().getPlayer(uuid);
    vars.put("before", sp.getHealth());
    sp.getHungerManager().eat(food);
});
return "AppleSkin estimates +" + estimate + " health for a golden carrot (hunger " + values.hunger + ", saturation modifier " + values.saturationModifier
    + ", AppleSkin exhaustion " + ((squeek.appleskin.mixin.HungerManagerAccessor) hunger).modaccel$getExhaustion() + "); the server player ate one";
