package io.izzel.arclight.boot.diagnostic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Arclight J2K - Расширенная диагностика запуска
 * Выводит подробную информацию о системе и состоянии сервера при старте
 */
public class StartupDiagnostics {

    private static final Logger LOGGER = LogManager.getLogger("Arclight-J2K");

    // Время когда JVM была запущена - для подсчёта времени старта
    private static final long JVM_START_TIME = ManagementFactory.getRuntimeMXBean().getStartTime();

    /**
     * Вызывается сразу после того как NeoForge и Bukkit полностью загрузились.
     * Передаём версии снаружи потому что здесь нет прямого доступа к FML/Paper API.
     *
     * @param neoforgeVersion версия NeoForge (например "21.1.228")
     * @param minecraftVersion версия Minecraft (например "1.21.1")
     * @param modCount количество загруженных NeoForge модов
     * @param pluginCount количество загруженных Bukkit плагинов
     */
    public static void printDiagnostics(
            String neoforgeVersion,
            String minecraftVersion,
            int modCount,
            int pluginCount
    ) {
        try {
            // Собираем данные о системе
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

            String javaVersion = System.getProperty("java.version");
            String javaVendor = System.getProperty("java.vendor");
            String osName = System.getProperty("os.name");
            String osArch = System.getProperty("os.arch");
            int cpuCores = Runtime.getRuntime().availableProcessors();

            // RAM
            long maxMemoryBytes = Runtime.getRuntime().maxMemory();
            long totalMemoryBytes = Runtime.getRuntime().totalMemory();
            long usedMemoryBytes = totalMemoryBytes - Runtime.getRuntime().freeMemory();

            long maxMemoryMB = maxMemoryBytes / 1024 / 1024;
            long usedMemoryMB = usedMemoryBytes / 1024 / 1024;

            // Время старта сервера (с момента запуска JVM)
            long uptimeMs = System.currentTimeMillis() - JVM_START_TIME;
            double uptimeSec = uptimeMs / 1000.0;

            // Сокращаем vendor для красивого вывода
            String javaVendorShort = shortenVendor(javaVendor);

            // Версия нашего форка из манифеста
            String forklVersion = getForklVersion();

            // Строим красивый вывод
            List<String> lines = buildDiagnosticLines(
                forklVersion,
                minecraftVersion,
                neoforgeVersion,
                javaVersion,
                javaVendorShort,
                osName,
                osArch,
                cpuCores,
                maxMemoryMB,
                usedMemoryMB,
                modCount,
                pluginCount,
                uptimeSec
            );

            // Выводим через логгер
            for (String line : lines) {
                LOGGER.info(line);
            }

        } catch (Exception e) {
            // Диагностика не должна крашить сервер - просто логируем ошибку
            LOGGER.warn("Arclight-J2K: Не удалось вывести диагностику запуска: {}", e.getMessage());
        }
    }

    /**
     * Лёгкая версия диагностики - только системная информация.
     * Вызывается на раннем этапе старта, до загрузки модов/плагинов.
     */
    public static void printEarlyDiagnostics(String neoforgeVersion, String minecraftVersion) {
        try {
            String javaVersion = System.getProperty("java.version");
            String javaVendor = shortenVendor(System.getProperty("java.vendor"));
            String osName = System.getProperty("os.name");
            int cpuCores = Runtime.getRuntime().availableProcessors();
            long maxMemoryMB = Runtime.getRuntime().maxMemory() / 1024 / 1024;
            String forklVersion = getForklVersion();

            LOGGER.info("╔══════════════════════════════════════════════════╗");
            LOGGER.info("║        Arclight J2K  {}{}║",
                padRight(forklVersion, 28 - forklVersion.length()), "");
            LOGGER.info("║        Гибридное ядро: NeoForge + Paper          ║");
            LOGGER.info("╠══════════════════════════════════════════════════╣");
            LOGGER.info("║  Minecraft  : {}", padRight(minecraftVersion, 35) + "║");
            LOGGER.info("║  NeoForge   : {}", padRight(neoforgeVersion, 35) + "║");
            LOGGER.info("║  Java       : {} ({})", javaVersion, javaVendor);
            LOGGER.info("║  Система    : {} ({})", osName, System.getProperty("os.arch"));
            LOGGER.info("║  CPU Ядра   : {}", cpuCores);
            LOGGER.info("║  RAM (макс) : {} MB", maxMemoryMB);
            LOGGER.info("╚══════════════════════════════════════════════════╝");

        } catch (Exception e) {
            LOGGER.warn("Arclight-J2K: Ранняя диагностика недоступна: {}", e.getMessage());
        }
    }

