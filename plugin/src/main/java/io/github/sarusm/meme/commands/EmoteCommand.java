package io.github.sarusm.meme.commands;

import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import io.github.sarusm.meme.MemePlugin;
import io.github.sarusm.meme.ServerEmote;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** {@code /meme <id>} — play an emote on yourself; {@code /meme stop} — clear it. */
public final class EmoteCommand implements TabExecutor {
    private final MemePlugin plugin;

    public EmoteCommand(MemePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Только для игроков.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text("[Meme] /meme <id> — показать эмоцию, /meme stop — убрать. "
                    + "Панель эмоций: клавиша G (нужен мод Meme).", NamedTextColor.YELLOW));
            return true;
        }
        if ("stop".equalsIgnoreCase(args[0])) {
            plugin.service().handleStop(player);
            return true;
        }
        plugin.service().handlePlay(player, args[0]);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new java.util.ArrayList<>();
        out.add("stop");
        for (ServerEmote emote : plugin.registry().all()) {
            if (emote.id.startsWith(prefix)) {
                out.add(emote.id);
            }
        }
        return out;
    }
}
