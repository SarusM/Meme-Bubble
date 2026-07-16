package io.github.sarusm.meme.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import io.github.sarusm.meme.MemePlugin;
import io.github.sarusm.meme.ServerEmote;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * {@code /memes} — admin management:
 * <pre>
 * /memes reload                    re-scan the asset folders + configs, resend catalogues
 * /memes list                      all emote ids with price/access
 * /memes info &lt;emote&gt;              one emote in detail
 * /memes mute &lt;player&gt; [minutes]   block a player's emotes (no minutes = until unmute)
 * /memes unmute &lt;player&gt;
 * /memes grant &lt;player&gt; &lt;emote|*&gt;  give access without payment
 * /memes revoke &lt;player&gt; &lt;emote|*&gt; take granted AND purchased access away
 * /memes price &lt;emote&gt; &lt;amount&gt;    set the price (persisted to emotes.yml)
 * /memes play &lt;player&gt; &lt;emote&gt;     force-play on a player
 * /memes stopall                   clear every emote for everyone
 * /memes playeremotes [on|off]     allow/deny emotes from the players' OWN local packs (no arg = status)
 * </pre>
 */
public final class EmotesAdminCommand implements TabExecutor {
    private static final List<String> SUBS = List.of(
            "reload", "list", "info", "set", "mute", "unmute", "grant", "revoke", "price", "play", "stopall",
            "playeremotes");

    private final MemePlugin plugin;

