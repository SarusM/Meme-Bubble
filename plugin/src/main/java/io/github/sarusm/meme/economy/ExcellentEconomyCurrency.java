package io.github.sarusm.meme.economy;

import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import io.github.sarusm.meme.api.EconomyProvider;

import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;

/**
 * One specific ExcellentEconomy currency ({@code economy: excellenteconomy} + {@code economy-currency})
 * — independent of which currency ExcellentEconomy exposes to Vault. The caller must check the
 * ExcellentEconomy plugin is present before class-loading this (compileOnly dependency).
 */
public final class ExcellentEconomyCurrency implements EconomyProvider {
    private final ExcellentEconomyAPI api;
    private final String currencyId;

    private ExcellentEconomyCurrency(ExcellentEconomyAPI api, String currencyId) {
        this.api = api;
        this.currencyId = currencyId;
    }

    /** Null (with the reason logged) when the API service or the currency id is missing. */
    public static ExcellentEconomyCurrency tryCreate(Server server, String currencyId, Logger log) {
        RegisteredServiceProvider<ExcellentEconomyAPI> registration =
                server.getServicesManager().getRegistration(ExcellentEconomyAPI.class);
        if (registration == null) {
            log.warning("ExcellentEconomy is present but its API service is not registered - purchases disabled.");
            return null;
        }
        ExcellentEconomyAPI api = registration.getProvider();
        if (api.currencyById(currencyId).isEmpty()) {
            log.warning("ExcellentEconomy has no currency '" + currencyId + "' (available: "
                    + api.getCurrencies().stream().map(ExcellentCurrency::getId).collect(Collectors.joining(", "))
                    + ") - purchases disabled.");
            return null;
        }
        return new ExcellentEconomyCurrency(api, currencyId);
    }

    /** Re-resolved every call so an ExcellentEconomy reload swapping currency objects can't leave us stale. */
    private Optional<ExcellentCurrency> currency() {
        return api.currencyById(currencyId);
    }

    @Override
    public String currencyName() {
        return currency().map(ExcellentCurrency::getName).orElse(currencyId);
    }

    @Override
    public String format(double amount) {
        return currency().map(c -> c.format(amount)).orElseGet(() -> {
            String num = amount == Math.floor(amount)
                    ? String.valueOf((long) amount)
                    : String.format(Locale.ROOT, "%.2f", amount);
            return num + " " + currencyId;
        });
    }

    /** ExcellentEconomy's synchronous API is online-only; purchases always run for online players. */
    @Override
    public double getBalance(OfflinePlayer player) {
        Player online = player.getPlayer();
        return online == null ? 0.0 : api.getBalance(online, currencyId);
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        Player online = player.getPlayer();
        return online != null && api.withdraw(online, currencyId, amount);
    }
}
