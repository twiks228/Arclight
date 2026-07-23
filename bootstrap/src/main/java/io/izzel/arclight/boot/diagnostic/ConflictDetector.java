package io.izzel.arclight.boot.diagnostic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Arclight J2K - Smart mod/plugin conflict detector.
 * Scans mods/ and plugins/ folders on startup and reports known conflicts.
 */
public class ConflictDetector {

    private static final Logger LOGGER = LogManager.getLogger("Arclight-J2K-Compat");

    // Known conflict rules: (modPattern, pluginPattern, description, severity)
    // null pattern means "not required for this side"
    private static final List<ConflictRule> KNOWN_CONFLICTS = List.of(
        new ConflictRule("worldedit", "WorldEdit",
            "WorldEdit found as both mod and plugin. Use only one.",
            Severity.WARNING),
        new ConflictRule("worldguard", null,
            "WorldGuard found in mods/ — this is a plugin, move it to plugins/",
            Severity.ERROR),
        new ConflictRule("optifine", null,
            "OptiFine is incompatible with NeoForge on server! Remove it from mods/",
            Severity.ERROR),
        new ConflictRule("iris", null,
            "Iris Shaders is a client-only mod, not needed on server",
            Severity.WARNING),
        new ConflictRule("sodium", null,
            "Sodium is a client-only mod, not needed on server",
            Severity.WARNING),
        new ConflictRule("worldedit", "FastAsyncWorldEdit",
            "WorldEdit (mod) + FastAsyncWorldEdit (plugin) may conflict",
            Severity.WARNING),
        new ConflictRule("luckperms", "LuckPerms",
            "LuckPerms found in both mods/ and plugins/! Use only one.",
            Severity.ERROR),
        new ConflictRule("viaversion", null,
            "ViaVersion found in mods/ — this is a plugin, move it to plugins/",
            Severity.ERROR),
        new ConflictRule("protocollib", null,
            "ProtocolLib found in mods/ — this is a plugin, move it to plugins/",
            Severity.ERROR)
    );

    /**
     * Runs the compatibility scan and prints results to log.
     * Called from ApplicationBootstrap during server startup.
     */
    public static ScanResult scan() {
        LOGGER.info("[Arclight-J2K] Running compatibility check...");

        List<ModInfo> mods    = scanFolder(Paths.get("mods"),    "mod");
        List<ModInfo> plugins = scanFolder(Paths.get("plugins"), "plugin");

        List<ConflictResult> conflicts = detectConflicts(mods, plugins);

        List<ConflictResult> errors   = new ArrayList<>();
        List<ConflictResult> warnings = new ArrayList<>();
        List<ConflictResult> infos    = new ArrayList<>();

        for (ConflictResult r : conflicts) {
            switch (r.severity()) {
                case ERROR   -> errors.add(r);
                case WARNING -> warnings.add(r);
                case INFO    -> infos.add(r);
            }
        }

        printResults(mods, plugins, errors, warnings, infos);

        return new ScanResult(mods, plugins, conflicts);
    }

    // Scans a folder and collects basic info from each jar file
    private static List<ModInfo> scanFolder(Path folder, String type) {
        List<ModInfo> result = new ArrayList<>();
        if (!Files.exists(folder)) return result;

        try {
            Files.list(folder)
                .filter(p -> p.toString().toLowerCase().endsWith(".jar"))
                .forEach(jarPath -> {
                    ModInfo info = readJarInfo(jarPath, type);
                    if (info != null) result.add(info);
                });
        } catch (Exception e) {
            LOGGER.warn("[Arclight-J2K] Error scanning {}: {}", folder, e.getMessage());
        }

        return result;
    }