    public EmotesAdminCommand(MemePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("[Meme] /memes "
                    + String.join("|", SUBS), NamedTextColor.YELLOW));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage(Component.text("[Meme] Перезагружено: "
                        + plugin.registry().all().size() + " эмоций.", NamedTextColor.GREEN));
            }
            case "list" -> {
                sender.sendMessage(Component.text("[Meme] Эмоции ("
                        + plugin.registry().all().size() + "):", NamedTextColor.YELLOW));
                for (ServerEmote e : plugin.registry().all()) {
                    sender.sendMessage(Component.text("  " + e.id
                            + (e.price > 0 ? " — " + plugin.economy().format(e.price) : " — бесплатно")
                            + (e.defaultAccess ? "" : " [по доступу]")
                            + (e.soundHash.isEmpty() ? "" : " [звук]"), NamedTextColor.GRAY));
                }
            }
            case "info" -> {
                ServerEmote e = args.length > 1 ? plugin.registry().get(args[1]) : null;
                if (e == null) {
                    sender.sendMessage(Component.text("[Meme] /memes info <emote>", NamedTextColor.RED));
                    return true;
                }
                String inPack = e.pack.isEmpty() ? "" : "из " + e.pack;
                sender.sendMessage(Component.text("[Meme] " + e.id + " (\"" + e.name + "\"): цена="
                        + (e.price > 0 ? plugin.economy().format(e.price) : "0")
                        + ", default-access=" + e.defaultAccess
                        + ", gif=" + (e.gifFile != null ? e.gifFile.getName()
                                : (e.gifHash.isEmpty() ? "-" : inPack)) + " (" + (e.gifSize / 1024) + " KB)"
                        + ", bubble=" + (e.bubbleFile != null ? e.bubbleFile.getName()
                                : (e.bubbleHash.isEmpty() ? "-" : inPack))
                        + ", sound=" + (e.soundFile != null ? e.soundFile.getName()
                                : (e.soundHash.isEmpty() ? "-" : inPack))
                        + ", scale=" + e.scale + ", loops=" + e.loops, NamedTextColor.GRAY));
            }
            case "mute" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                OfflinePlayer offline = target != null ? target
                        : (args.length > 1 ? Bukkit.getOfflinePlayerIfCached(args[1]) : null);
                if (offline == null) {
                    sender.sendMessage(Component.text("[Meme] Игрок не найден.", NamedTextColor.RED));
                    return true;
                }
                long minutes = 0;
                if (args.length > 2) {
                    try {
                        minutes = Long.parseLong(args[2]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(Component.text("[Meme] /memes mute <player> [минуты]",
                                NamedTextColor.RED));
                        return true;
                    }
                }
                plugin.api().mute(offline, minutes * 60_000L);
                sender.sendMessage(Component.text("[Meme] Эмоции игрока " + offline.getName()
                        + (minutes > 0 ? " отключены на " + minutes + " мин." : " отключены (до unmute)."),
                        NamedTextColor.GREEN));
                if (target != null) {
                    target.sendMessage(Component.text("[Meme] Твои эмоции отключены администратором"
                            + (minutes > 0 ? " на " + minutes + " мин." : "."), NamedTextColor.RED));
                }
            }
            case "unmute" -> {
                OfflinePlayer offline = args.length > 1 ? Bukkit.getOfflinePlayerIfCached(args[1]) : null;
                if (offline == null) {
                    sender.sendMessage(Component.text("[Meme] Игрок не найден.", NamedTextColor.RED));
                    return true;
                }
                plugin.api().unmute(offline);
                sender.sendMessage(Component.text("[Meme] Эмоции игрока " + offline.getName()
                        + " снова включены.", NamedTextColor.GREEN));
            }
            case "grant", "revoke" -> {
                OfflinePlayer offline = args.length > 2 ? Bukkit.getOfflinePlayerIfCached(args[1]) : null;
                if (offline == null) {
                    sender.sendMessage(Component.text("[Meme] /memes " + args[0]
                            + " <player> <emote|*>", NamedTextColor.RED));
                    return true;
                }
                String emoteId = args[2].toLowerCase(Locale.ROOT);
                if (!"*".equals(emoteId) && plugin.registry().get(emoteId) == null) {
                    sender.sendMessage(Component.text("[Meme] Нет такой эмоции: " + emoteId,
                            NamedTextColor.RED));
                    return true;
                }
                if ("grant".equalsIgnoreCase(args[0])) {
                    plugin.api().grant(offline, emoteId);
                    sender.sendMessage(Component.text("[Meme] Выдан доступ " + offline.getName()
                            + " → " + emoteId, NamedTextColor.GREEN));
                } else {
                    plugin.api().revoke(offline, emoteId);
                    sender.sendMessage(Component.text("[Meme] Отозван доступ " + offline.getName()
                            + " → " + emoteId, NamedTextColor.GREEN));
                }
            }
            case "set" -> {
                // /memes set <emote|defaults> <param> <value...> — live tuning, persisted to emotes.yml
                // and pushed to every online modded player immediately. 'defaults' writes the baseline
                // applied to every emote that doesn't override the key itself.
                if (args.length <= 3) {
                    sender.sendMessage(Component.text("[Meme] /memes set <emote|defaults> <параметр> "
                            + "<значение>. Параметры: " + String.join(", ",
                                    io.github.sarusm.meme.EmoteRegistry.TUNABLE_PARAMS),
                            NamedTextColor.RED));
                    return true;
                }
                String value = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                if ("defaults".equalsIgnoreCase(args[1])) {
                    String error = plugin.registry().setDefault(args[2], value);
                    if (error != null) {
                        sender.sendMessage(Component.text("[Meme] " + error, NamedTextColor.RED));
                        return true;
                    }
                    plugin.reloadAll(); // defaults are baked in at load time -> re-layer + resend lists
                    sender.sendMessage(Component.text("[Meme] defaults." + args[2] + " = " + value
                            + " (применено ко всем эмоциям без своего значения)", NamedTextColor.GREEN));
                    return true;
                }
                ServerEmote e = plugin.registry().get(args[1]);
                if (e == null) {
                    sender.sendMessage(Component.text("[Meme] Нет такой эмоции: " + args[1],
                            NamedTextColor.RED));
                    return true;
                }
                String error = plugin.registry().setValue(e, args[2], value);
                if (error != null) {
                    sender.sendMessage(Component.text("[Meme] " + error, NamedTextColor.RED));
                    return true;
                }
                plugin.service().broadcastEntry(e);
                sender.sendMessage(Component.text("[Meme] " + e.id + "." + args[2] + " = " + value,
                        NamedTextColor.GREEN));
            }
            case "price" -> {
                ServerEmote e = args.length > 2 ? plugin.registry().get(args[1]) : null;
                if (e == null) {
                    sender.sendMessage(Component.text("[Meme] /memes price <emote> <сумма>",
                            NamedTextColor.RED));
                    return true;
                }
                double price;
                try {
                    price = Double.parseDouble(args[2]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("[Meme] Неверная сумма.", NamedTextColor.RED));
                    return true;
                }
                plugin.api().setPrice(e.id, price);
                sender.sendMessage(Component.text("[Meme] Цена " + e.id + " = "
                        + (price > 0 ? plugin.economy().format(price) : "бесплатно"), NamedTextColor.GREEN));
            }
            case "play" -> {
                Player target = args.length > 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    sender.sendMessage(Component.text("[Meme] /memes play <player> <emote>",
                            NamedTextColor.RED));
                    return true;
                }
                plugin.api().play(target, args[2]);
            }
            case "stopall" -> {
                plugin.service().stopAll();
                sender.sendMessage(Component.text("[Meme] Все эмоции остановлены.", NamedTextColor.GREEN));
            }
            case "playeremotes" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("[Meme] Свои эмоции игроков: "
                            + (plugin.service().playerEmotesAllowed() ? "разрешены" : "запрещены")
                            + ". /memes playeremotes on|off", NamedTextColor.YELLOW));
                    return true;
                }
                Boolean allowed = switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "on", "true", "вкл" -> true;
                    case "off", "false", "выкл" -> false;
                    default -> null;
                };
                if (allowed == null) {
                    sender.sendMessage(Component.text("[Meme] /memes playeremotes on|off",
                            NamedTextColor.RED));
                    return true;
                }
                plugin.service().setPlayerEmotesAllowed(allowed);
                sender.sendMessage(Component.text("[Meme] Свои эмоции игроков теперь "
                        + (allowed ? "РАЗРЕШЕНЫ — игроки могут показывать эмоции из своих наборов "
                                + "(meme/memes на клиенте); увидят их те, у кого есть такие же файлы."
                                : "ЗАПРЕЩЕНЫ (серверные эмоции работают как обычно)."),
                        NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("[Meme] /memes "
                    + String.join("|", SUBS), NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String s : SUBS) {
                if (s.startsWith(p)) {
                    out.add(s);
                }
            }
            return out;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "set" -> {
                    if ("defaults".startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        out.add("defaults");
                    }
                    addEmoteIds(out, args[1]);
                }
                case "info", "price" -> addEmoteIds(out, args[1]);
                case "mute", "unmute", "grant", "revoke", "play" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                            out.add(p.getName());
                        }
                    }
                }
                case "playeremotes" -> {
                    for (String s : List.of("on", "off")) {
                        if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                            out.add(s);
                        }
                    }
                }
                default -> {
                }
            }
        } else if (args.length == 3 && (sub.equals("grant") || sub.equals("revoke") || sub.equals("play"))) {
            if ("*".startsWith(args[2]) && !sub.equals("play")) {
                out.add("*");
            }
            addEmoteIds(out, args[2]);
        } else if (args.length == 3 && sub.equals("set")) {
            String p = args[2].toLowerCase(Locale.ROOT);
            for (String param : io.github.sarusm.meme.EmoteRegistry.TUNABLE_PARAMS) {
                if (param.startsWith(p)) {
                    out.add(param);
                }
            }
        }
        return out;
    }

    private void addEmoteIds(List<String> out, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        for (ServerEmote e : plugin.registry().all()) {
            if (e.id.startsWith(p)) {
                out.add(e.id);
            }
        }
    }
}
