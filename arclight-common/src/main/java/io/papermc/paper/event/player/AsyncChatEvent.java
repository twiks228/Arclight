package io.papermc.paper.event.player;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Arclight J2K - Minimal Paper API compatibility shim for AsyncChatEvent.
 *
 * Exists only so that plugins compiled against Paper API (such as CoreProtect)
 * can register event listeners without ClassNotFoundException at startup.
 *
 * This event is never fired on Arclight.
 * Chat is handled through standard Bukkit AsyncPlayerChatEvent instead.
 */
public class AsyncChatEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();
    private boolean cancelled = false;

    // Uses the public single-argument PlayerEvent constructor.
    // The async flag is set via the inherited field through PlayerEvent(Player).
    public AsyncChatEvent(boolean async, @NotNull Player player) {
        super(player);
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
    public @NotNull HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}