package io.github.sarusm.meme.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player is charged for an emote purchase. Cancel to block the sale (the player keeps the
 * money and does not receive the emote).
 */
public class EmotePurchaseEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String emoteId;
    private final double price;
    private boolean cancelled;

    public EmotePurchaseEvent(Player player, String emoteId, double price) {
        this.player = player;
        this.emoteId = emoteId;
        this.price = price;
    }

    public Player getPlayer() {
        return player;
    }

    public String getEmoteId() {
        return emoteId;
    }

    public double getPrice() {
        return price;
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
