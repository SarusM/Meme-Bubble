package io.github.sarusm.meme;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

import io.github.sarusm.meme.api.MemeApi;
import io.github.sarusm.meme.api.EconomyProvider;
import io.github.sarusm.meme.commands.EmoteCommand;
import io.github.sarusm.meme.commands.EmotesAdminCommand;
import io.github.sarusm.meme.economy.EssentialsEconomy;
import io.github.sarusm.meme.economy.ExcellentEconomyCurrency;
import io.github.sarusm.meme.economy.PlayerPointsEconomy;
import io.github.sarusm.meme.net.EmoteChannel;
import io.github.sarusm.meme.vault.VaultEconomy;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Meme — server-driven GIF emote speech bubbles (with optional sound) for players running the
 * Meme Fabric client mod. The server owner drops assets into {@code plugins/Meme/emotes/}
 * (gifs/bubbles/sounds), tunes/prices them in {@code emotes.yml}, and clients download everything
 * automatically over plugin messages — no resource packs.
 *
 * <p>Wiring: {@link EmoteChannel} (transport + modded-player tracking + throttled asset streaming),
 * {@link EmoteRegistry} (catalogue + content-addressed assets), {@link DataStore} (purchases/grants/mutes),
 * {@link EmoteService} (validation, broadcasting, purchases), {@link ApiImpl} (public {@link MemeApi},
 * registered in the services manager). Economy: the {@code economy:} config key picks Vault (default),
 * a specific ExcellentEconomy currency, EssentialsX money or PlayerPoints; replaceable via the API.
 */
public final class MemePlugin extends JavaPlugin {
    private EmoteRegistry registry;
    private DataStore data;
    private EmoteService service;
    private EmoteChannel channel;
    private ApiImpl api;
    private volatile EconomyProvider economy;
    /** True once a provider arrived via {@link #setEconomy} — config hooks then never overwrite it. */
    private volatile boolean economyFromApi;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        installBundledPack();

        registry = new EmoteRegistry(this);
        data = new DataStore(this);
        service = new EmoteService(this);
        channel = new EmoteChannel(this);
        api = new ApiImpl(this);

        registry.load();
        data.load();
        channel.register();

        economy = new NoEconomy(getConfig().getString("currency-name", "Coins"));
        // Economy plugins register their services during their own onEnable — resolve after startup settles.
        getServer().getScheduler().runTask(this, this::hookEconomy);

        PluginCommand meme = getCommand("meme");
        if (meme != null) {
            EmoteCommand executor = new EmoteCommand(this);
            meme.setExecutor(executor);
            meme.setTabCompleter(executor);
        }
        PluginCommand memes = getCommand("memes");
        if (memes != null) {
            EmotesAdminCommand executor = new EmotesAdminCommand(this);
            memes.setExecutor(executor);
            memes.setTabCompleter(executor);
        }

