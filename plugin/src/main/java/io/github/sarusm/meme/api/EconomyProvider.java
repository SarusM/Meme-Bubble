package io.github.sarusm.meme.api;

import org.bukkit.OfflinePlayer;

/**
 * The currency Meme charges emote prices in. The plugin installs the provider picked by the
 * {@code economy:} config key (Vault, a specific ExcellentEconomy currency, EssentialsX money or
 * PlayerPoints); server plugins with their own currency can replace it via
 * {@link MemeApi#setEconomyProvider} at any time.
 */
public interface EconomyProvider {
    /** Human-readable currency name ("Coins", "Gems"...). Shown to players next to prices. */
    String currencyName();

    /** Format an amount for display, e.g. {@code 150 Coins}. */
    String format(double amount);

    /** The player's current balance. */
    double getBalance(OfflinePlayer player);

    /**
     * Withdraw {@code amount} from the player.
     *
     * @return true when the money was taken (the purchase then completes), false otherwise
     */
    boolean withdraw(OfflinePlayer player, double amount);
}
