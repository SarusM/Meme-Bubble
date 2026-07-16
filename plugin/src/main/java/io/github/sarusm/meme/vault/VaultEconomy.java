package io.github.sarusm.meme.vault;

import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

import io.github.sarusm.meme.api.EconomyProvider;

import net.milkbowl.vault.economy.Economy;

/**
 * Vault-backed {@link EconomyProvider}. Only ever loaded when the Vault plugin is present (this class
 * references Vault API types, so the caller must class-load it conditionally).
 */
public final class VaultEconomy implements EconomyProvider {
    private final Economy economy;

    private VaultEconomy(Economy economy) {
        this.economy = economy;
    }

    /** Null when Vault has no registered economy (e.g. no economy plugin installed). */
    public static VaultEconomy tryCreate(Server server) {
        RegisteredServiceProvider<Economy> registration =
                server.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : new VaultEconomy(registration.getProvider());
    }

    @Override
    public String currencyName() {
        String name = economy.currencyNamePlural();
        return name == null || name.isBlank() ? economy.getName() : name;
    }

    @Override
    public String format(double amount) {
        return economy.format(amount);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return economy.getBalance(player);
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }
}
