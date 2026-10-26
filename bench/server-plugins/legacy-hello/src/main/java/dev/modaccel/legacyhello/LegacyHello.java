package dev.modaccel.legacyhello;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class LegacyHello extends JavaPlugin {
    @Override
    public void onEnable() {
        getLogger().info("LegacyHello enabled on " + getServer().getBukkitVersion());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage("Hello, " + sender.getName() + ", from LegacyHello");
        return true;
    }
}
