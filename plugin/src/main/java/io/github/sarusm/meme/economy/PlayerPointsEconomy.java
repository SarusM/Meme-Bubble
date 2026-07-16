package io.github.sarusm.meme.economy;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.OfflinePlayer;

import io.github.sarusm.meme.api.EconomyProvider;

/**
 * PlayerPoints points. Points are integers, so fractional emote prices are charged rounded UP. The caller
 * must check the PlayerPoints plugin is present before class-loading this (compileOnly dependency).
 */
public final class PlayerPointsEconomy implements EconomyProvider {
    private final PlayerPointsAPI api;

    private PlayerPointsEconomy(PlayerPointsAPI api) {
        this.api = api;
    }

    /** Null when PlayerPoints has not initialised yet. */
    public static PlayerPointsEconomy tryCreate() {
        PlayerPoints plugin = PlayerPoints.getInstance();
        return plugin == null ? null : new PlayerPointsEconomy(plugin.getAPI());
    }

    private static int points(double amount) {
        return (int) Math.ceil(amount);
    }

    @Override
    public String currencyName() {
        return api.getCurrencyNamePlural();
    }

    @Override
    public String format(double amount) {
        int points = points(amount);
        return points + " " + api.getCurrencyName(points);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return api.look(player.getUniqueId());
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        return api.take(player.getUniqueId(), points(amount));
    }
}