    // ========== Приватные вспомогательные методы ==========

    private static List<String> buildDiagnosticLines(
            String forklVersion, String minecraft, String neoforge,
            String java, String javaVendor, String os, String arch,
            int cores, long maxRam, long usedRam,
            int mods, int plugins, double uptime
    ) {
        List<String> lines = new ArrayList<>();

        lines.add("╔══════════════════════════════════════════════════════╗");
        lines.add("║            ARCLIGHT J2K  —  Статус запуска           ║");
        lines.add("╠══════════════════════════════════════════════════════╣");
        lines.add(String.format("║  Версия форка : %-35s║", forklVersion));
        lines.add("╠══════════════════════════════════════════════════════╣");
        lines.add(String.format("║  Minecraft    : %-35s║", minecraft));
        lines.add(String.format("║  NeoForge     : %-35s║", neoforge));
        lines.add("╠══════════════════════════════════════════════════════╣");
        lines.add(String.format("║  Java         : %-35s║", java + " (" + javaVendor + ")"));
        lines.add(String.format("║  Система      : %-35s║", os + " " + arch));
        lines.add(String.format("║  CPU Ядра     : %-35s║", cores));
        lines.add(String.format("║  RAM макс     : %-35s║", maxRam + " MB"));
        lines.add(String.format("║  RAM исп      : %-35s║", usedRam + " MB"));
        lines.add("╠══════════════════════════════════════════════════════╣");
        lines.add(String.format("║  Моды (Neo)   : %-35s║", mods));
        lines.add(String.format("║  Плагины      : %-35s║", plugins));
        lines.add(String.format("║  Время старта : %-35s║", String.format("%.1f сек", uptime)));
        lines.add("╚══════════════════════════════════════════════════════╝");

        return lines;
    }

    /**
 * Reads the fork version from the jar MANIFEST.MF.
 * Falls back to "dev" if the manifest is not found or version is missing.
 */
private static String getForklVersion() {
    try {
        var manifestUrl = StartupDiagnostics.class
                .getClassLoader()
                .getResource("META-INF/MANIFEST.MF");
        if (manifestUrl != null) {
            try (var stream = manifestUrl.openStream()) {
                Manifest manifest = new Manifest(stream);
                String ver = manifest.getMainAttributes()
                        .getValue("Implementation-Version");
                if (ver != null && !ver.isBlank()) {
                    // Clean up: "arclight-1.21.1-1.0.2-SNAPSHOT+abc" → "1.21.1-1.0.2"
                    ver = ver.replaceAll("arclight-", "")
                             .replaceAll("\\+[0-9a-f]+$", "")
                             .replaceAll("-SNAPSHOT$", "")
                             .trim();
                    return "v" + ver;
                }
            }
        }
    } catch (Exception ignored) {}
    return "dev";
}

    /**
     * Сокращает название vendor Java для компактного вывода.
     * "Eclipse Adoptium" → "Adoptium"
     * "Oracle Corporation" → "Oracle"
     */
    private static String shortenVendor(String vendor) {
        if (vendor == null) return "Unknown";
        if (vendor.contains("Adoptium")) return "Adoptium";
        if (vendor.contains("Eclipse")) return "Eclipse";
        if (vendor.contains("Oracle")) return "Oracle";
        if (vendor.contains("Amazon")) return "Amazon Corretto";
        if (vendor.contains("Microsoft")) return "Microsoft";
        if (vendor.contains("BellSoft")) return "Liberica";
        if (vendor.contains("Azul")) return "Azul Zulu";
        if (vendor.contains("GraalVM")) return "GraalVM";
        // Если ничего не подошло - берём первое слово
        return vendor.split(" ")[0];
    }

    private static String padRight(String s, int n) {
        if (s == null) s = "";
        if (s.length() >= n) return s.substring(0, n);
        return s + " ".repeat(n - s.length());
    }

    /**
     * Подсчитывает количество jar-файлов в папке mods.
     * Используется как запасной вариант если FML API недоступен.
     */
    public static int countModFiles() {
        try {
            Path modsDir = Paths.get("mods");
            if (!Files.exists(modsDir)) return 0;
            return (int) Files.list(modsDir)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .count();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Подсчитывает количество jar-файлов в папке plugins.
     * Используется как запасной вариант если Bukkit API недоступен.
     */
    public static int countPluginFiles() {
        try {
            Path pluginsDir = Paths.get("plugins");
            if (!Files.exists(pluginsDir)) return 0;
            return (int) Files.list(pluginsDir)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .count();
        } catch (Exception e) {
            return -1;
        }
    }
}