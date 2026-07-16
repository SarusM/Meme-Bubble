package io.github.sarusm.meme.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Public API of the Meme Paper plugin. Obtain it from Bukkit's services manager:
 *
 * <pre>{@code
 * MemeApi api = Bukkit.getServicesManager().load(MemeApi.class);
 * }</pre>
 *
 * Access model per emote: usable when its {@code default-access} is true, OR the player purchased it, OR an
 * admin/plugin granted it, OR the player has the {@code meme.emote.<id>} permission. Prices are charged
 * through the pluggable {@link EconomyProvider}. Events {@code EmotePlayEvent}/{@code EmotePurchaseEvent}
 * (package {@code io.github.sarusm.meme.api.events}) fire before playing/charging and are cancellable.
 */
public interface MemeApi {
    /** Ids of every configured emote (a .gif in the emotes folder). */
    Collection<String> getEmoteIds();

    /** Whether the player may play the emote right now (default access / owned / granted / permission). */
    boolean hasAccess(OfflinePlayer player, String emoteId);

    /** Grant the player permanent access to one emote ({@code "*"} = every current emote). Persisted. */
    void grant(OfflinePlayer player, String emoteId);

    /** Revoke a grant AND a purchase of the emote ({@code "*"} = all). Persisted. */
    void revoke(OfflinePlayer player, String emoteId);

    /** Emotes the player has been granted or has purchased. */
    Set<String> getGranted(UUID playerId);

    /** The emote's price ({@code 0} = free); NaN when the id is unknown. */
    double getPrice(String emoteId);

    /** Change an emote's price and persist it to emotes.yml. Online clients get the update pushed. */
    void setPrice(String emoteId, double price);

    /**
     * Play an emote over the player as if they triggered it themselves (access + mute + cooldown checks
     * still apply).
     *
     * @return true when it actually started
     */
    boolean play(Player player, String emoteId);

    /** Stop the player's current emote. */
    void stop(Player player);

    /** Mute a player's emotes for {@code durationMillis} ({@code 0} = until unmuted). Persisted. */
    void mute(OfflinePlayer player, long durationMillis);

    void unmute(OfflinePlayer player);

    boolean isMuted(OfflinePlayer player);

    /** The active economy provider (never null; a zero-functionality fallback exists without Vault). */
    EconomyProvider getEconomyProvider();

    /** Replace the economy used for emote purchases (your own currency plugin, for example). */
    void setEconomyProvider(EconomyProvider provider);
}
