package io.izzel.arclight.boot.neoforge.mod;

import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import io.izzel.arclight.boot.asm.AsyncCatcher;
import io.izzel.arclight.boot.asm.EnumDefinalizer;
import io.izzel.arclight.boot.asm.Implementer;
import io.izzel.arclight.boot.asm.InventoryImplementer;
import io.izzel.arclight.boot.asm.LoggerTransformer;
import io.izzel.arclight.boot.asm.SwitchTableFixer;
import io.izzel.arclight.boot.log.ArclightI18nLogger;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Arclight's primary {@link ILaunchPluginService} for NeoForge.
 *
 * <p>This plugin is injected into NeoForge's modlauncher pipeline by
 * {@link ModBootstrap#injectLaunchPlugin()} and processes every class that
 * passes through the transformer pipeline.</p>
 *
 * <p>Registered transformers:</p>
 * <ul>
 *   <li>{@link InventoryImplementer} — adds Bukkit inventory bridge methods</li>
 *   <li>{@link SwitchTableFixer} — fixes switch table decompilation artifacts</li>
 *   <li>{@link AsyncCatcher} — injects thread-safety assertions</li>
 *   <li>{@link EnumDefinalizer} — removes {@code final} from enum value arrays
 *       to allow dynamic enum extension via {@link io.izzel.arclight.api.EnumHelper}</li>
 *   <li>{@link LoggerTransformer} — (optional) bridges java.util.logging to Log4j
 *       when the JUL LogManager is not already a Log4j bridge</li>
 * </ul>
 *
 * <p>Mixin-applied classes ({@code reason == "mixin"}) are explicitly excluded
 * from processing to avoid double-transformation.</p>
 */
public class ArclightImplementer implements ILaunchPluginService {

    static final Logger LOGGER = ArclightI18nLogger.getLogger("Implementer");

    /** Phases where we want to process classes (after mixin application). */
    private static final EnumSet<Phase> OH_YES_SIR = EnumSet.of(Phase.AFTER);

    /** Phases where we skip processing (e.g., empty classes or mixin-applied). */
    private static final EnumSet<Phase> NOT_TODAY = EnumSet.noneOf(Phase.class);

    /** Registry of active class transformers, keyed by audit identifier. */
    private final Map<String, Implementer> implementers = new HashMap<>();

    /**
     * Callback that receives audit trail data for each transformed class.
     * Set by the modlauncher framework; may be {@code null} if not configured.
     */
    private volatile Consumer<String[]> auditAcceptor;

    /** The transformer loader (class bytes source) provided by modlauncher. */
    private ITransformerLoader transformerLoader;

    /**
     * Whether to install the {@link LoggerTransformer}.
     * Only needed when the JUL LogManager is not already bridged to Log4j.
     */
    private final boolean installLoggerTransformer;

    public ArclightImplementer() {
        this(detectTransformLogger());
    }

    public ArclightImplementer(boolean installLoggerTransformer) {
        this.installLoggerTransformer = installLoggerTransformer;
    }

    /**
     * Detects whether the JUL LogManager needs to be bridged to Log4j.
     * If bridging is needed and {@code log4j.jul.LoggerAdapter} is not set,
     * configures Arclight's custom adapter.
     *
     * @return {@code true} if the {@link LoggerTransformer} should be installed
     */
    private static boolean detectTransformLogger() {
        boolean needsBridge = !(java.util.logging.LogManager.getLogManager()
            instanceof org.apache.logging.log4j.jul.LogManager);

        if (needsBridge && !System.getProperties().containsKey("log4j.jul.LoggerAdapter")) {
            System.setProperty(
                "log4j.jul.LoggerAdapter",
                "io.izzel.arclight.boot.log.ArclightLoggerAdapter"
            );
        }
        return needsBridge;
    }

    @Override
    public String name() {
        return "arclight_implementer";
    }

    /**
     * Called by modlauncher after the transformer class loader is constructed.
     *
     * <p>Wires Arclight's post-bootstrap package lookups, then registers all
     * active class transformers.</p>
     *
     * @param transformerLoader the class byte loader
     * @param specialPaths      named paths provided by the launcher (unused)
     */
    @Override
    public void initializeLaunch(
            ITransformerLoader transformerLoader, NamedPath[] specialPaths) {
        // Complete the deferred package wiring from ModBootstrap.load()
        ModBootstrap.postRun();

        this.transformerLoader = transformerLoader;

        // Register transformers
        implementers.put("inventory", new InventoryImplementer());
        implementers.put("switch",    SwitchTableFixer.INSTANCE);
        implementers.put("async",     AsyncCatcher.INSTANCE);
        implementers.put("enum",      new EnumDefinalizer());

        if (installLoggerTransformer) {
            implementers.put("logger", new LoggerTransformer());
        }
    }

    /**
     * Indicates which transformation phases this plugin handles.
     *
     * <p>We skip mixin-applied classes to avoid re-transforming classes that
     * Mixin has already processed. Empty classes are also skipped because
     * there is nothing to transform.</p>
     *
     * @param classType the class being considered
     * @param isEmpty   whether the class bytes are empty
     * @param reason    the transformation reason (e.g., {@code "mixin"})
     * @return the set of phases to process
     */
    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty, String reason) {
        if ("mixin".equals(reason) || isEmpty) {
            return NOT_TODAY;
        }
        return OH_YES_SIR;
    }

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        throw new IllegalStateException(
            "Outdated ModLauncher — 3-arg handlesClass must be used"
        );
    }

    @Override
    public void customAuditConsumer(String className, Consumer<String[]> auditDataAcceptor) {
        this.auditAcceptor = auditDataAcceptor;
    }

    /**
     * Applies all registered transformers to the given class.
     *
     * <p>Each transformer reports whether it modified the class. Transformers
     * that made changes are recorded in the audit trail for debugging.</p>
     *
     * @param phase     the current processing phase ({@link Phase#AFTER})
     * @param classNode the ASM class node (modified in-place)
     * @param classType the class type descriptor
     * @param reason    the transformation reason
     * @return {@code true} if at least one transformer modified the class
     */
    @Override
    public boolean processClass(
            Phase phase, ClassNode classNode, Type classType, String reason) {
        if ("mixin".equals(reason)) {
            return false;
        }

        List<String> modifiedBy = new ArrayList<>();
        for (Map.Entry<String, Implementer> entry : implementers.entrySet()) {
            if (entry.getValue().processClass(classNode)) {
                modifiedBy.add(entry.getKey());
            }
        }

        // Report which transformers modified this class to the audit system
        if (auditAcceptor != null && !modifiedBy.isEmpty()) {
            auditAcceptor.accept(new String[]{ String.join(",", modifiedBy) });
        }

        return !modifiedBy.isEmpty();
    }

    @Override
    public boolean processClass(Phase phase, ClassNode classNode, Type classType) {
        throw new IllegalStateException(
            "Outdated ModLauncher — 4-arg processClass must be used"
        );
    }
}