package ${package};

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

/**
 * NeoForge's net.neoforged.neoforge.common.util.FakePlayer: a server player with no client, for machines that act as a
 * player. It is a Fabric API FakePlayer (net.fabricmc.fabric.api.entity.FakePlayer), so Fabric mods recognise it too;
 * Fabric's gives it a packet listener that drops everything and makes it skip ticks, stats, sleep, riding, menus and
 * damage, and this adds NeoForge's overrides: no chat, no death, no harming players. Differences: NeoForge's
 * FakePlayerAdvancements and openMenu with extra data (NeoForge's own overload) have no Fabric counterpart; isFakePlayer
 * is a method of this class (NeoForge adds it to every Player).
 */
public class FakePlayer extends net.fabricmc.fabric.api.entity.FakePlayer {

    public FakePlayer(ServerLevel level, GameProfile name) {
        super(level, name);
    }

    @Override
    public void sendSystemMessage(Component chatComponent, boolean actionBar) {}

    @Override
    public boolean canHarmPlayer(Player player) {
        return false;
    }

    @Override
    public void die(DamageSource source) {}

    public boolean isFakePlayer() {
        return true;
    }
}
