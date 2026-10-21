package com.hush.allpurpose;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after a player is recorded as combat logging (disconnect/timeout while
 * tagged). Fired before the shame ceremony runs, which happens on their next
 * join so the whole server can watch.
 */
public class CombatLogEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String opponentName;
    private final int offence;
    private final int remainingSeconds;

    public CombatLogEvent(Player player, String opponentName, int offence, int remainingSeconds) {
        this.player = player;
        this.opponentName = opponentName;
        this.offence = offence;
        this.remainingSeconds = remainingSeconds;
    }

    public Player getPlayer() {
        return player;
    }

    public String getOpponentName() {
        return opponentName;
    }

    public int getOffence() {
        return offence;
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
