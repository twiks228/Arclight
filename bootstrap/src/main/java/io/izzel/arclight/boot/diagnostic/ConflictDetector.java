package io.izzel.arclight.boot.diagnostic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

/**
 * Arclight J2K - Smart compatibility checker.
 *
 * Scans mods/ and plugins/ on startup:
 * - Reads neoforge.mods.toml to extract mod dependencies
 * - Checks required NeoForge version ranges
 * - Detects missing required mods
 * - Detects client-only mods on server
 * - Detects plugins placed in mods/ by mistake
 * - Detects duplicate jars across mods/ and plugins/
 */
public class ConflictDetector {

    private static final Logger LOGGER = LogManager.getLogger("Arclight-J2K-Compat");

    // Pattern for version range check: extract minimum version
    // e.g. "[21.1.234,)" → "21.1.234"
    private static final Pattern VERSION_RANGE_MIN =
        Pattern.compile("\\[([\\d.]+)");

    // ── Known rules ──────────────────────────────────────────────────────────

    // Client-only mods that should never be on a server
    private static final Set<String> CLIENT_ONLY_MODS = Set.of(
        "optifine", "iris", "sodium", "embeddium", "rubidium",
        "euphoria_patches", "bliss", "complementary"
    );

    // Plugin jar patterns that end up in mods/ by mistake
    private static final Set<String> PLUGIN_NAMES_IN_MODS = Set.of(
        "worldguard", "viaversion", "protocollib", "essentials",
        "luckperms", "coreprotect", "placeholderapi", "authme"
    );

    // ── Entry point ──────────────────────────────────────────────────────────

    /**
     * Runs the full compatibility scan.
     * Called from ApplicationBootstrap during startup.
     */
    public static ScanResult scan() {
        LOGGER.info("[Arclight-J2K] Running compatibility check...");

        String currentNeoForge = System.getProperty(
            "arclight.neoforge.version", "21.1.228");

        List<ModInfo> mods    = scanFolder(Paths.get("mods"));
        List<ModInfo> plugins = scanFolder(Paths.get("plugins"));

        List<Issue> issues = new ArrayList<>();

        // Check dependency requirements from mods.toml
        checkModDependencies(mods, currentNeoForge, issues);

        // Check for client-only mods
        checkClientOnlyMods(mods, issues);

        // Check for plugins in mods/ folder
        checkPluginsInModsFolder(mods, issues);

        // Check for suspicious jars in plugins/ that look like mods
        checkModsInPluginsFolder(plugins, issues);

        // Check for duplicate names across both folders
        checkDuplicates(mods, plugins, issues);

        printResults(mods, plugins, issues);

        return new ScanResult(mods, plugins, issues);
    }

    // ── Scanners ─────────────────────────────────────────────────────────────

    // Scans a folder and reads basic info from each jar
    private static List<ModInfo> scanFolder(Path folder) {
        List<ModInfo> result = new ArrayList<>();
        if (!Files.exists(folder)) return result;

        try {
            Files.list(folder)
                .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                .forEach(jarPath -> {
                    ModInfo info = readModInfo(jarPath);
                    if (info != null) result.add(info);
                });
        } catch (IOException e) {
            LOGGER.warn("[Arclight-J2K] Cannot scan {}: {}", folder, e.getMessage());
        }
        return result;
    }

