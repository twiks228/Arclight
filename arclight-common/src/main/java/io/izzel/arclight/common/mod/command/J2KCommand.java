package io.izzel.arclight.common.mod.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;

/**
 * Arclight J2K - /j2k command.
 * Provides server operators with runtime diagnostics and compatibility info.
 * Self-contained — does not depend on bootstrap module classes.
 */
public class J2KCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "\u00a78[\u00a76J2K\u00a78] \u00a7r";

    // Cached conflict scan result label
    private static String lastScanSummary = null;

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args) {

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status"  -> handleStatus(sender);
            case "scan"    -> handleScan(sender);
            case "version" -> handleVersion(sender);
            case "memory"  -> handleMemory(sender);
            case "help"    -> sendHelp(sender);
            default        -> sender.sendMessage(
                PREFIX + "\u00a7cUnknown subcommand. Use \u00a7f/j2k help");
        }

        return true;
    }

    // ── /j2k status ──────────────────────────────────────────────────────────

    private void handleStatus(CommandSender sender) {
        String neoforgeVer  = System.getProperty("arclight.neoforge.version", "21.1.228");
        String minecraftVer = System.getProperty("arclight.minecraft.version", "1.21.1");
        String javaVersion  = System.getProperty("java.version", "?");
        String javaVendor   = shortenVendor(System.getProperty("java.vendor", "Unknown"));
        String osName       = System.getProperty("os.name", "?");
        String osArch       = System.getProperty("os.arch", "?");
        int    cpuCores     = Runtime.getRuntime().availableProcessors();

        long maxRamMB  = Runtime.getRuntime().maxMemory()   / 1024 / 1024;
        long totalMB   = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        long usedRamMB = totalMB - Runtime.getRuntime().freeMemory() / 1024 / 1024;

        long uptimeSec = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        int modCount    = countJarsInFolder("mods");
        int pluginCount = countJarsInFolder("plugins");

        sender.sendMessage("");
        sender.sendMessage(PREFIX + "\u00a76\u00a7l=== Arclight J2K Status ===");
        sender.sendMessage(PREFIX + "\u00a7ePlatform  \u00a78: \u00a7fMC \u00a7a" + minecraftVer
            + " \u00a78| \u00a7fNeoForge \u00a7a" + neoforgeVer);
        sender.sendMessage(PREFIX + "\u00a7eJava      \u00a78: \u00a7f" + javaVersion
            + " \u00a78(" + javaVendor + ")");
        sender.sendMessage(PREFIX + "\u00a7eSystem    \u00a78: \u00a7f" + osName
            + " " + osArch + " \u00a78| \u00a7fCores: \u00a7a" + cpuCores);
        sender.sendMessage(PREFIX + "\u00a7eMemory    \u00a78: \u00a7a" + usedRamMB
            + "\u00a7f/\u00a7a" + maxRamMB + " \u00a7fMB");
        sender.sendMessage(PREFIX + "\u00a7eUptime    \u00a78: \u00a7a" + formatUptime(uptimeSec));
        sender.sendMessage(PREFIX + "\u00a7eMods      \u00a78: \u00a7a"
            + (modCount >= 0 ? modCount : "?") + " \u00a77jars in mods/");
        sender.sendMessage(PREFIX + "\u00a7ePlugins   \u00a78: \u00a7a"
            + (pluginCount >= 0 ? pluginCount : "?") + " \u00a77jars in plugins/");

        if (lastScanSummary != null) {
            sender.sendMessage(PREFIX + "\u00a7eCompat    \u00a78: " + lastScanSummary);
        } else {
            sender.sendMessage(PREFIX + "\u00a7eCompat    \u00a78: \u00a77(run /j2k scan)");
        }

        sender.sendMessage("");
    }

    // ── /j2k scan ─────────────────────────────────────────────────────────────

    private void handleScan(CommandSender sender) {
        sender.sendMessage(PREFIX + "\u00a7eScanning mods/ and plugins/ for conflicts...");

        Thread scanThread = new Thread(() -> {
            try {
                List<String> modNames    = getJarNames("mods");
                List<String> pluginNames = getJarNames("plugins");
                List<String> issues      = new ArrayList<>();

                // Known client-only mods that should not be on server
                List<String> clientOnly = Arrays.asList(
                    "optifine", "iris", "sodium", "embeddium", "rubidium"
                );
                for (String m : modNames) {
                    for (String bad : clientOnly) {
                        if (m.toLowerCase().contains(bad)) {
                            issues.add("\u00a7c[ERROR] Client-only mod in mods/: " + m);
                        }
                    }
                }

                // Known plugins placed in wrong folder
                List<String> pluginsInMods = Arrays.asList(
                    "worldguard", "viaversion", "protocollib", "essentials"
                );
                for (String m : modNames) {
                    for (String bad : pluginsInMods) {
                        if (m.toLowerCase().contains(bad)) {
                            issues.add("\u00a7c[ERROR] Plugin in mods/ folder: " + m
                                + " \u00a77(move to plugins/)");
                        }
                    }
                }

                // Duplicate names in both folders
                for (String mod : modNames) {
                    String modKey = mod.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
                    if (modKey.length() < 5) continue;
                    for (String plugin : pluginNames) {
                        String pluginKey = plugin.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
                        if (pluginKey.length() < 5) continue;
                        int len = Math.min(6, Math.min(modKey.length(), pluginKey.length()));
                        if (modKey.substring(0, len).equals(pluginKey.substring(0, len))) {
                            issues.add("\u00a7e[WARN] Possible duplicate: mods/" + mod
                                + " and plugins/" + plugin);
                        }
                    }
                }

                // Build summary
                long errors   = issues.stream().filter(s -> s.contains("[ERROR]")).count();
                long warnings = issues.stream().filter(s -> s.contains("[WARN]")).count();

                sender.sendMessage(PREFIX + "\u00a7aScan complete.");
                sender.sendMessage(PREFIX + "\u00a7eMods    \u00a78: \u00a7a" + modNames.size());
                sender.sendMessage(PREFIX + "\u00a7ePlugins \u00a78: \u00a7a" + pluginNames.size());

                if (issues.isEmpty()) {
                    lastScanSummary = "\u00a7a\u2714 No conflicts";
                    sender.sendMessage(PREFIX + "\u00a7a\u2714 No conflicts detected!");
                } else {
                    lastScanSummary = "\u00a7c" + errors + " error(s) \u00a7e" + warnings + " warn(s)";
                    for (String issue : issues) {
                        sender.sendMessage(PREFIX + issue);
                    }
                }

            } catch (Exception e) {
                sender.sendMessage(PREFIX + "\u00a7cScan failed: " + e.getMessage());
            }
        }, "Arclight-J2K-Scan");

        scanThread.setDaemon(true);
        scanThread.start();
    }

    // ── /j2k version ──────────────────────────────────────────────────────────

    private void handleVersion(CommandSender sender) {
        String neoforgeVer  = System.getProperty("arclight.neoforge.version", "21.1.228");
        String minecraftVer = System.getProperty("arclight.minecraft.version", "1.21.1");
        String javaVersion  = System.getProperty("java.version", "?");

        sender.sendMessage("");
        sender.sendMessage(PREFIX + "\u00a7a\u00a7lArclight J2K");
        sender.sendMessage(PREFIX + "\u00a7eMC         \u00a78: \u00a7f" + minecraftVer);
        sender.sendMessage(PREFIX + "\u00a7eNeoForge   \u00a78: \u00a7f" + neoforgeVer);
        sender.sendMessage(PREFIX + "\u00a7eJava       \u00a78: \u00a7f" + javaVersion);
        sender.sendMessage(PREFIX + "\u00a7eAuthor     \u00a78: \u00a7fJake (twiks228)");
        sender.sendMessage(PREFIX + "\u00a7eBase       \u00a78: \u00a77Arclight by IzzelAliz");
        sender.sendMessage(PREFIX + "\u00a7eGitHub     \u00a78: \u00a77github.com/twiks228/Arclight");
        sender.sendMessage("");
    }

    // ── /j2k memory ───────────────────────────────────────────────────────────

    private void handleMemory(CommandSender sender) {
        Runtime rt = Runtime.getRuntime();
        long maxMB   = rt.maxMemory()   / 1024 / 1024;
        long totalMB = rt.totalMemory() / 1024 / 1024;
        long freeMB  = rt.freeMemory()  / 1024 / 1024;
        long usedMB  = totalMB - freeMB;
        int  pct     = (int) (usedMB * 100 / Math.max(maxMB, 1));

        sender.sendMessage("");
        sender.sendMessage(PREFIX + "\u00a76\u00a7l=== Memory ===");
        sender.sendMessage(PREFIX + "\u00a7eUsed      \u00a78: \u00a7a" + usedMB + " MB");
        sender.sendMessage(PREFIX + "\u00a7eAllocated \u00a78: \u00a7f" + totalMB + " MB");
        sender.sendMessage(PREFIX + "\u00a7eMax (-Xmx)\u00a78: \u00a7f" + maxMB + " MB");
        sender.sendMessage(PREFIX + buildBar(pct, 20) + " \u00a7f" + pct + "%");
        sender.sendMessage("");
    }

    // ── /j2k help ─────────────────────────────────────────────────────────────

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(PREFIX + "\u00a76\u00a7l=== Arclight J2K Commands ===");
        sender.sendMessage(PREFIX + "\u00a7f/j2k status  \u00a77- Full server status");
        sender.sendMessage(PREFIX + "\u00a7f/j2k version \u00a77- Fork version info");
        sender.sendMessage(PREFIX + "\u00a7f/j2k scan    \u00a77- Scan mod/plugin conflicts");
        sender.sendMessage(PREFIX + "\u00a7f/j2k memory  \u00a77- Memory usage");
        sender.sendMessage(PREFIX + "\u00a7f/j2k help    \u00a77- Show this help");
        sender.sendMessage("");
    }

    // ── Tab completion ────────────────────────────────────────────────────────

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args) {
        if (args.length == 1) {
            return Arrays.asList("status", "version", "scan", "memory", "help")
                .stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .toList();
        }
        return List.of();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Counts jar files in a server folder
    private static int countJarsInFolder(String folderName) {
        try {
            Path folder = Paths.get(folderName);
            if (!Files.exists(folder)) return 0;
            return (int) Files.list(folder)
                .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                .count();
        } catch (IOException e) {
            return -1;
        }
    }

    // Returns list of jar file names (without .jar extension) from a folder
    private static List<String> getJarNames(String folderName) {
        List<String> result = new ArrayList<>();
        try {
            Path folder = Paths.get(folderName);
            if (!Files.exists(folder)) return result;
            Files.list(folder)
                .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    result.add(name.substring(0, name.length() - 4));
                });
        } catch (IOException ignored) {}
        return result;
    }

    // Builds a colored progress bar
    private static String buildBar(int pct, int width) {
        int filled = (int) (pct / 100.0 * width);
        String color = pct < 60 ? "\u00a7a" : pct < 85 ? "\u00a7e" : "\u00a7c";
        StringBuilder bar = new StringBuilder("\u00a78[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? color + "|" : "\u00a78|");
        }
        bar.append("\u00a78]");
        return bar.toString();
    }

    // Formats uptime seconds to human-readable string
    private static String formatUptime(long seconds) {
        long hours   = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs    = seconds % 60;
        if (hours > 0)   return hours + "h " + minutes + "m " + secs + "s";
        if (minutes > 0) return minutes + "m " + secs + "s";
        return secs + "s";
    }

    // Shortens Java vendor name for display
    private static String shortenVendor(String vendor) {
        if (vendor.contains("Adoptium") || vendor.contains("Eclipse")) return "Adoptium";
        if (vendor.contains("Oracle"))    return "Oracle";
        if (vendor.contains("Amazon"))    return "Corretto";
        if (vendor.contains("Microsoft")) return "Microsoft";
        if (vendor.contains("BellSoft"))  return "Liberica";
        if (vendor.contains("Azul"))      return "Azul";
        return vendor.split(" ")[0];
    }
}