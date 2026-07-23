package io.izzel.arclight.installer;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class Mirrors {

    private static final String CUSTOM_REPOSITORIES_PROPERTY =
        "arclight.maven.repositories";

    private static final String CUSTOM_REPOSITORIES_ENVIRONMENT =
        "ARCLIGHT_MAVEN_REPOSITORIES";

    /*
     * Existing repositories are kept first for backward compatibility.
     */
    private static final String[] PRIMARY_MAVEN_REPOSITORIES = {
        "https://arclight.hypertention.cn/",
        "https://repo.spongepowered.org/maven/"
    };

    /*
     * Official repositories are used when the primary mirrors
     * cannot provide the requested artifact.
     */
    private static final String[] FALLBACK_MAVEN_REPOSITORIES = {
        "https://repo.maven.apache.org/maven2/",
        "https://maven.neoforged.net/releases/",
        "https://maven.izzel.io/releases/"
    };

    private static final String[] MOJANG_MIRRORS = {
        "https://mojmirror.hypertention.cn",
        "https://piston-meta.mojang.com"
    };

    private static final String VERSION_MANIFEST =
        "%s/mc/game/version_manifest.json";

    private Mirrors() {
    }

    public static String[] getMavenRepo() {
        Set<String> repositories = new LinkedHashSet<>();

        addRepositories(repositories, PRIMARY_MAVEN_REPOSITORIES);

        /*
         * Custom repositories are tried after the existing mirrors
         * and before the official fallback repositories.
         */
        addConfiguredRepositories(
            repositories,
            System.getProperty(CUSTOM_REPOSITORIES_PROPERTY)
        );

        addConfiguredRepositories(
            repositories,
            System.getenv(CUSTOM_REPOSITORIES_ENVIRONMENT)
        );

        addRepositories(repositories, FALLBACK_MAVEN_REPOSITORIES);

        return repositories.toArray(String[]::new);
    }

    public static List<Map.Entry<String, String>> getVersionManifest() {
        return Arrays.stream(MOJANG_MIRRORS)
            .map(mirror -> Map.entry(
                mirror,
                VERSION_MANIFEST.formatted(mirror)
            ))
            .collect(Collectors.toList());
    }

    public static String mapMojangMirror(String url, String mirror) {
        if (mirror.equals(MOJANG_MIRRORS[MOJANG_MIRRORS.length - 1])) {
            return url;
        }

        return url
            .replace("https://launcher.mojang.com", mirror)
            .replace("https://launchermeta.mojang.com", mirror)
            .replace("https://piston-meta.mojang.com", mirror)
            .replace("https://piston-data.mojang.com", mirror);
    }

    public static boolean isMirrorUrl(String url) {
        return url != null && url.startsWith(MOJANG_MIRRORS[0]);
    }

    private static void addRepositories(
        Set<String> repositories,
        String[] values
    ) {
        Arrays.stream(values)
            .map(Mirrors::normalizeRepository)
            .filter(repository -> !repository.isBlank())
            .forEach(repositories::add);
    }

    private static void addConfiguredRepositories(
        Set<String> repositories,
        String configuredValue
    ) {
        if (configuredValue == null || configuredValue.isBlank()) {
            return;
        }

        Arrays.stream(configuredValue.split("[,;\\r\\n]+"))
            .map(String::trim)
            .filter(repository -> !repository.isBlank())
            .map(Mirrors::normalizeRepository)
            .forEach(repositories::add);
    }

    private static String normalizeRepository(String repository) {
        String result = repository.trim();

        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }

        return result + "/";
    }
}