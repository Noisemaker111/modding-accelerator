// Behaviour gate: after the flight, every chunk snapshot the map holds that has blended tints has
// exactly the tints a fresh blend of that snapshot gives, however the map came by them.
long until = System.currentTimeMillis() + 60_000;
while (!Boolean.TRUE.equals(vars.get("fly.done")) && vars.containsKey("fly.thread") && System.currentTimeMillis() < until) Thread.sleep(200);
var session = dev.diorama.MapSession.get();
if (session == null) return "no session";
int checked = 0;
int differ = 0;
for (var snapshot : session.map().chunksNow().values()) {
    var tints = snapshot.tints();
    if (tints == null) continue;
    var fresh = dev.diorama.bake.BiomeTints.blend(snapshot);
    checked++;
    if (!Arrays.equals(tints.grass(), fresh.grass()) || !Arrays.equals(tints.foliage(), fresh.foliage())
        || !Arrays.equals(tints.dryFoliage(), fresh.dryFoliage()) || !Arrays.equals(tints.water(), fresh.water())) differ++;
}
return differ == 0 && checked >= 100 ? "true" : "checked " + checked + ", differ " + differ;