        getServer().getServicesManager().register(MemeApi.class, api, this, ServicePriority.Normal);
        // ASCII only: the Windows server console codepage mangles anything fancier.
        getLogger().info("Meme enabled - " + registry.all().size() + " emotes ready.");
    }

    /**
     * First run only: drop the starter emote pack bundled in the jar into {@code emotes/packs/}.
     * "First run" = the packs dir does not exist yet; once it does (even emptied by the owner), nothing
     * is ever re-installed. A server that already has loose emotes in {@code emotes/gifs} or
     * {@code emotes/sounds} is an established install — it gets the marker dir but not the pack.
     */
    private void installBundledPack() {
        File packs = new File(getDataFolder(), "emotes/packs");
        if (packs.exists()) {
            return;
        }
        packs.mkdirs();
        String[] gifs = new File(getDataFolder(), "emotes/gifs").list();
        String[] sounds = new File(getDataFolder(), "emotes/sounds").list();
        if ((gifs != null && gifs.length > 0) || (sounds != null && sounds.length > 0)) {
            return;
        }
        try (InputStream in = getResource("meme-pack.zip")) {
            if (in == null) {
                return; // jar built without the bundled pack
            }
            Files.copy(in, new File(packs, "meme-pack.zip").toPath());
            getLogger().info("Installed the starter emote pack: emotes/packs/meme-pack.zip"
                    + " (delete the file to remove all of its emotes).");
        } catch (IOException e) {
            getLogger().warning("Could not install the bundled emote pack: " + e);
        }
    }

    /**
     * Installs the provider picked by the {@code economy:} config key. A misconfiguration (unknown value,
     * missing plugin, unknown currency) leaves purchases DISABLED with a console warning — never a silent
     * fallback to a currency the owner did not choose.
     */
    private void hookEconomy() {
        if (economyFromApi) {
            return; // another plugin installed a custom provider — theirs wins
        }
        economy = new NoEconomy(getConfig().getString("currency-name", "Coins"));
        String mode = getConfig().getString("economy", "vault").trim().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "", "vault" -> hookVault();
            case "excellenteconomy" -> hookExcellentEconomy();
            case "essentials" -> hookEssentials();
            case "playerpoints" -> hookPlayerPoints();
            default -> getLogger().warning("Unknown economy '" + mode
                    + "' in config.yml (expected vault / excellenteconomy / essentials / playerpoints)"
                    + " - purchases disabled.");
        }
    }

    private void hookVault() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().info("Vault not found - emote purchases disabled (prices still shown).");
            return;
        }
        VaultEconomy vault = VaultEconomy.tryCreate(getServer());
        if (vault != null) {
            economy = vault;
            getLogger().info("Hooked Vault economy: " + vault.currencyName());
        } else {
            getLogger().warning("Vault found but no economy registered - purchases disabled.");
        }
    }

    private void hookExcellentEconomy() {
        if (getServer().getPluginManager().getPlugin("ExcellentEconomy") == null) {
            getLogger().warning("economy: excellenteconomy, but the ExcellentEconomy plugin is not installed"
                    + " - purchases disabled.");
            return;
        }
        String currencyId = getConfig().getString("economy-currency", "").trim();
        if (currencyId.isEmpty()) {
            getLogger().warning("economy: excellenteconomy needs economy-currency (a currency id from"
                    + " plugins/ExcellentEconomy/currencies/) - purchases disabled.");
            return;
        }
        ExcellentEconomyCurrency provider =
                ExcellentEconomyCurrency.tryCreate(getServer(), currencyId, getLogger());
        if (provider != null) {
            economy = provider;
            getLogger().info("Hooked ExcellentEconomy currency '" + currencyId + "'.");
        }
    }

    private void hookEssentials() {
        if (getServer().getPluginManager().getPlugin("Essentials") == null) {
            getLogger().warning("economy: essentials, but the Essentials plugin is not installed"
                    + " - purchases disabled.");
            return;
        }
        economy = new EssentialsEconomy();
        getLogger().info("Hooked EssentialsX economy.");
    }

    private void hookPlayerPoints() {
        if (getServer().getPluginManager().getPlugin("PlayerPoints") == null) {
            getLogger().warning("economy: playerpoints, but the PlayerPoints plugin is not installed"
                    + " - purchases disabled.");
            return;
        }
        PlayerPointsEconomy provider = PlayerPointsEconomy.tryCreate();
        if (provider != null) {
            economy = provider;
            getLogger().info("Hooked PlayerPoints.");
        } else {
            getLogger().warning("PlayerPoints is present but its API is not ready - purchases disabled.");
        }
    }

    @Override
    public void onDisable() {
        if (data != null) {
            data.save();
        }
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }

    /** {@code /memes reload}: configs + folders re-read, every modded player gets a fresh catalogue. */
    public void reloadAll() {
        reloadConfig();
        hookEconomy(); // the economy: key may have changed
        registry.load();
        for (Player p : getServer().getOnlinePlayers()) {
            if (channel.isModded(p)) {
                service.sendList(p);
            }
        }
    }

    public EmoteRegistry registry() {
        return registry;
    }

    public DataStore data() {
        return data;
    }

    public EmoteService service() {
        return service;
    }

    public EmoteChannel channel() {
        return channel;
    }

    public MemeApi api() {
        return api;
    }

    public EconomyProvider economy() {
        return economy;
    }

    public void setEconomy(EconomyProvider provider) {
        if (provider != null) {
            economy = provider;
            economyFromApi = true;
        }
    }
}
