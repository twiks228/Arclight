package io.izzel.arclight.common.mixin.core.server.network;

import com.mojang.authlib.GameProfile;
import io.izzel.arclight.common.mod.mixins.annotation.CreateConstructor;
import io.izzel.arclight.common.mod.mixins.annotation.ShadowConstructor;
import io.izzel.arclight.mixin.Decorate;
import io.izzel.arclight.mixin.DecorationOps;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerLinks;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.CraftServerLinks;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerLinksSendEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.net.SocketAddress;

/**
 * Mixin for {@link ServerConfigurationPacketListenerImpl} that handles
 * the configuration phase of player login on hybrid servers.
 *
 * <p>Key behaviours:</p>
 * <ul>
 *   <li>Fires {@link PlayerLinksSendEvent} before server links are sent</li>
 *   <li>Skips redundant login check during configuration→game transition
 *       (the check was already performed during the login phase)</li>
 *   <li>Reuses the existing {@link ServerPlayer} instance instead of creating
 *       a new one, preserving state from the login phase</li>
 * </ul>
 */
@Mixin(value = ServerConfigurationPacketListenerImpl.class, priority = 1100)
public abstract class ServerConfigurationPacketListenerImplMixin
        extends ServerCommonPacketListenerImplMixin {

    @Mutable
    @Shadow @Final private GameProfile gameProfile;

    @Shadow private ClientInformation clientInformation;

    // ── Constructor ───────────────────────────────────────────────────────────

    @ShadowConstructor.Super
    public abstract void arclight$super(
            MinecraftServer server, Connection connection,
            CommonListenerCookie cookie, ServerPlayer player);

    @CreateConstructor
    public void arclight$constructor(
            MinecraftServer server, Connection connection,
            CommonListenerCookie cookie, ServerPlayer player) {
        arclight$super(server, connection, cookie, player);
        this.gameProfile = cookie.gameProfile();
        this.clientInformation = cookie.clientInformation();
    }

    // ── Server links event ────────────────────────────────────────────────────

    /**
     * Fires {@link PlayerLinksSendEvent} before server links are sent to the client
     * during the configuration phase. Allows plugins to modify the links.
     * {@code require = 0} prevents crashes if the method signature changes in a future version.
     */
    @Decorate(
        method = "startConfiguration",
        require = 0,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;serverLinks()Lnet/minecraft/server/ServerLinks;"
        )
    )
    private ServerLinks arclight$sendLinksEvent(MinecraftServer instance) throws Throwable {
        var links = (ServerLinks) DecorationOps.callsite().invoke(instance);
        var wrapper = new CraftServerLinks(links);
        var event = new PlayerLinksSendEvent(
            (Player) player.bridge$getBukkitEntity(), wrapper
        );
        Bukkit.getPluginManager().callEvent(event);
        return wrapper.getServerLinks();
    }

    // ── Configuration finished ────────────────────────────────────────────────

    /**
     * Skips the redundant {@code canPlayerLogin} check during configuration→game
     * transition. The player was already validated during the login phase;
     * re-checking here would incorrectly reject players if the server became
     * full between login and configuration completion.
     */
    @Redirect(
        method = "handleConfigurationFinished",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;canPlayerLogin(" +
                     "Ljava/net/SocketAddress;" +
                     "Lcom/mojang/authlib/GameProfile;" +
                     ")Lnet/minecraft/network/chat/Component;"
        )
    )
    private Component arclight$skipLoginCheck(
            PlayerList instance, SocketAddress address, GameProfile profile) {
        return null; // Always allow — already validated during login
    }

    /**
     * Reuses the existing {@link ServerPlayer} instance from the login phase
     * instead of creating a new one. This preserves player state (inventory,
     * position, etc.) that was set up during login event handling.
     *
     * <p>Also applies the latest {@link ClientInformation} from the configuration
     * phase (language, render distance, etc.).</p>
     */
    @Redirect(
        method = "handleConfigurationFinished",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;getPlayerForLogin(" +
                     "Lcom/mojang/authlib/GameProfile;" +
                     "Lnet/minecraft/server/level/ClientInformation;" +
                     ")Lnet/minecraft/server/level/ServerPlayer;"
        )
    )
    private ServerPlayer arclight$useCurrentPlayer(
            PlayerList instance, GameProfile profile, ClientInformation clientInfo) {
        this.player.updateOptions(clientInfo);
        return this.player;
    }
}