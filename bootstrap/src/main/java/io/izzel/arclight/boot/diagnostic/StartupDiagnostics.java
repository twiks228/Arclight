package io.izzel.arclight.boot.diagnostic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.Manifest;

/**
 * Arclight J2K - Enhanced startup diagnostics.
 * Prints system and server information on startup.
 */
public class StartupDiagnostics {

    private static final Logger LOGGER = LogManager.getLogger("Arclight-J2K");

    private static final long JVM_START_TIME =
        ManagementFactory.getRuntimeMXBean().getStartTime();

    /**
     * Prints a short early diagnostic banner.
     * Called before mods and plugins are loaded.
     */
    public static void printEarlyDiagnostics(
            String neoforgeVersion, String minecraftVersion) {
        try {
            String javaVersion  = System.getProperty("java.version", "?");
            String javaVendor   = shortenVendor(System.getProperty("java.vendor", "?"));
            String osName       = System.getProperty("os.name", "?");
            String osArch       = System.getProperty("os.arch", "?");
            int    cpuCores     = Runtime.getRuntime().availableProcessors();
            long   maxRamMB     = Runtime.getRuntime().maxMemory() / 1024 / 1024;
            String forkVersion  = readForkVersion();

            LOGGER.info("╔══════════════════════════════════════════════════╗");
            LOGGER.info("║        Arclight J2K  {}{}║",
                forkVersion, " ".repeat(Math.max(0, 28 - forkVersion.length())));
            LOGGER.info("║        Hybrid core: NeoForge + Paper             ║");
            LOGGER.info("╠══════════════════════════════════════════════════╣");
            LOGGER.info("║  Minecraft  : {}", pad(minecraftVersion, 35) + "║");
            LOGGER.info("║  NeoForge   : {}", pad(neoforgeVersion, 35) + "║");
            LOGGER.info("║  Java       : {} ({})", javaVersion, javaVendor);
            LOGGER.info("║  System     : {} {}", osName, osArch);
            LOGGER.info("║  CPU Cores  : {}", cpuCores);
            LOGGER.info("║  RAM max    : {} MB", maxRamMB);
            LOGGER.info("╚══════════════════════════════════════════════════╝");
        } catch (Exception e) {
            LOGGER.warn("[Arclight-J2K] Could not print startup diagnostics: {}", e.getMessage());
        }
    }

    /**
     * Prints full diagnostics including mods, plugins, and uptime.
     * Called after server has fully started.
     */
    public static void printDiagnostics(
            String neoforgeVersion, String minecraftVersion,
            int modCount, int pluginCount) {
        try {
            long uptimeMs  = System.currentTimeMillis() - JVM_START_TIME;
            double uptimeSec = uptimeMs / 1000.0;

            long maxRamMB  = Runtime.getRuntime().maxMemory()   / 1024 / 1024;
            long totalMB   = Runtime.getRuntime().totalMemory() / 1024 / 1024;
            long usedRamMB = totalMB - Runtime.getRuntime().freeMemory() / 1024 / 1024;

            String javaVersion = System.getProperty("java.version", "?");
            String javaVendor  = shortenVendor(System.getProperty("java.vendor", "?"));
            String osName      = System.getProperty("os.name", "?");
            int    cpuCores    = Runtime.getRuntime().availableProcessors();

            LOGGER.info("+====================================================+");
            LOGGER.info("|          Arclight J2K — Startup Complete           |");
            LOGGER.info("+====================================================+");
            LOGGER.info(String.format("|  Minecraft  : %-37s|", minecraftVersion));
            LOGGER.info(String.format("|  NeoForge   : %-37s|", neoforgeVersion));
            LOGGER.info(String.format("|  Java       : %-37s|", javaVersion + " (" + javaVendor + ")"));
            LOGGER.info(String.format("|  System     : %-37s|", osName));
            LOGGER.info(String.format("|  CPU Cores  : %-37s|", cpuCores));
            LOGGER.info(String.format("|  RAM used   : %-37s|", usedRamMB + "/" + maxRamMB + " MB"));
            LOGGER.info("+----------------------------------------------------+");
            LOGGER.info(String.format("|  Mods       : %-37s|", modCount >= 0 ? modCount : "?"));
            LOGGER.info(String.format("|  Plugins    : %-37s|", pluginCount >= 0 ? pluginCount : "?"));
            LOGGER.info(String.format("|  Boot time  : %-37s|", String.format("%.1f sec", uptimeSec)));
            LOGGER.info("+====================================================+");
        } catch (Exception e) {
            LOGGER.warn("[Arclight-J2K] Could not print full diagnostics: {}", e.getMessage());
        }
    }

    // Counts jar files in a server folder (mods/ or plugins/)
    public static int countModFiles() {
        return countJars("mods");
    }

    public static int countPluginFiles() {
        return countJars("plugins");
    }

    private static int countJars(String folder) {
        try {
            Path dir = Paths.get(folder);
            if (!Files.exists(dir)) return 0;
            return (int) Files.list(dir)
                .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                .count();
        } catch (IOException e) {
            return -1;
        }
    }

    // Reads the fork version string from the jar MANIFEST.MF.
    // Cleans up the raw version string so it shows as "1.21.1-1.0.2" instead of garbage.
    private static String readForkVersion() {
        try {
            var url = StartupDiagnostics.class.getClassLoader()
                .getResource("META-INF/MANIFEST.MF");
            if (url != null) {
                try (var stream = url.openStream()) {
                    Manifest mf = new Manifest(stream);
                    String raw = mf.getMainAttributes().getValue("Implementation-Version");
                    if (raw != null && !raw.isBlank()) {
                        // "arclight-1.21.1-1.0.2-SNAPSHOT+abc123" -> "1.21.1-1.0.2"
                        return raw.replaceAll("^arclight-", "")
                                  .replaceAll("-SNAPSHOT.*$", "")
                                  .replaceAll("\\+.*$", "")
                                  .trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "dev";
    }

    // Pads or truncates a string for banner column alignment
    private static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    // Shortens Java vendor name for compact display
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