    // Reads name and version from jar manifest, falls back to filename
    private static ModInfo readJarInfo(Path jarPath, String type) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            String fileName = jarPath.getFileName().toString();
            String baseName = fileName.substring(0, fileName.length() - 4);
            String name     = baseName;
            String version  = "unknown";

            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                var attrs = manifest.getMainAttributes();
                String implTitle   = attrs.getValue("Implementation-Title");
                String implVersion = attrs.getValue("Implementation-Version");
                if (implTitle   != null && !implTitle.isBlank())   name    = implTitle;
                if (implVersion != null && !implVersion.isBlank()) version = implVersion;
            }

            return new ModInfo(name, version, baseName.toLowerCase(), type, jarPath);

        } catch (Exception e) {
            String fileName = jarPath.getFileName().toString();
            String baseName = fileName.substring(0, fileName.length() - 4);
            return new ModInfo(baseName, "unknown", baseName.toLowerCase(), type, jarPath);
        }
    }

    // Checks all loaded jars against the known conflict rules
    private static List<ConflictResult> detectConflicts(
            List<ModInfo> mods, List<ModInfo> plugins) {
        List<ConflictResult> results = new ArrayList<>();

        for (ConflictRule rule : KNOWN_CONFLICTS) {
            Optional<ModInfo> matchedMod = Optional.empty();
            if (rule.modPattern() != null) {
                matchedMod = mods.stream()
                    .filter(m -> m.nameLower().contains(rule.modPattern().toLowerCase()))
                    .findFirst();
            }

            Optional<ModInfo> matchedPlugin = Optional.empty();
            if (rule.pluginPattern() != null) {
                matchedPlugin = plugins.stream()
                    .filter(p -> p.nameLower().contains(rule.pluginPattern().toLowerCase()))
                    .findFirst();
            }

            boolean triggered = false;

            if (rule.modPattern() != null && rule.pluginPattern() != null) {
                triggered = matchedMod.isPresent() && matchedPlugin.isPresent();
            } else if (rule.modPattern() != null) {
                triggered = matchedMod.isPresent();
            } else if (rule.pluginPattern() != null) {
                triggered = mods.stream()
                    .anyMatch(m -> m.nameLower()
                        .contains(rule.pluginPattern().toLowerCase()));
            }

            if (triggered) {
                String location = buildLocation(matchedMod, matchedPlugin);
                results.add(new ConflictResult(
                    rule.description(), rule.severity(), location));
            }
        }

        detectDuplicates(mods, plugins, results);
        return results;
    }

    // Looks for jars with similar names in both mods/ and plugins/
    private static void detectDuplicates(
            List<ModInfo> mods, List<ModInfo> plugins,
            List<ConflictResult> results) {
        for (ModInfo mod : mods) {
            for (ModInfo plugin : plugins) {
                String modKey    = mod.nameLower().replaceAll("[^a-z0-9]", "");
                String pluginKey = plugin.nameLower().replaceAll("[^a-z0-9]", "");

                if (modKey.length() > 6 && pluginKey.length() > 6) {
                    int len = Math.min(8, Math.min(modKey.length(), pluginKey.length()));
                    if (modKey.substring(0, len).equals(pluginKey.substring(0, len))) {
                        boolean alreadyFound = results.stream().anyMatch(r ->
                            r.description().toLowerCase()
                                .contains(modKey.substring(0, 4)));
                        if (!alreadyFound) {
                            results.add(new ConflictResult(
                                String.format("Possible duplicate: '%s' (mods/) and '%s' (plugins/)",
                                    mod.name(), plugin.name()),
                                Severity.WARNING,
                                mod.path() + " / " + plugin.path()
                            ));
                        }
                    }
                }
            }
        }
    }

    private static String buildLocation(
            Optional<ModInfo> mod, Optional<ModInfo> plugin) {
        StringBuilder sb = new StringBuilder();
        mod.ifPresent(m -> sb.append("mods/").append(m.path().getFileName()));
        if (mod.isPresent() && plugin.isPresent()) sb.append(" + ");
        plugin.ifPresent(p -> sb.append("plugins/").append(p.path().getFileName()));
        return sb.toString();
    }

    // Prints a formatted compatibility report to the log
    private static void printResults(
            List<ModInfo> mods,    List<ModInfo> plugins,
            List<ConflictResult> errors,
            List<ConflictResult> warnings,
            List<ConflictResult> infos) {

        LOGGER.info("+-----------------------------------------------+");
        LOGGER.info("|    Arclight J2K - Compatibility Report        |");
        LOGGER.info("+-----------------------------------------------+");
        LOGGER.info(String.format("|  Mods    (mods/)   : %-4d                      |", mods.size()));
        LOGGER.info(String.format("|  Plugins (plugins/): %-4d                      |", plugins.size()));
        LOGGER.info("+-----------------------------------------------+");

        if (errors.isEmpty() && warnings.isEmpty() && infos.isEmpty()) {
            LOGGER.info("|  OK: No conflicts detected. All good!         |");
        } else {
            for (ConflictResult r : errors) {
                LOGGER.error("  [ERROR] {}", r.description());
                if (!r.location().isBlank())
                    LOGGER.error("          File: {}", r.location());
            }
            for (ConflictResult r : warnings) {
                LOGGER.warn("  [WARN]  {}", r.description());
                if (!r.location().isBlank())
                    LOGGER.warn("          File: {}", r.location());
            }
            for (ConflictResult r : infos) {
                LOGGER.info("  [INFO]  {}", r.description());
            }
        }

        LOGGER.info("+-----------------------------------------------+");

        if (!errors.isEmpty()) {
            LOGGER.error("[Arclight-J2K] {} critical conflict(s) detected! Check log above.",
                errors.size());
        }
    }

    // ── Records ──────────────────────────────────────────────────────────────

    public record ModInfo(
        String name, String version, String nameLower,
        String type, Path path) {}

    public record ConflictRule(
        String modPattern, String pluginPattern,
        String description, Severity severity) {}

    public record ConflictResult(
        String description, Severity severity, String location) {}

    public record ScanResult(
        List<ModInfo> mods,
        List<ModInfo> plugins,
        List<ConflictResult> conflicts) {

        public boolean hasErrors() {
            return conflicts.stream().anyMatch(c -> c.severity() == Severity.ERROR);
        }
        public long errorCount() {
            return conflicts.stream().filter(c -> c.severity() == Severity.ERROR).count();
        }
        public long warningCount() {
            return conflicts.stream().filter(c -> c.severity() == Severity.WARNING).count();
        }
    }

    public enum Severity { INFO, WARNING, ERROR }
}