package io.izzel.arclight.boot.diagnostic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Arclight J2K — Enhanced startup diagnostics.
 *
 * <p>Provides two diagnostic output methods:</p>
 * <ul>
 *   <li>{@link #printEarlyDiagnostics} — prints a compact banner before mods
 *       and plugins are loaded (called from the bootstrap)</li>
 *   <li>{@link #printDiagnostics} — prints full stats after the server has
 *       started (mods, plugins, boot time, RAM usage)</li>
 * </ul>
 */
public final class StartupDiagnostics {

    private StartupDiagnostics() {}

    private static final Logger LOGGER = LogManager.getLogger("Arclight-J2K");

    /**
     * JVM start time in milliseconds since epoch.
     * Captured once at class initialization to measure total boot time accurately.
     */
    private static final long JVM_START_TIME =
        ManagementFactory.getRuntimeMXBean().getStartTime();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Prints a startup banner with basic platform and hardware information.
     * Called early in the bootstrap before any mods or plugins are loaded.
     *
     * @param neoforgeVersion  the NeoForge version string
     * @param minecraftVersion the Minecraft version string
     */
    public static void printEarlyDiagnostics(
            String neoforgeVersion, String minecraftVersion) {
        try {
            String javaVersion = System.getProperty("java.version", "?");
            String javaVendor  = shortenVendor(System.getProperty("java.vendor", "?"));
            String osName      = System.getProperty("os.name", "?");
            String osArch      = System.getProperty("os.arch", "?");
            int    cpuCores    = Runtime.getRuntime().availableProcessors();
            long   maxRamMB    = Runtime.getRuntime().maxMemory() / 1024 / 1024;
            String forkVersion = readForkVersion();

            LOGGER.info("╔══════════════════════════════════════════════════╗");
            LOGGER.info("║        Arclight J2K  {}{}║",
                forkVersion,
                " ".repeat(Math.max(0, 28 - forkVersion.length()))
            );
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
     * Prints full server diagnostics after startup is complete.
     *
     * @param neoforgeVersion  the NeoForge version string
     * @param minecraftVersion the Minecraft version string
     * @param modCount         number of loaded mods (-1 if unknown)
     * @param pluginCount      number of loaded plugins (-1 if unknown)
     */
    public static void printDiagnostics(
            String neoforgeVersion, String minecraftVersion,
            int modCount, int pluginCount) {
        try {
            long   uptimeMs  = System.currentTimeMillis() - JVM_START_TIME;
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
            LOGGER.info(String.format("|  Mods       : %-37s|", modCount    >= 0 ? modCount    : "?"));
            LOGGER.info(String.format("|  Plugins    : %-37s|", pluginCount >= 0 ? pluginCount : "?"));
            LOGGER.info(String.format("|  Boot time  : %-37s|", String.format("%.1f sec", uptimeSec)));
            LOGGER.info("+====================================================+");
        } catch (Exception e) {
            LOGGER.warn("[Arclight-J2K] Could not print full diagnostics: {}", e.getMessage());
        }
    }

    // ── File counting ─────────────────────────────────────────────────────────

    /**
     * Counts {@code .jar} files in the {@code mods/} directory.
     *
     * @return the count, or -1 if the directory cannot be read
     */
    public static int countModFiles() {
        return countJars("mods");
    }

    /**
     * Counts {@code .jar} files in the {@code plugins/} directory.
     *
     * @return the count, or -1 if the directory cannot be read
     */
    public static int countPluginFiles() {
        return countJars("plugins");
    }

    /**
     * Counts {@code .jar} files in the given directory.
     * Uses try-with-resources to ensure the directory stream is always closed,
     * preventing file descriptor leaks on long-running servers.
     *
     * @param folder the folder name relative to the server working directory
     * @return the jar count, or -1 if the folder cannot be listed
     */
    private static int countJars(String folder) {
        Path dir = Paths.get(folder);
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream
                .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                .count();
        } catch (IOException e) {
            return -1;
        }
    }

    // ── Version detection ─────────────────────────────────────────────────────

    /**
     * Reads the Arclight J2K fork version from the jar manifest.
     *
     * <p>Strategy (in order):</p>
     * <ol>
     *   <li>Try the {@link java.security.ProtectionDomain} of this class to get
     *       the exact jar location and read its {@code MANIFEST.MF}.</li>
     *   <li>Fall back to the classloader's resource URL for {@code META-INF/MANIFEST.MF}.</li>
     *   <li>If both fail, return {@code "dev"}.</li>
     * </ol>
     *
     * @return the cleaned version string (e.g., {@code "1.21.1-1.0.2"}) or {@code "dev"}
     */
    private static String readForkVersion() {
        // Strategy 1: exact jar via ProtectionDomain
        try {
            var pd = StartupDiagnostics.class.getProtectionDomain();
            if (pd != null && pd.getCodeSource() != null
                    && pd.getCodeSource().getLocation() != null) {
                var location = pd.getCodeSource().getLocation();
                try (var jarStream = new JarInputStream(location.openStream())) {
                    Manifest mf = jarStream.getManifest();
                    if (mf != null) {
                        String ver = mf.getMainAttributes()
                            .getValue("Implementation-Version");
                        String cleaned = cleanVersion(ver);
                        if (cleaned != null) return cleaned;
                    }
                }
            }
        } catch (Exception ignored) {}

        // Strategy 2: classloader resource
        try {
            var url = StartupDiagnostics.class.getClassLoader()
                .getResource("META-INF/MANIFEST.MF");
            if (url != null) {
                try (var stream = url.openStream()) {
                    Manifest mf = new Manifest(stream);
                    String ver = mf.getMainAttributes().getValue("Implementation-Version");
                    String cleaned = cleanVersion(ver);
                    if (cleaned != null) return cleaned;
                }
            }
        } catch (Exception ignored) {}

        return "dev";
    }

    /**
     * Cleans an implementation version string to a short display form.
     * e.g., {@code "arclight-1.21.1-1.0.2-SNAPSHOT+abc123"} → {@code "1.21.1-1.0.2"}
     *
     * @param ver the raw version string from the manifest
     * @return the cleaned version, or {@code null} if the input is blank
     */
    private static String cleanVersion(String ver) {
        if (ver == null || ver.isBlank()) return null;
        return ver
            .replaceAll("^arclight-", "")
            .replaceAll("-SNAPSHOT.*$", "")
            .replaceAll("\\+.*$", "")
            .trim();
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    /**
     * Pads or truncates a string to exactly {@code width} characters for
     * aligned banner column display.
     */
    private static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    /**
     * Shortens a Java vendor name to a compact display form.
     * Falls back to the first word of the vendor string.
     */
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