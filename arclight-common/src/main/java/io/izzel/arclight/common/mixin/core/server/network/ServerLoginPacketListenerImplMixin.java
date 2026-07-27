package io.izzel.arclight.common.mixin.core.server.network;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.properties.Property;
import io.izzel.arclight.common.bridge.core.network.ConnectionBridge;
import io.izzel.arclight.common.bridge.core.server.network.ServerCommonPacketListenerImplBridge;
import io.izzel.arclight.common.bridge.core.server.network.ServerLoginPacketListenerImplBridge;
import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import io.izzel.arclight.common.bridge.core.server.players.PlayerListBridge;
import io.izzel.arclight.common.mod.util.VelocitySupport;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.Util;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;
import net.minecraft.util.StringUtil;
import org.apache.commons.lang3.Validate;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v.CraftServer;
import org.bukkit.craftbukkit.v.entity.CraftPlayer;
import org.bukkit.craftbukkit.v.util.Waitable;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.PrivateKey;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Mixin for {@link ServerLoginPacketListenerImpl} that integrates Bukkit's
 * login event pipeline and proxy forwarding support.
 *
 * <p>Login flow:</p>
 * <ol>
 *   <li>{@link #handleHello} — receives client hello, initiates auth or offline login</li>
 *   <li>{@link #handleKey} — (online mode) verifies encryption and authenticates with Mojang</li>
 *   <li>{@link #bridge$preLogin} — fires Bukkit pre-login events</li>
 *   <li>{@code startClientVerification} → configuration phase</li>
 * </ol>
 *
 * <p>Supports three proxy forwarding modes:</p>
 * <ul>
 *   <li><b>None</b> — standard Mojang authentication</li>
 *   <li><b>BungeeCord</b> — hostname-based UUID/profile injection (legacy)</li>
 *   <li><b>Velocity</b> — plugin channel-based modern forwarding</li>
 * </ul>
 */
@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 1100)
public abstract class ServerLoginPacketListenerImplMixin
        implements ServerLoginPacketListenerImplBridge, CraftPlayer.TransferCookieConnection {

    // @formatter:off
    @Shadow private ServerLoginPacketListenerImpl.State state;
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final public Connection connection;
    @Shadow @Final private static AtomicInteger UNIQUE_THREAD_ID;
    @Shadow @Final private static Logger LOGGER;
    @Shadow public abstract void disconnect(Component reason);
    @Shadow public abstract String getUserName();
    @Shadow @Final private byte[] challenge;
    @Shadow @Nullable private String requestedUsername;
    @Shadow abstract void startClientVerification(GameProfile profile);
    @Shadow protected abstract boolean isPlayerAlreadyInWorld(GameProfile profile);
    @Shadow @Nullable private GameProfile authenticatedProfile;
    @Shadow @Final private boolean transferred;
    // @formatter:on

    // ── Constants ─────────────────────────────────────────────────────────────

    /**
     * Regex for validating property names in spoofed game profiles.
     * Only word characters (letters, digits, underscore) up to 16 chars.
     */
    @Unique
    private static final Pattern PROP_PATTERN = Pattern.compile("\\w{0,16}");

    // ── State ─────────────────────────────────────────────────────────────────

    /** The Bukkit player entity created during login validation. */
    private ServerPlayer player;

    /** Transaction ID for Velocity modern forwarding, or -1 if not using Velocity. */
    @Unique
    protected int arclight$velocityLoginId = -1;

    // ── Bridge implementation ─────────────────────────────────────────────────

    @Override
    public int bridge$getVelocityLoginId() {
        return arclight$velocityLoginId;
    }

    @Override
    public void bridge$disconnect(String s) {
        this.disconnect(Component.literal(s));
    }

    public void disconnect(String s) {
        bridge$disconnect(s);
    }

    // ── Hello handler ─────────────────────────────────────────────────────────

    /**
     * @author IzzelAliz
     * @reason Overwritten to integrate Bukkit pre-login events, BungeeCord/Velocity
     * forwarding, and offline-mode profile creation into the login flow.
     */
    @Overwrite
    public void handleHello(ServerboundHelloPacket packetIn) {
        Validate.validState(
            this.state == ServerLoginPacketListenerImpl.State.HELLO,
            "Unexpected hello packet"
        );
        Validate.validState(
            StringUtil.isValidPlayerName(packetIn.name()),
            "Invalid characters in username"
        );

        this.requestedUsername = packetIn.name();
        GameProfile singleplayerProfile = this.server.getSingleplayerProfile();

        if (singleplayerProfile != null
                && this.requestedUsername.equalsIgnoreCase(singleplayerProfile.getName())) {
            // Singleplayer owner — skip authentication entirely
            this.startClientVerification(singleplayerProfile);
            return;
        }

        if (this.server.usesAuthentication() && !this.connection.isMemoryConnection()) {
            // Online mode — send encryption request
            this.state = ServerLoginPacketListenerImpl.State.KEY;
            this.connection.send(new ClientboundHelloPacket(
                "", this.server.getKeyPair().getPublic().getEncoded(),
                this.challenge, true
            ));
            return;
        }

        // Offline mode
        if (VelocitySupport.isEnabled()) {
            // Velocity modern forwarding: send a custom query to get player info
            this.arclight$velocityLoginId = ThreadLocalRandom.current().nextInt();
            this.connection.send(new ClientboundCustomQueryPacket(
                this.arclight$velocityLoginId, VelocitySupport.createPacket()
            ));
            return;
        }

        // Standard offline mode: create profile and fire pre-login events
        Thread thread = bridge$newHandleThread(
            "User Authenticator #" + UNIQUE_THREAD_ID.incrementAndGet(),
            () -> {
                try {
                    GameProfile profile = arclight$createOfflineProfile(
                        connection, requestedUsername
                    );
                    bridge$preLogin(profile);
                } catch (Exception ex) {
                    disconnect(Component.translatable(
                        "multiplayer.disconnect.unverified_username"
                    ));
                    LOGGER.warn("Exception verifying {}", requestedUsername, ex);
                }
            }
        );
        thread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
        thread.start();
    }

    // ── Offline profile creation ──────────────────────────────────────────────

    /**
     * Creates an offline-mode {@link GameProfile} using either the BungeeCord-spoofed
     * UUID or a deterministic offline UUID derived from the player name.
     *
     * <p>If BungeeCord has provided spoofed profile properties (e.g., skin data),
     * they are copied into the profile after filtering invalid property names.</p>
     *
     * @param connection the network connection (may contain spoofed data)
     * @param name       the player's requested username
     * @return the constructed offline game profile
     */
    @Unique
    private static GameProfile arclight$createOfflineProfile(Connection connection, String name) {
        ConnectionBridge bridge = (ConnectionBridge) connection;

        UUID uuid = (bridge.bridge$getSpoofedUUID() != null)
            ? bridge.bridge$getSpoofedUUID()
            : UUIDUtil.createOfflinePlayerUUID(name);

        GameProfile profile = new GameProfile(uuid, name);

        // Copy spoofed properties from BungeeCord (skin, cape, etc.)
        Property[] spoofedProperties = bridge.bridge$getSpoofedProfile();
        if (spoofedProperties != null) {
            for (Property property : spoofedProperties) {
                if (PROP_PATTERN.matcher(property.name()).matches()) {
                    profile.getProperties().put(property.name(), property);
                }
            }
        }

        return profile;
    }

    // ── Login verification ────────────────────────────────────────────────────

    /**
     * Redirects the vanilla login check to use Arclight's extended
     * {@link PlayerListBridge#bridge$canPlayerLogin} which creates
     * the Bukkit player entity and fires login events.
     */
    @Redirect(
        method = "verifyLoginAndFinishConnectionSetup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;canPlayerLogin(" +
                     "Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;" +
                     ")Lnet/minecraft/network/chat/Component;"
        )
    )
    private Component arclight$canLogin(
            PlayerList instance, SocketAddress address, GameProfile profile) {
        if (this.player == null) {
            this.player = ((PlayerListBridge) instance).bridge$canPlayerLogin(
                address, profile,
                (ServerLoginPacketListenerImpl) (Object) this
            );
        }
        return null; // Login check result is handled via player being null or not
    }

    /**
     * Cancels login if the Bukkit player creation/validation failed,
     * or if the player is awaiting cookie responses.
     */
    @Inject(
        method = "verifyLoginAndFinishConnectionSetup",
        cancellable = true,
        at = @At(
            value = "INVOKE",
            shift = At.Shift.AFTER,
            target = "Lnet/minecraft/server/players/PlayerList;canPlayerLogin(" +
                     "Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;" +
                     ")Lnet/minecraft/network/chat/Component;"
        )
    )
    private void arclight$returnIfFail(GameProfile profile, CallbackInfo ci) {
        if (this.player == null) {
            ci.cancel();
        } else if (((CraftPlayer) this.player.bridge$getBukkitEntity()).isAwaitingCookies()) {
            ci.cancel();
        }
    }

    /**
     * Skips the vanilla "disconnect all players with same profile" logic.
     * Arclight handles this via {@link #isPlayerAlreadyInWorld} instead,
     * which correctly handles the configuration→game transition.
     */
    @Redirect(
        method = "verifyLoginAndFinishConnectionSetup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;disconnectAllPlayersWithProfile(" +
                     "Lcom/mojang/authlib/GameProfile;)Z"
        )
    )
    private boolean arclight$skipKick(PlayerList instance, GameProfile profile) {
        return this.isPlayerAlreadyInWorld(
            Objects.requireNonNull(this.authenticatedProfile)
        );
    }

    // ── Login acknowledgement ─────────────────────────────────────────────────

    /**
     * Ensures the login acknowledgement packet is processed on the main thread.
     * This is critical because the configuration phase setup is not thread-safe.
     */
    @Inject(method = "handleLoginAcknowledgement", at = @At("HEAD"))
    private void arclight$mainThreadConfiguration(
            ServerboundLoginAcknowledgedPacket packet, CallbackInfo ci) {
        PacketUtils.ensureRunningOnSameThread(
            packet, (ServerLoginPacketListenerImpl) (Object) this, this.server
        );
    }

    /**
     * Passes the Bukkit player entity to the newly created configuration listener,
     * preserving state from the login phase.
     */
    @Inject(
        method = "handleLoginAcknowledgement",
        locals = LocalCapture.CAPTURE_FAILHARD,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;setupInboundProtocol(" +
                     "Lnet/minecraft/network/ProtocolInfo;" +
                     "Lnet/minecraft/network/PacketListener;)V"
        )
    )
    private void arclight$setPlayer(
            ServerboundLoginAcknowledgedPacket packet,
            CallbackInfo ci,
            CommonListenerCookie cookie,
            ServerConfigurationPacketListenerImpl listener) {
        ((ServerCommonPacketListenerImplBridge) listener).bridge$setPlayer(this.player);
    }

    // ── Cookie response ───────────────────────────────────────────────────────

    @Inject(method = "handleCookieResponse", cancellable = true, at = @At("HEAD"))
    private void arclight$cookieResponse(
            ServerboundCookieResponsePacket packet, CallbackInfo ci) {
        PacketUtils.ensureRunningOnSameThread(
            packet, (ServerLoginPacketListenerImpl) (Object) this, this.server
        );
        if (this.player != null
                && ((CraftPlayer) this.player.bridge$getBukkitEntity()).handleCookieResponse(packet)) {
            ci.cancel();
        }
    }

    // ── Encryption key handler ────────────────────────────────────────────────

    /**
     * @author IzzelAliz
     * @reason Overwritten to integrate Bukkit pre-login events into the
     * Mojang authentication flow and handle offline fallback for singleplayer.
     */
    @Overwrite
    public void handleKey(ServerboundKeyPacket packetIn) {
        Validate.validState(
            this.state == ServerLoginPacketListenerImpl.State.KEY,
            "Unexpected key packet"
        );

        final String serverId;
        try {
            PrivateKey privateKey = this.server.getKeyPair().getPrivate();
            if (!packetIn.isChallengeValid(this.challenge, privateKey)) {
                throw new IllegalStateException("Protocol error");
            }

            SecretKey secretKey = packetIn.getSecretKey(privateKey);
            Cipher decryptCipher = Crypt.getCipher(2, secretKey);
            Cipher encryptCipher = Crypt.getCipher(1, secretKey);

            serverId = new BigInteger(
                Crypt.digestData("", this.server.getKeyPair().getPublic(), secretKey)
            ).toString(16);

            this.state = ServerLoginPacketListenerImpl.State.AUTHENTICATING;
            this.connection.setEncryptionKey(decryptCipher, encryptCipher);
        } catch (CryptException e) {
            throw new IllegalStateException("Protocol error", e);
        }

        Thread thread = bridge$newHandleThread(
            "User Authenticator #" + UNIQUE_THREAD_ID.incrementAndGet(),
            () -> {
                String name = Objects.requireNonNull(requestedUsername, "Player name not initialized");
                try {
                    // Check if the proxy prevention requires IP verification
                    SocketAddress remoteAddress = connection.getRemoteAddress();
                    InetAddress verifyAddress = (server.getPreventProxyConnections()
                        && remoteAddress instanceof InetSocketAddress inet)
                        ? inet.getAddress()
                        : null;

                    var profileResult = server.getSessionService()
                        .hasJoinedServer(name, serverId, verifyAddress);

                    if (profileResult != null) {
                        GameProfile profile = profileResult.profile();
                        if (!connection.isConnected()) return;
                        bridge$preLogin(profile);
                    } else if (server.isSingleplayer()) {
                        LOGGER.warn("Failed to verify username but will let them in anyway!");
                        startClientVerification(arclight$createOfflineProfile(connection, name));
                    } else {
                        disconnect(Component.translatable(
                            "multiplayer.disconnect.unverified_username"
                        ));
                        LOGGER.error("Username '{}' tried to join with an invalid session", name);
                    }
                } catch (AuthenticationException e) {
                    if (server.isSingleplayer()) {
                        LOGGER.warn("Authentication servers are down but will let them in anyway!");
                        startClientVerification(arclight$createOfflineProfile(connection, name));
                    } else {
                        disconnect(Component.translatable(
                            "multiplayer.disconnect.authservers_down"
                        ));
                        LOGGER.error("Couldn't verify username because servers are unavailable");
                    }
                } catch (Exception e) {
                    disconnect(Component.translatable(
                        "multiplayer.disconnect.unverified_username"
                    ));
                    LOGGER.error("Exception verifying {}", name, e);
                }
            }
        );
        thread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
        thread.start();
    }

    // ── Pre-login event pipeline ──────────────────────────────────────────────

    /**
     * Fires Bukkit's pre-login events ({@link AsyncPlayerPreLoginEvent} and
     * the deprecated {@link PlayerPreLoginEvent}) and proceeds with client
     * verification if not rejected.
     *
     * <p>If Velocity modern forwarding is enabled but the login did not go
     * through the Velocity channel, the player is disconnected.</p>
     *
     * @param gameProfile the authenticated or offline game profile
     * @throws Exception if a synchronous event waitable is interrupted
     */
    @Unique
    public void bridge$preLogin(GameProfile gameProfile) throws Exception {
        if (this.arclight$velocityLoginId == -1 && VelocitySupport.isEnabled()) {
            disconnect("This server requires you to connect with Velocity.");
            return;
        }
        arclight$callPlayerPreLoginEvents(gameProfile);
        LOGGER.info("UUID of player {} is {}", gameProfile.getName(), gameProfile.getId());
        this.startClientVerification(gameProfile);
    }

    /**
     * Fires both the async and (if handlers are registered) synchronous
     * pre-login events for the given profile.
     *
     * <p>The synchronous event is dispatched to the main server thread via
     * a {@link Waitable} to ensure thread safety.</p>
     *
     * @param profile the player's game profile
     * @throws Exception if the synchronous waitable throws
     */
    @Unique
    private void arclight$callPlayerPreLoginEvents(GameProfile profile) throws Exception {
        String playerName = profile.getName();
        InetAddress address = ((InetSocketAddress) connection.getRemoteAddress()).getAddress();
        UUID uniqueId = profile.getId();
        CraftServer craftServer = (CraftServer) Bukkit.getServer();

        // Fire async event first (runs on the auth thread)
        AsyncPlayerPreLoginEvent asyncEvent = new AsyncPlayerPreLoginEvent(
            playerName, address, uniqueId
        );
        craftServer.getPluginManager().callEvent(asyncEvent);

        // Only fire the deprecated synchronous event if any handlers are registered
        if (PlayerPreLoginEvent.getHandlerList().getRegisteredListeners().length != 0) {
            PlayerPreLoginEvent syncEvent = new PlayerPreLoginEvent(
                playerName, address, uniqueId
            );
            if (asyncEvent.getResult() != PlayerPreLoginEvent.Result.ALLOWED) {
                syncEvent.disallow(asyncEvent.getResult(), asyncEvent.getKickMessage());
            }

            Waitable<PlayerPreLoginEvent.Result> waitable = new Waitable<>() {
                @Override
                protected PlayerPreLoginEvent.Result evaluate() {
                    craftServer.getPluginManager().callEvent(syncEvent);
                    return syncEvent.getResult();
                }
            };

            ((MinecraftServerBridge) server).bridge$queuedProcess(waitable);

            if (waitable.get() != PlayerPreLoginEvent.Result.ALLOWED) {
                disconnect(syncEvent.getKickMessage());
            }
        } else if (asyncEvent.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            disconnect(asyncEvent.getKickMessage());
        }
    }

    // ── Velocity custom query handler ─────────────────────────────────────────

    /**
     * Handles the Velocity modern forwarding response via a custom query answer.
     *
     * <p>Validates the HMAC integrity of the forwarding data, extracts the
     * real client IP and game profile, and proceeds with login.</p>
     *
     * <p><b>FFAPI compatibility note:</b> Forgified Fabric API (FFAPI) records
     * every custom query and awaits all responses before entering configuration.
     * This handler is injected at INVOKE disconnect to ensure it runs after FFAPI's
     * HEAD handler, preserving a defined injection order.</p>
     *
     * @see VelocitySupport
     */
    @Inject(
        method = "handleCustomQueryPacket",
        cancellable = true,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"
        )
    )
    private void arclight$modernForwardReply(
            ServerboundCustomQueryAnswerPacket packet, CallbackInfo ci) {
        if (!VelocitySupport.isEnabled()
                || packet.transactionId() != this.bridge$getVelocityLoginId()) {
            return;
        }

        var payload = arclight$platform$customQAData(packet);
        if (payload == null) {
            this.bridge$disconnect("This server requires you to connect with Velocity.");
            ci.cancel();
            return;
        }

        var buf = payload.readNullable(r -> {
            int size = r.readableBytes();
            if (size < 0 || size > 1048576) {
                throw new IllegalArgumentException(
                    "Payload may not be larger than 1048576 bytes"
                );
            }
            return new FriendlyByteBuf(r.readBytes(size));
        });

        if (buf == null) {
            this.bridge$disconnect("This server requires you to connect with Velocity.");
            ci.cancel();
            return;
        }

        if (!VelocitySupport.checkIntegrity(buf)) {
            this.bridge$disconnect("Unable to verify player details");
            ci.cancel();
            return;
        }

        int version = buf.readVarInt();
        if (version > VelocitySupport.MAX_SUPPORTED_FORWARDING_VERSION) {
            throw new IllegalStateException(
                "Unsupported forwarding version " + version
                + ", wanted up to " + VelocitySupport.MAX_SUPPORTED_FORWARDING_VERSION
            );
        }

        // Extract the real client port from the current connection
        SocketAddress listening = this.connection.getRemoteAddress();
        int port = (listening instanceof InetSocketAddress inet) ? inet.getPort() : 0;

        // Replace the connection address with the real client address from Velocity
        this.connection.address = new InetSocketAddress(
            VelocitySupport.readAddress(buf), port
        );
        this.authenticatedProfile = VelocitySupport.createProfile(buf);

        // Proceed with login on the background executor
        Util.backgroundExecutor().execute(() -> {
            try {
                this.bridge$preLogin(this.authenticatedProfile);
            } catch (Exception ex) {
                disconnect(Component.translatable(
                    "multiplayer.disconnect.unverified_username"
                ));
                LOGGER.warn("Exception verifying {}",
                    this.authenticatedProfile.getName(), ex);
            }
        });

        this.arclight$platform$onCustomQA(packet);
        ci.cancel();
    }

    // ── TransferCookieConnection implementation ───────────────────────────────

    @Override
    public boolean isTransferred() {
        return this.transferred;
    }

    @Override
    public ConnectionProtocol getProtocol() {
        return ConnectionProtocol.LOGIN;
    }

    @Override
    public void sendPacket(Packet<?> packet) {
        this.connection.send(packet);
    }

    @Override
    public void kickPlayer(Component component) {
        disconnect(component);
    }
}