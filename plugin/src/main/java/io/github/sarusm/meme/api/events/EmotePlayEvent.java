package io.github.sarusm.meme.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before an emote starts playing over a player (command, client panel or API). Cancel to block it —
 * e.g. in a region, during a minigame, or while the player is vanished.
 */
public class EmotePlayEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String emoteId;
    private boolean cancelled;

    public EmotePlayEvent(Player player, String emoteId) {
        this.player = player;
        this.emoteId = emoteId;
    }

    public Player getPlayer() {
        return player;
    }

    public String getEmoteId() {
        return emoteId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
