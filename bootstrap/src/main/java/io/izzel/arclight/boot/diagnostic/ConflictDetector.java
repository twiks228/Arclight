package io.izzel.arclight.boot.diagnostic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Arclight J2K — Smart compatibility checker.
 *
 * <p>Scans {@code mods/} and {@code plugins/} on startup to detect common
 * configuration problems. Produces a structured {@link ScanResult} that can
 * be used by the {@code /j2k scan} command and the startup log.</p>
 *
 * <p>Checks performed:</p>
 * <ul>
 *   <li>NeoForge version range requirements from {@code neoforge.mods.toml}</li>
 *   <li>Missing required mod dependencies</li>
 *   <li>Client-only mods placed on the server</li>
 *   <li>Bukkit plugins accidentally placed in {@code mods/}</li>
 *   <li>NeoForge mods accidentally placed in {@code plugins/}</li>
 *   <li>Duplicate jars present in both folders</li>
 * </ul>
 */
public final class ConflictDetector {

    private ConflictDetector() {}

    private static final Logger LOGGER = LogManager.getLogger("Arclight-J2K-Compat");

    /**
     * Pattern to extract the minimum version from a Maven version range.
     * Supports: {@code [21.1.234,)}, {@code [21.1,22)}, etc.
     */
    private static final Pattern VERSION_RANGE_MIN =
        Pattern.compile("\\[([\\d.]+)");

    // ── Known rule sets ───────────────────────────────────────────────────────

    /**
     * Jar name substrings that identify client-only mods.
     * These mods have no server-side functionality and should never be
     * placed in a dedicated server's {@code mods/} folder.
     */
    private static final Set<String> CLIENT_ONLY_MODS = Set.of(
        "optifine", "iris", "sodium", "embeddium", "rubidium",
        "euphoria_patches", "bliss", "complementary"
    );

    /**
     * Jar name substrings that identify Bukkit plugins commonly placed
     * in {@code mods/} by mistake. These should be in {@code plugins/}.
     */
    private static final Set<String> PLUGIN_NAMES_IN_MODS = Set.of(
        "worldguard", "viaversion", "protocollib", "essentials",
        "luckperms", "coreprotect", "placeholderapi", "authme"
    );

    /**
     * Multi-platform plugin names that may legitimately contain "neoforge"
     * or "fabric" in their filename while still being valid Bukkit plugins.
     */
    private static final Set<String> KNOWN_MULTI_PLATFORM_PLUGINS = Set.of(
        "tab", "plasmovoice", "captcha", "warning",
        "fabric-elytra", "interactivechat"
    );

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Runs the full compatibility scan and returns a {@link ScanResult}.
     * Called during server startup to detect configuration problems early.
     *
     * @return the scan result containing all detected issues
     */
    public static ScanResult scan() {
        LOGGER.info("[Arclight-J2K] Running compatibility check...");

        String currentNeoForge = System.getProperty(
    "arclight.neoforge.version", "21.1.236");

        List<ModInfo> mods    = scanFolder(Paths.get("mods"));
        List<ModInfo> plugins = scanFolder(Paths.get("plugins"));

        List<Issue> issues = new ArrayList<>();

        checkModDependencies(mods, currentNeoForge, issues);
        checkClientOnlyMods(mods, issues);
        checkPluginsInModsFolder(mods, issues);
        checkModsInPluginsFolder(plugins, issues);
        checkDuplicates(mods, plugins, issues);

        printResults(mods, plugins, issues);

        return new ScanResult(mods, plugins, issues);
    }

    // ── Folder scanner ────────────────────────────────────────────────────────

