package com.hush.allpurpose;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a player is about to be combat-tagged (once per player per hit).
 * Cancelling the event skips the tag for that player only.
 */
public class CombatTagEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Player opponent;
    private final int seconds;
    private boolean cancelled;

    public CombatTagEvent(Player player, Player opponent, int seconds) {
        this.player = player;
        this.opponent = opponent;
        this.seconds = seconds;
    }

    public Player getPlayer() {
        return player;
    }

    public Player getOpponent() {
        return opponent;
    }

    public int getSeconds() {
        return seconds;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