    // Reads neoforge.mods.toml and manifest from a jar
    private static ModInfo readModInfo(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - 4);

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // Read display name from manifest
            String displayName = baseName;
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                String title = manifest.getMainAttributes().getValue("Implementation-Title");
                if (title != null && !title.isBlank()) displayName = title;
            }

            // Read neoforge.mods.toml for dependencies
            List<ModDependency> deps = new ArrayList<>();
            String modId = baseName.toLowerCase();

            JarEntry tomlEntry = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (tomlEntry == null) {
                tomlEntry = jar.getJarEntry("META-INF/mods.toml");
            }

            if (tomlEntry != null) {
                try (InputStream is = jar.getInputStream(tomlEntry)) {
                    String content = new String(
                        is.readAllBytes(), StandardCharsets.UTF_8);
                    modId = extractModId(content, modId);
                    deps  = extractDependencies(content);
                }
            }

            return new ModInfo(displayName, modId, baseName.toLowerCase(), jarPath, deps);

        } catch (Exception e) {
            return new ModInfo(baseName, baseName.toLowerCase(),
                baseName.toLowerCase(), jarPath, List.of());
        }
    }

    // ── TOML parsers (lightweight, no external library) ───────────────────────

    // Extracts modId from mods.toml content
    private static String extractModId(String toml, String fallback) {
        Pattern p = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(toml);
        if (m.find()) return m.group(1);
        return fallback;
    }

    // Extracts [[dependencies.*]] blocks from mods.toml
  private static List<ModDependency> extractDependencies(String toml) {
    List<ModDependency> result = new ArrayList<>();

    Pattern sectionPattern = Pattern.compile(
        "\\[\\[dependencies\\.[^]]+]]([^\\[]*)", Pattern.DOTALL);
    Matcher sectionMatcher = sectionPattern.matcher(toml);

    while (sectionMatcher.find()) {
        String block = sectionMatcher.group(1);

        Matcher modIdM = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(block);
        if (!modIdM.find()) continue;
        String depModId = modIdM.group(1);

        Matcher verM = Pattern.compile("versionRange\\s*=\\s*\"([^\"]+)\"").matcher(block);
        String versionRange = verM.find() ? verM.group(1) : "*";

        // Only include EXPLICITLY mandatory=true or dependencies without mandatory field
        // that are NOT common optional-compat patterns
        Matcher mandM = Pattern.compile("mandatory\\s*=\\s*(true|false)").matcher(block);
        boolean mandatory;
        if (mandM.find()) {
            mandatory = mandM.group(1).equals("true");
        } else {
            // No mandatory field - treat as mandatory only if it's neoforge/minecraft
            // Other deps without mandatory are likely optional compat
            mandatory = depModId.equals("neoforge") || depModId.equals("minecraft");
        }

        if (mandatory) {
            result.add(new ModDependency(depModId, versionRange));
        }
    }
    return result;
}
    // ── Checkers ──────────────────────────────────────────────────────────────

    // Checks each mod's declared dependencies against loaded mods and NeoForge version
    private static void checkModDependencies(
            List<ModInfo> mods, String currentNeoForge, List<Issue> issues) {

        Set<String> loadedModIds = new HashSet<>();
        for (ModInfo m : mods) loadedModIds.add(m.modId());

        for (ModInfo mod : mods) {
            for (ModDependency dep : mod.dependencies()) {

                if (dep.modId().equals("neoforge")) {
                    // Check NeoForge version range
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
                    // Missing required mod
                    issues.add(new Issue(Severity.WARNING,
                        String.format("Mod '%s' requires missing mod '%s'",
                            mod.displayName(), dep.modId()),
                        mod.path().getFileName().toString()
                    ));
                }
            }
        }
    }

    // Checks for client-only mods by jar name pattern
    private static void checkClientOnlyMods(List<ModInfo> mods, List<Issue> issues) {
        for (ModInfo mod : mods) {
            for (String pattern : CLIENT_ONLY_MODS) {
                if (mod.nameLower().contains(pattern)) {
                    issues.add(new Issue(Severity.WARNING,
                        "Client-only mod on server: " + mod.displayName()
                            + " — not needed on server",
                        mod.path().getFileName().toString()
                    ));
                }
            }
        }
    }

    // Checks for jars in mods/ that are actually Bukkit plugins by name
    private static void checkPluginsInModsFolder(List<ModInfo> mods, List<Issue> issues) {
        for (ModInfo mod : mods) {
            for (String pattern : PLUGIN_NAMES_IN_MODS) {
                if (mod.nameLower().contains(pattern)) {
                    issues.add(new Issue(Severity.ERROR,
                        "Plugin in mods/ folder: " + mod.displayName()
                            + " — move it to plugins/",
                        mod.path().getFileName().toString()
                    ));
                }
            }
        }
    }

    // Checks for jars in plugins/ that look like NeoForge mods
    private static void checkModsInPluginsFolder(List<ModInfo> plugins, List<Issue> issues) {
        for (ModInfo plugin : plugins) {
            String name = plugin.nameLower();
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

    // Checks for jars with similar names in both mods/ and plugins/
    private static void checkDuplicates(
            List<ModInfo> mods, List<ModInfo> plugins, List<Issue> issues) {
        for (ModInfo mod : mods) {
            for (ModInfo plugin : plugins) {
                String mk = mod.nameLower().replaceAll("[^a-z0-9]", "");
                String pk = plugin.nameLower().replaceAll("[^a-z0-9]", "");
                if (mk.length() < 5 || pk.length() < 5) continue;
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

    // ── Version range checker ─────────────────────────────────────────────────

    // Checks if a version string satisfies a Maven version range.
    // Supports: [x,), [x,y), *, any
    private static boolean versionInRange(String version, String range) {
        if (range == null || range.isBlank() || range.equals("*")
                || range.equalsIgnoreCase("any")) {
            return true;
        }
        try {
            Matcher minMatcher = VERSION_RANGE_MIN.matcher(range);
            if (minMatcher.find()) {
                String minVer = minMatcher.group(1);
                return compareVersions(version, minVer) >= 0;
            }
        } catch (Exception ignored) {}
        return true; // If we cannot parse range, assume ok
    }

    // Simple numeric version comparison: "21.1.228" vs "21.1.234"
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
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    // ── Output ────────────────────────────────────────────────────────────────

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
            LOGGER.info("|  OK: No conflicts detected. All good!         |");
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
            LOGGER.error("[Arclight-J2K] {} critical issue(s) found! Server may not start correctly.", errors);
        }
        if (warnings > 0) {
            LOGGER.warn("[Arclight-J2K] {} warning(s) found. Check compatibility.", warnings);
        }
        if (errors == 0 && warnings == 0) {
            LOGGER.info("[Arclight-J2K] All checks passed.");
        }
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record ModDependency(String modId, String versionRange) {}

    public record ModInfo(
        String displayName,
        String modId,
        String nameLower,
        Path path,
        List<ModDependency> dependencies
    ) {}

    public record Issue(
        Severity severity,
        String description,
        String file
    ) {}

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

    public enum Severity { ERROR, WARNING, INFO }
}