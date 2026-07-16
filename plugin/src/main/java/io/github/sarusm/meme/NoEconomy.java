package io.github.sarusm.meme;

import java.util.Locale;

import org.bukkit.OfflinePlayer;

import io.github.sarusm.meme.api.EconomyProvider;

/**
 * Fallback economy when neither Vault nor a custom {@code EconomyProvider} is installed: prices display
 * (using {@code currency-name} from config.yml) but every withdrawal fails, so priced emotes stay
 * grant/permission-only.
 */
final class NoEconomy implements EconomyProvider {
    private final String currencyName;

    NoEconomy(String currencyName) {
        this.currencyName = currencyName;
    }

    @Override
    public String currencyName() {
        return currencyName;
    }

    @Override
    public String format(double amount) {
        String num = amount == Math.floor(amount)
                ? String.valueOf((long) amount)
                : String.format(Locale.ROOT, "%.2f", amount);
        return num + " " + currencyName;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return 0.0;
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        return false;
    }
}
