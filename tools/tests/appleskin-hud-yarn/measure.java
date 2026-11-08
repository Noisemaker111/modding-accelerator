// After the server player's food fell back below 18, natural regeneration has stopped: compare what really healed
// with the estimate AppleSkin's HUD drew before the carrot was eaten (the flashing hearts).
var sp = mc.getServer().getPlayerManager().getPlayer(mc.player.getUuid());
float gained = sp.getHealth() - (Float) vars.get("before");
float estimate = (Float) vars.get("estimate");
String seen = "real natural regeneration healed +" + gained + " health; AppleSkin's HUD estimated +" + estimate;
if (gained < 1.0F) throw new AssertionError("The check itself did not work: the player healed only +" + gained + ", so nothing was compared. " + seen);
if (Math.abs(gained - estimate) > 0.01F) throw new AssertionError("AppleSkin's estimate is wrong: " + seen + " (allowed difference 0.01).");
return "PASS " + seen;
