package io.github.sarusm.meme.economy;

import java.math.BigDecimal;

import org.bukkit.OfflinePlayer;

import io.github.sarusm.meme.api.EconomyProvider;
import com.earth2me.essentials.api.Economy;
import com.earth2me.essentials.api.NoLoanPermittedException;
import com.earth2me.essentials.api.UserDoesNotExistException;

import net.ess3.api.MaxMoneyException;

/**
 * EssentialsX money via its static {@link Economy} API — a direct hook for servers where Vault points at
 * a different economy plugin. The caller must check the Essentials plugin is present before class-loading
 * this (compileOnly dependency).
 */
public final class EssentialsEconomy implements EconomyProvider {
    @Override
    public String currencyName() {
        return "Money";
    }

    @Override
    public String format(double amount) {
        return Economy.format(BigDecimal.valueOf(amount));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        try {
            return Economy.getMoneyExact(player.getUniqueId()).doubleValue();
        } catch (UserDoesNotExistException e) {
            return 0.0;
        }
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        BigDecimal value = BigDecimal.valueOf(amount);
        try {
            if (!Economy.hasEnough(player.getUniqueId(), value)) {
                return false;
            }
            Economy.subtract(player.getUniqueId(), value);
            return true;
        } catch (UserDoesNotExistException | NoLoanPermittedException | MaxMoneyException
                | ArithmeticException e) {
            return false;
        }
    }
}
