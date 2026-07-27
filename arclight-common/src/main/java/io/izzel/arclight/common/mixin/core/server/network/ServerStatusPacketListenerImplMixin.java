package io.izzel.arclight.common.mixin.core.server.network;

import com.mojang.authlib.GameProfile;
import io.izzel.arclight.common.bridge.core.server.network.ServerStatusPacketListenerImplBridge;
import io.izzel.arclight.common.mod.server.ArclightServer;
import io.izzel.arclight.common.mod.util.ArclightPingEvent;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.util.CraftChatMessage;
import org.spigotmc.SpigotConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Mixin for {@link ServerStatusPacketListenerImpl} that replaces the vanilla
 * status response with a Bukkit-customizable server list ping response.
 *
 * <p>Fires {@link ArclightPingEvent} (a Bukkit {@code ServerListPingEvent})
 * allowing plugins to modify:</p>
 * <ul>
 *   <li>MOTD (message of the day)</li>
 *   <li>Player count and max players</li>
 *   <li>Server icon (favicon)</li>
 *   <li>Player sample list</li>
 * </ul>
 *
 * <p>The player sample respects:</p>
 * <ul>
 *   <li>{@code hideOnlinePlayers} server option — shows empty list when enabled</li>
 *   <li>{@code allowsListing} per-player setting — uses anonymous profile for hidden players</li>
 *   <li>Spigot's {@code playerSample} config — limits sample size</li>
 * </ul>
 */
@Mixin(value = ServerStatusPacketListenerImpl.class, priority = 1100)
public class ServerStatusPacketListenerImplMixin
        implements ServerStatusPacketListenerImplBridge {

    @Redirect(
        method = "handleStatusRequest",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private void arclight$handleServerPing(Connection networkManager, Packet<?> packetIn) {
        MinecraftServer server = ArclightServer.getMinecraftServer();

        // Build player sample list (respects listing privacy settings)
        List<ServerPlayer> onlinePlayers = server.getPlayerList().players;
        List<GameProfile> profiles = new ArrayList<>(onlinePlayers.size());

        for (ServerPlayer player : onlinePlayers) {
            if (player == null) continue;
            profiles.add(player.allowsListing()
                ? player.getGameProfile()
                : MinecraftServer.ANONYMOUS_PLAYER_PROFILE
            );
        }

        // Shuffle and limit sample size
        if (!server.hidesOnlinePlayers() && !profiles.isEmpty()) {
            Collections.shuffle(profiles);
            profiles = profiles.subList(0, Math.min(profiles.size(), SpigotConfig.playerSample));
        }

        // Fire Bukkit ping event for plugin customization
        ArclightPingEvent event = new ArclightPingEvent(networkManager, server);
        Bukkit.getPluginManager().callEvent(event);

        // Build the response using (potentially modified) event data
        ServerStatus.Players playerSample = new ServerStatus.Players(
            event.getMaxPlayers(),
            event.getNumPlayers(),
            server.hidesOnlinePlayers() ? Collections.emptyList() : profiles
        );

        ServerStatus ping = bridge$platform$createServerStatus(
            CraftChatMessage.fromString(event.getMotd(), true)[0],
            Optional.of(playerSample),
            Optional.of(new ServerStatus.Version(
                server.getServerModName() + " " + server.getServerVersion(),
                SharedConstants.getCurrentVersion().getProtocolVersion()
            )),
            (event.icon.value != null)
                ? Optional.of(new ServerStatus.Favicon(event.icon.value))
                : Optional.empty(),
            server.enforceSecureProfile()
        );

        networkManager.send(new ClientboundStatusResponsePacket(ping));
    }
}