    /**
     * Scans a folder and reads mod metadata from each jar.
     * Uses try-with-resources to prevent file descriptor leaks.
     *
     * @param folder the folder to scan
     * @return a list of {@link ModInfo} for each jar found
     */
    private static List<ModInfo> scanFolder(Path folder) {
        if (!Files.exists(folder)) return List.of();
        List<ModInfo> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                  .forEach(jarPath -> {
                      ModInfo info = readModInfo(jarPath);
                      if (info != null) result.add(info);
                  });
        } catch (IOException e) {
            LOGGER.warn("[Arclight-J2K] Cannot scan {}: {}", folder, e.getMessage());
        }
        return result;
    }

    // ── Jar metadata reader ────────────────────────────────────────────────────

    /**
     * Reads mod metadata from a jar file, including display name, mod ID,
     * and declared dependencies from {@code neoforge.mods.toml}.
     *
     * @param jarPath the path to the jar file
     * @return the mod info, or {@code null} if the jar cannot be opened
     */
    private static ModInfo readModInfo(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - 4);

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // Try to get a human-readable display name from the manifest
            String displayName = baseName;
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                String title = manifest.getMainAttributes().getValue("Implementation-Title");
                if (title != null && !title.isBlank()) {
                    displayName = title;
                }
            }

            // Try to parse neoforge.mods.toml for dependency information
            List<ModDependency> deps = List.of();
            String modId = baseName.toLowerCase();

            JarEntry tomlEntry = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (tomlEntry == null) {
                tomlEntry = jar.getJarEntry("META-INF/mods.toml");
            }

            if (tomlEntry != null) {
                try (InputStream is = jar.getInputStream(tomlEntry)) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    modId = extractModId(content, modId);
                    deps  = extractDependencies(content);
                }
            }

            return new ModInfo(displayName, modId, baseName.toLowerCase(), jarPath, deps);

        } catch (Exception e) {
            // Return minimal info so the jar is still counted in the report
            return new ModInfo(baseName, baseName.toLowerCase(),
                baseName.toLowerCase(), jarPath, List.of());
        }
    }

    // ── TOML parsers ──────────────────────────────────────────────────────────

    /** Extracts {@code modId} from {@code mods.toml} content. */
    private static String extractModId(String toml, String fallback) {
        Matcher m = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(toml);
        return m.find() ? m.group(1) : fallback;
    }

    /**
     * Extracts mandatory {@code [[dependencies.*]]} blocks from {@code mods.toml}.
     *
     * <p>Only includes dependencies where {@code mandatory = true} is explicitly set,
     * or where the dependency is on {@code neoforge} or {@code minecraft} (which are
     * always mandatory even without the field). All other deps without the field are
     * treated as optional compatibility entries to reduce false positives.</p>
     */
    private static List<ModDependency> extractDependencies(String toml) {
        List<ModDependency> result = new ArrayList<>();

        Matcher sectionMatcher = Pattern.compile(
            "\\[\\[dependencies\\.[^]]+]]([^\\[]*)", Pattern.DOTALL
        ).matcher(toml);

        while (sectionMatcher.find()) {
            String block = sectionMatcher.group(1);

            Matcher modIdM = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(block);
            if (!modIdM.find()) continue;
            String depModId = modIdM.group(1);

            Matcher verM = Pattern.compile("versionRange\\s*=\\s*\"([^\"]+)\"").matcher(block);
            String versionRange = verM.find() ? verM.group(1) : "*";

            Matcher mandM = Pattern.compile("mandatory\\s*=\\s*(true|false)").matcher(block);
            boolean mandatory;
            if (mandM.find()) {
                mandatory = "true".equals(mandM.group(1));
            } else {
                // No field: assume mandatory only for core deps to reduce false positives
                mandatory = depModId.equals("neoforge") || depModId.equals("minecraft");
            }

            if (mandatory) {
                result.add(new ModDependency(depModId, versionRange));
            }
        }
        return result;
    }

    // ── Checkers ──────────────────────────────────────────────────────────────

    private static void checkModDependencies(
            List<ModInfo> mods, String currentNeoForge, List<Issue> issues) {
        Set<String> loadedModIds = new HashSet<>();
        for (ModInfo m : mods) loadedModIds.add(m.modId());

        for (ModInfo mod : mods) {
            for (ModDependency dep : mod.dependencies()) {
                if (dep.modId().equals("neoforge")) {
                    if (!versionInRange(currentNeoForge, dep.versionRange())) {
                        issues.add(new Issue(Severity.ERROR,
                            String.format("Mod '%s' requires NeoForge %s, current is %s",
                                mod.displayName(), dep.versionRange(), currentNeoForge),
                            mod.path().getFileName().toString()
                        ));
                    }
                } else if (!dep.modId().equals("minecraft")
                        && !dep.modId().equals("forge")
                        && !dep.modId().equals("fabricloader")
                        && !loadedModIds.contains(dep.modId())) {
                    issues.add(new Issue(Severity.WARNING,
                        String.format("Mod '%s' requires missing mod '%s'",
                            mod.displayName(), dep.modId()),
                        mod.path().getFileName().toString()
                    ));
                }
            }
        }
    }

    private static void checkClientOnlyMods(List<ModInfo> mods, List<Issue> issues) {
        for (ModInfo mod : mods) {
            for (String pattern : CLIENT_ONLY_MODS) {
                if (mod.nameLower().contains(pattern)) {
                    issues.add(new Issue(Severity.WARNING,
                        "Client-only mod on server: " + mod.displayName()
                            + " — not needed on server",
                        mod.path().getFileName().toString()
                    ));
                    break; // One warning per jar is enough
                }
            }
        }
    }

    private static void checkPluginsInModsFolder(List<ModInfo> mods, List<Issue> issues) {
        for (ModInfo mod : mods) {
            for (String pattern : PLUGIN_NAMES_IN_MODS) {
                if (mod.nameLower().contains(pattern)) {
                    issues.add(new Issue(Severity.ERROR,
                        "Plugin in mods/ folder: " + mod.displayName()
                            + " — move it to plugins/",
                        mod.path().getFileName().toString()
                    ));
                    break;
                }
            }
        }
    }

    private static void checkModsInPluginsFolder(List<ModInfo> plugins, List<Issue> issues) {
        for (ModInfo plugin : plugins) {
            String name = plugin.nameLower();

            boolean isKnownPlugin = KNOWN_MULTI_PLATFORM_PLUGINS.stream()
                .anyMatch(name::contains);
            if (isKnownPlugin) continue;

            if (name.contains("neoforge") || name.contains("-forge-")
                    || name.contains("fabric")) {
                issues.add(new Issue(Severity.WARNING,
                    "Possible mod in plugins/ folder: " + plugin.displayName()
                        + " — verify it is a Bukkit plugin",
                    plugin.path().getFileName().toString()
                ));
            }
        }
    }

    private static void checkDuplicates(
            List<ModInfo> mods, List<ModInfo> plugins, List<Issue> issues) {
        for (ModInfo mod : mods) {
            String mk = mod.nameLower().replaceAll("[^a-z0-9]", "");
            if (mk.length() < 5) continue;
            for (ModInfo plugin : plugins) {
                String pk = plugin.nameLower().replaceAll("[^a-z0-9]", "");
                if (pk.length() < 5) continue;
                int len = Math.min(6, Math.min(mk.length(), pk.length()));
                if (mk.substring(0, len).equals(pk.substring(0, len))) {
                    issues.add(new Issue(Severity.WARNING,
                        "Possible duplicate: mods/" + mod.path().getFileName()
                            + " and plugins/" + plugin.path().getFileName(),
                        ""
                    ));
                }
            }
        }
    }

    // ── Version comparison ────────────────────────────────────────────────────

    /**
     * Checks whether the given version satisfies the Maven version range.
     * Supports: {@code [x,)}, {@code [x,y)}, {@code *}, {@code any}.
     *
     * @param version the version to check
     * @param range   the Maven version range expression
     * @return {@code true} if the version is within the range
     */
    private static boolean versionInRange(String version, String range) {
        if (range == null || range.isBlank() || range.equals("*")
                || range.equalsIgnoreCase("any")) {
            return true;
        }
        try {
            Matcher minMatcher = VERSION_RANGE_MIN.matcher(range);
            if (minMatcher.find()) {
                return compareVersions(version, minMatcher.group(1)) >= 0;
            }
        } catch (Exception ignored) {}
        return true;
    }

    /**
     * Compares two dot/dash-separated version strings numerically.
     * Non-numeric segments are treated as 0.
     */
    private static int compareVersions(String a, String b) {
        String[] partsA = a.split("[.\\-]");
        String[] partsB = b.split("[.\\-]");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int pa = i < partsA.length ? parseIntSafe(partsA[i]) : 0;
            int pb = i < partsB.length ? parseIntSafe(partsB[i]) : 0;
            if (pa != pb) return Integer.compare(pa, pb);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── Report output ─────────────────────────────────────────────────────────

    private static void printResults(
            List<ModInfo> mods, List<ModInfo> plugins, List<Issue> issues) {
        long errors   = issues.stream().filter(i -> i.severity() == Severity.ERROR).count();
        long warnings = issues.stream().filter(i -> i.severity() == Severity.WARNING).count();

        LOGGER.info("+-----------------------------------------------+");
        LOGGER.info("|    Arclight J2K - Compatibility Report        |");
        LOGGER.info("+-----------------------------------------------+");
        LOGGER.info(String.format("|  Mods    (mods/)   : %-4d                      |", mods.size()));
        LOGGER.info(String.format("|  Plugins (plugins/): %-4d                      |", plugins.size()));
        LOGGER.info("+-----------------------------------------------+");

        if (issues.isEmpty()) {
            LOGGER.info("|  OK: No conflicts detected.                   |");
        } else {
            for (Issue issue : issues) {
                if (issue.severity() == Severity.ERROR) {
                    LOGGER.error("  [ERROR] {}", issue.description());
                    if (!issue.file().isBlank())
                        LOGGER.error("          File: {}", issue.file());
                } else {
                    LOGGER.warn("  [WARN]  {}", issue.description());
                    if (!issue.file().isBlank())
                        LOGGER.warn("          File: {}", issue.file());
                }
            }
        }

        LOGGER.info("+-----------------------------------------------+");

        if (errors > 0) {
            LOGGER.error("[Arclight-J2K] {} critical issue(s) detected! Server may malfunction.", errors);
        }
        if (warnings > 0) {
            LOGGER.warn("[Arclight-J2K] {} warning(s) found. Review compatibility.", warnings);
        }
        if (errors == 0 && warnings == 0) {
            LOGGER.info("[Arclight-J2K] All compatibility checks passed.");
        }
    }

    // ── Records ───────────────────────────────────────────────────────────────

    /** A declared mandatory dependency of a mod. */
    public record ModDependency(String modId, String versionRange) {}

    /** Metadata extracted from a jar in {@code mods/} or {@code plugins/}. */
    public record ModInfo(
        String displayName,
        String modId,
        String nameLower,
        Path path,
        List<ModDependency> dependencies
    ) {}

    /** A detected compatibility issue. */
    public record Issue(Severity severity, String description, String file) {}

    /** The complete result of a compatibility scan. */
    public record ScanResult(
        List<ModInfo> mods,
        List<ModInfo> plugins,
        List<Issue> issues
    ) {
        public boolean hasErrors() {
            return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
        }
        public long errorCount() {
            return issues.stream().filter(i -> i.severity() == Severity.ERROR).count();
        }
        public long warningCount() {
            return issues.stream().filter(i -> i.severity() == Severity.WARNING).count();
        }
    }

    /** Severity levels for compatibility issues. */
    public enum Severity { ERROR, WARNING, INFO }
}