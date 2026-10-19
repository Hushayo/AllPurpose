package com.hush.allpurpose;

import com.lenis0012.bukkit.loginsecurity.LoginSecurity;
import com.lenis0012.bukkit.loginsecurity.session.AuthService;
import com.lenis0012.bukkit.loginsecurity.session.PlayerSession;
import com.lenis0012.bukkit.loginsecurity.session.action.ActionCallback;
import com.lenis0012.bukkit.loginsecurity.session.action.ActionResponse;
import com.lenis0012.bukkit.loginsecurity.session.action.LoginAction;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Auto-login for configured accounts (auth-auto-login-players), used for the
 * spare-laptop spectator so it survives server restarts with zero typing.
 * On join we wait a few seconds (LoginSecurity needs its session ready), then
 * if the account is still not authorized we perform a LoginAction on its
 * behalf via the LoginSecurity API (AuthService.PLUGIN). Retries a few times,
 * then gives up. No-op when LoginSecurity is absent or the list is empty;
 * accounts not in the list are never touched.
 */
public class AuthAutoLogin implements Listener {

    /** Delay (ticks) before each login attempt; 60 ticks = 3s. */
    private static final int[] RETRY_TICKS = {60, 120, 240, 480};
    private static final String PLUGIN_NAME = "LoginSecurity";

    private final AllPurpose plugin;

    public AuthAutoLogin(AllPurpose plugin) {
        this.plugin = plugin;
    }

    private Set<String> names() {
        Set<String> out = new HashSet<>();
        try {
            List<String> list = plugin.getConfig().getStringList("auth-auto-login-players");
            if (list != null) {
                for (String n : list) {
                    if (n != null && !n.trim().isEmpty()) out.add(n.trim().toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        try {
            if (event == null || event.getPlayer() == null) return;
            if (Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) return;
            if (!names().contains(event.getPlayer().getName().toLowerCase(Locale.ROOT))) return;
            attempt(event.getPlayer(), 0);
        } catch (Throwable ignored) {}
    }

    private void attempt(final Player player, final int step) {
        if (step >= RETRY_TICKS.length) {
            try { plugin.getLogger().warning("Auto-login: gave up on " + player.getName()); } catch (Exception ignored) {}
            return;
        }
        try {
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() {
                    try {
                        if (!player.isOnline()) return;
                        if (isAuthorized(player)) return; // session continuation already handled it
                        login(player, step);
                    } catch (Throwable t) {
                        attempt(player, step + 1);
                    }
                }
            }, RETRY_TICKS[step]);
        } catch (Throwable ignored) {}
    }

    private boolean isAuthorized(Player player) {
        PlayerSession session = LoginSecurity.getSessionManager().getPlayerSession(player);
        return session != null && session.isAuthorized();
    }

    private void login(final Player player, final int step) {
        PlayerSession session = LoginSecurity.getSessionManager().getPlayerSession(player);
        if (session == null) { attempt(player, step + 1); return; }
        try {
            session.performActionAsync(new LoginAction(AuthService.PLUGIN, plugin), new ActionCallback() {
                @Override public void call(ActionResponse response) {
                    try {
                        if (response != null && response.isSuccess()) {
                            plugin.getLogger().info("Auto-login: logged in " + player.getName() + ".");
                        } else {
                            attempt(player, step + 1);
                        }
                    } catch (Throwable t) { attempt(player, step + 1); }
                }
            });
        } catch (Throwable t) {
            attempt(player, step + 1);
        }
    }
}
