package io.izzel.arclight.boot.neoforge.application;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.api.EnumHelper;
import io.izzel.arclight.api.Unsafe;
import io.izzel.arclight.boot.AbstractBootstrap;
import io.izzel.arclight.boot.diagnostic.ConflictDetector;
import io.izzel.arclight.boot.diagnostic.StartupDiagnostics;
import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.i18n.ArclightLocale;

import java.util.Arrays;
import java.util.ServiceLoader;
import java.util.function.Consumer;

public class ApplicationBootstrap implements Consumer<String[]>, AbstractBootstrap {

    private static final int MIN_DEPRECATED_VERSION = 60;
    private static final int MIN_DEPRECATED_JAVA_VERSION = 16;

    // Версии - берём из системных свойств которые ставит NeoForge/Arclight
    // Если не найдены - берём дефолтные значения из libs.versions.toml
    private static final String NEOFORGE_VERSION =
        System.getProperty("arclight.neoforge.version", "21.1.228");
    private static final String MINECRAFT_VERSION =
        System.getProperty("arclight.minecraft.version", "1.21.1");

    @Override
    @SuppressWarnings("unchecked")
    public void accept(String[] args) {
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
        System.setProperty("log4j.jul.LoggerAdapter", "io.izzel.arclight.boot.log.ArclightLoggerAdapter");
        System.setProperty("log4j.configurationFile", "arclight-log4j2.xml");
        ArclightLocale.info("i18n.using-language",
            ArclightConfig.spec().getLocale().getCurrent(),
            ArclightConfig.spec().getLocale().getFallback());

        try {
            int javaVersion = (int) Float.parseFloat(System.getProperty("java.class.version"));
            if (javaVersion < MIN_DEPRECATED_VERSION) {
                ArclightLocale.error("java.deprecated",
                    System.getProperty("java.version"), MIN_DEPRECATED_JAVA_VERSION);
                Thread.sleep(3000);
            }
            Unsafe.ensureClassInitialized(EnumHelper.class);
        } catch (Throwable t) {
            System.err.println("Your Java is not compatible with Arclight.");
            t.printStackTrace();
            return;
        }

        try {
            // 1. Стандартная инициализация Arclight (без изменений)
            this.setupMod(ArclightPlatform.NEOFORGE);
            this.dirtyHacks();

            // 2. ★ НОВОЕ: Ранняя диагностика системы (до загрузки модов)
            StartupDiagnostics.printEarlyDiagnostics(NEOFORGE_VERSION, MINECRAFT_VERSION);

            // 3. ★ НОВОЕ: Детектор конфликтов (сканирует mods/ и plugins/)
            ConflictDetector.ScanResult scanResult = ConflictDetector.scan();

            // Если есть критические ошибки - предупреждаем но не останавливаем сервер
            // (администратор сам решает продолжать или нет)
            if (scanResult.hasErrors()) {
                ArclightLocale.info("i18n.using-language"); // используем логгер
                System.err.println("[Arclight-J2K] ❌ Обнаружены критические конфликты (" +
                    scanResult.errorCount() + ")! Проверьте лог выше.");
                // Небольшая пауза чтобы администратор увидел предупреждение
                Thread.sleep(2000);
            }

            // 4. Стандартный запуск NeoForge (без изменений)
            int targetIndex = Arrays.asList(args).indexOf("--launchTarget");
            if (targetIndex >= 0 && targetIndex < args.length - 1) {
                args[targetIndex + 1] = "arclightserver";
            }
            ServiceLoader.load(getClass().getModule().getLayer(), Consumer.class).stream()
                    .filter(it -> !it.type().getName().contains("arclight"))
                    .findFirst().orElseThrow().get().accept(args);

            // 5. ★ НОВОЕ: Полная диагностика после загрузки всего
            // (запускаем в отдельном потоке с задержкой, чтобы Paper успел загрузить плагины)
            scheduleFullDiagnostics(scanResult);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Fail to launch Arclight.");
        }
    }

    /**
     * Запускает полную диагностику через 5 секунд после старта сервера.
     * К этому моменту Paper уже загрузил плагины и мы можем их посчитать.
     */
    private void scheduleFullDiagnostics(ConflictDetector.ScanResult scanResult) {
        Thread diagnosticsThread = new Thread(() -> {
            try {
                // Ждём пока сервер полностью запустится
                Thread.sleep(5000);

                // Считаем моды и плагины по файлам (простой вариант)
                int modCount = StartupDiagnostics.countModFiles();
                int pluginCount = StartupDiagnostics.countPluginFiles();

                StartupDiagnostics.printDiagnostics(
                    NEOFORGE_VERSION,
                    MINECRAFT_VERSION,
                    modCount,
                    pluginCount
                );

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Arclight-J2K-Diagnostics");

        // Daemon поток - не мешает остановке сервера
        diagnosticsThread.setDaemon(true);
        diagnosticsThread.start();
    }
}