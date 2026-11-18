// A busy animal farm beside the player: a barrier pen 32 x 32 at y 250 with 900 cows in it, made on the server thread
// with the game's own commands (the same pen and cows in every launch). Every animal standing on the ground runs Lionfish
// API's fluid-standing check in Entity.move each tick on the server. Returns whether the server has at least the 900 cows.
// The save opens after the client starts (quick play): wait until the player is in it.
long deadline = System.nanoTime() + 110_000_000_000L;
while ((mc.m_91092_() == null || mc.m_91092_().m_6846_().m_11314_().isEmpty() || mc.f_91074_ == null) && System.nanoTime() < deadline) Thread.sleep(500);
Thread.sleep(3000);
var server = mc.m_91092_();
return server.m_18691_(() -> {
    var player = server.m_6846_().m_11314_().get(0);
    int x = (int) Math.floor(player.m_20185_()) + 8, z = (int) Math.floor(player.m_20189_()) - 12, y = 250;
    var commands = server.m_129892_();
    var source = server.m_129893_().m_81325_(4).m_81324_();
    commands.m_230957_(source, "fill " + x + " " + (y - 1) + " " + z + " " + (x + 31) + " " + (y + 3) + " " + (z + 31) + " minecraft:barrier hollow");
    for (int i = 0; i < 900; i++) {
        commands.m_230957_(source, "summon minecraft:cow " + (x + 1 + (i % 30)) + " " + y + " " + (z + 1 + (i / 30)));
    }
    int cows = 0;
    for (var e : server.m_129783_().m_8583_()) if (e.m_6095_() == net.minecraft.world.entity.EntityType.f_20557_) cows++;
    return cows >= 900;
}).get();
