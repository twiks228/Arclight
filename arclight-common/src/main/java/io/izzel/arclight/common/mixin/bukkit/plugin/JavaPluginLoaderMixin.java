package io.izzel.arclight.common.mixin.bukkit.plugin;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.izzel.arclight.common.bridge.bukkit.JavaPluginLoaderBridge;
import io.izzel.arclight.common.bridge.bukkit.PluginClassLoaderBridge;
import io.izzel.arclight.common.mod.server.ArclightServer;
import io.izzel.arclight.i18n.ArclightConfig;
import org.apache.commons.lang3.Validate;
import org.bukkit.Server;
import org.bukkit.Warning;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventException;
import org.bukkit.event.Listener;
import org.bukkit.plugin.AuthorNagException;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Mixin for {@link JavaPluginLoader} that fixes plugin event listener registration
 * for Java 21+ compatibility and adds class loader isolation support for hybrid servers.
 *
 * <p><b>Key fix (Java 21 hidden class compatibility):</b><br>
 * The original code used {@code MethodHandles.lookup().defineHiddenClass(...)}, where
 * the lookup context was Arclight's own class (in package
 * {@code io.izzel.arclight.common.mixin.bukkit.plugin}). Java 21 enforces that a
 * hidden class can only be defined in the same package as the lookup class, which
 * means Arclight's lookup cannot define hidden classes in third-party plugin packages
 * like {@code net.coreprotect.*} or {@code com.djrapitops.plan.*}.
 *
 * <p><b>Solution:</b><br>
 * Use {@link MethodHandles#privateLookupIn(Class, MethodHandles.Lookup)} with the
 * listener's declaring class to obtain a lookup scoped to the plugin's package.
 * If that fails (e.g., module access restrictions), fall back to a safe reflection-based
 * executor that still correctly invokes the listener method.
 */
@Mixin(value = JavaPluginLoader.class, remap = false)
public abstract class JavaPluginLoaderMixin implements JavaPluginLoaderBridge {

    // @formatter:off
    @Shadow @Final private Server server;
    @Invoker("setClass")        public abstract void bridge$setClass(String name, Class<?> clazz);
    @Invoker("getClassByName")  public abstract Class<?> arclight$getClassByName(String name, boolean resolve, PluginDescriptionFile desc);
    @Accessor("loaders")        public abstract <T extends URLClassLoader & PluginClassLoaderBridge> List<T> arclight$getLoaders();
    // @formatter:on

    // ── Static constants ──────────────────────────────────────────────────────

    /** Counter used to generate unique class names for ASM-generated executors. */
    @Unique
    private static final AtomicInteger ARCLIGHT$COUNTER = new AtomicInteger();

    /**
     * Cache of already-generated executor classes, keyed by the listener method.
     * Expires after 1 hour of inactivity to avoid long-term memory leaks.
     */
    @Unique
    private static final Cache<Method, EventExecutor> ARCLIGHT$EXECUTOR_CACHE =
        CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    /**
     * Arclight's own lookup — used ONLY for the fallback reflection executor
     * and for the initial {@link MethodHandles#privateLookupIn} call.
     * Must NOT be used directly to define hidden classes for plugin code.
     */
    @Unique
    private static final MethodHandles.Lookup ARCLIGHT$LOOKUP = MethodHandles.lookup();

    /**
     * ASM annotation descriptor for the JVM's {@code @Hidden} annotation.
     * Suppresses the generated executor class from stack traces, improving
     * readability for server administrators.
     *
     * <p>The correct descriptor depends on the Java class file version:
     * <ul>
     *   <li>Java 8 (class version 52.0): {@code LambdaForm$Hidden}</li>
     *   <li>Java 9+ (class version 53.0+): {@code jdk.internal.vm.annotation.Hidden}</li>
     * </ul>
     */
    @Unique
    private static final String ARCLIGHT$HIDDEN_ANNOTATION =
        Float.parseFloat(System.getProperty("java.class.version")) < 57.0f
            ? "Ljava/lang/invoke/LambdaForm$Hidden;"
            : "Ljdk/internal/vm/annotation/Hidden;";

    // ── PluginClassLoader constructor handle ──────────────────────────────────

    @Unique
    private MethodHandle arclight$mh_ctorPcl;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void arclight$initMH(Server instance, CallbackInfo ci) {
        try {
            Class<?> pcl = Class.forName(
                "org.bukkit.plugin.java.PluginClassLoader",
                true, getClass().getClassLoader()
            );
            arclight$mh_ctorPcl = MethodHandles.lookup().findConstructor(
                pcl,
                MethodType.methodType(
                    void.class,
                    String.class, JavaPluginLoader.class, ClassLoader.class,
                    PluginDescriptionFile.class, File.class, File.class, ClassLoader.class
                )
            );
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to initialize PluginClassLoader MethodHandle", e);
        }
    }

    @Redirect(
        method = "loadPlugin",
        at = @At(
            value = "NEW",
            target = "(Lorg/bukkit/plugin/java/JavaPluginLoader;" +
                     "Ljava/lang/ClassLoader;" +
                     "Lorg/bukkit/plugin/PluginDescriptionFile;" +
                     "Ljava/io/File;Ljava/io/File;Ljava/lang/ClassLoader;)" +
                     "Lorg/bukkit/plugin/java/PluginClassLoader;"
        )
    )
    @Coerce
    private Object arclight$redirectPclConstructor(
            JavaPluginLoader loader, ClassLoader parent,
            PluginDescriptionFile desc,
            File file, File file2, ClassLoader ex
    ) {
        try {
            return arclight$mh_ctorPcl.invoke(desc.getName(), loader, parent, desc, file, file2, ex);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke PluginClassLoader constructor", e);
        }
    }

    @Override
    public Server arclight$server() {
        return server;
    }

    // ── Class-by-name resolution ──────────────────────────────────────────────

    /**
     * @author InitAuther97
     * @reason Support plugin class loader isolation for hybrid NeoForge+Bukkit environments.
     *         When isolation is enabled, only classes from declared dependency plugins
     *         are visible to each plugin's class loader, preventing class leakage.
     */
    @Overwrite
    Class<?> getClassByName(
            String name, boolean resolve, PluginDescriptionFile description) {
        SimplePluginManager manager =
            (SimplePluginManager) this.server.getPluginManager();

        if (ArclightConfig.spec().getCompat().isIsolatedPluginClassLoaders(name)) {
            Set<String> loaders = ArclightServer.iterateDepends(description);
            for (PluginClassLoaderBridge loader : arclight$getLoaders()) {
                PluginDescriptionFile desc = loader.arclight$desc();
                if (loaders.contains(desc.getName())
                        || !Collections.disjoint(loaders, desc.getProvides())) {
                    try {
                        return loader.arclight$loadFromExternal(name, resolve, true);
                    } catch (ClassNotFoundException ignored) {}
                }
            }
        } else {
            for (PluginClassLoaderBridge loader : arclight$getLoaders()) {
                try {
                    return loader.arclight$loadFromExternal(
                        name, resolve,
                        manager.isTransitiveDepend(description, loader.arclight$desc())
                    );
                } catch (ClassNotFoundException ignored) {}
            }
        }
        return null;
    }

    // ── Event listener registration ───────────────────────────────────────────

    /**
     * @author IzzelAliz, Jake J2K
     * @reason Overwritten to use Java 21+ compatible hidden class event executors.
     *
     * <p>The core fix is in {@link #arclight$createExecutor}: we obtain a
     * {@link MethodHandles#privateLookupIn} scoped to the listener's declaring class
     * before calling {@code defineHiddenClass}. This satisfies Java 21's requirement
     * that hidden classes be defined in the same package as the lookup class.</p>
     *
     * <p>If {@code privateLookupIn} fails (e.g., due to module encapsulation), we
     * transparently fall back to a safe reflection-based executor so that plugins
     * still work correctly, even if slightly less efficiently.</p>
     */
    @Overwrite
    @NotNull
    public Map<Class<? extends Event>, Set<RegisteredListener>> createRegisteredListeners(
            @NotNull Listener listener, @NotNull Plugin plugin) {
        Validate.notNull(plugin, "Plugin can not be null");
        Validate.notNull(listener, "Listener can not be null");

        Map<Class<? extends Event>, Set<RegisteredListener>> ret = new HashMap<>();

        // Collect all public and declared methods — plugin event handlers can be
        // in any visibility level (common for internal listener classes)
        Set<Method> methods;
        try {
            Method[] publicMethods   = listener.getClass().getMethods();
            Method[] declaredMethods = listener.getClass().getDeclaredMethods();
            methods = new HashSet<>(publicMethods.length + declaredMethods.length, 1.0f);
            Collections.addAll(methods, publicMethods);
            Collections.addAll(methods, declaredMethods);
        } catch (NoClassDefFoundError e) {
            plugin.getLogger().severe(
                "Plugin " + plugin.getDescription().getFullName()
                + " has failed to register events for " + listener.getClass()
                + " because " + e.getMessage() + " does not exist."
            );
            return ret;
        }

        for (Method method : methods) {
            EventHandler eh = method.getAnnotation(EventHandler.class);
            if (eh == null) continue;

            // Skip bridge/synthetic methods to avoid duplicate event dispatch (SPIGOT-893)
            if (method.isBridge() || method.isSynthetic()) continue;

            Class<?> checkClass;
            if (method.getParameterTypes().length != 1
                    || !Event.class.isAssignableFrom(
                        checkClass = method.getParameterTypes()[0])) {
                plugin.getLogger().severe(
                    plugin.getDescription().getFullName()
                    + " attempted to register an invalid EventHandler method signature \""
                    + method.toGenericString() + "\" in " + listener.getClass()
                );
                continue;
            }

            final Class<? extends Event> eventClass = checkClass.asSubclass(Event.class);
            method.setAccessible(true);

            Set<RegisteredListener> eventSet =
                ret.computeIfAbsent(eventClass, k -> new HashSet<>());

            // Warn about deprecated events
            for (Class<?> clazz = eventClass;
                    Event.class.isAssignableFrom(clazz);
                    clazz = clazz.getSuperclass()) {
                if (clazz.getAnnotation(Deprecated.class) == null) continue;

                Warning warning    = clazz.getAnnotation(Warning.class);
                Warning.WarningState state = server.getWarningState();
                if (!state.printFor(warning)) break;

                String message = String.format(
                    "\"%s\" has registered a listener for %s on method \"%s\", "
                    + "but the event is Deprecated. \"%s\"; please notify the authors %s.",
                    plugin.getDescription().getFullName(),
                    clazz.getName(),
                    method.toGenericString(),
                    (warning != null && warning.reason().length() != 0)
                        ? warning.reason()
                        : "Server performance will be affected",
                    Arrays.toString(plugin.getDescription().getAuthors().toArray())
                );
                // Explicit cast to Throwable avoids ambiguous Logger.log() overloads
                Throwable nagEx = (state == Warning.WarningState.ON)
                    ? new AuthorNagException(null) : null;
                plugin.getLogger().log(Level.WARNING, message, (Throwable) nagEx);
                break;
            }

            try {
                EventExecutor executor = arclight$createExecutor(method, eventClass);
                eventSet.add(new RegisteredListener(
                    listener, executor, eh.priority(), plugin, eh.ignoreCancelled()
                ));
            } catch (Exception e) {
                plugin.getLogger().log(
                    Level.SEVERE,
                    "Failed to create event executor for method '"
                    + method.getName() + "' in listener '"
                    + listener.getClass().getName()
                    + "'. Plugin: " + plugin.getDescription().getFullName(),
                    e
                );
            }
        }
        return ret;
    }

    // ── ASM executor generation ───────────────────────────────────────────────

    /**
     * Creates or retrieves a cached {@link EventExecutor} for the given listener method.
     *
     * <p><b>Java 21 fix:</b> Uses {@link MethodHandles#privateLookupIn(Class, MethodHandles.Lookup)}
     * to obtain a lookup scoped to the listener's declaring class before calling
     * {@code defineHiddenClass}. This is required because Java 21 enforces that hidden
     * classes can only be defined in the same package as their lookup class.</p>
     *
     * <p><b>Fallback:</b> If {@code privateLookupIn} fails (e.g., the plugin class is
     * in an encapsulated module), a safe reflection-based executor is returned instead.
     * The plugin still works correctly; it just uses slightly slower reflection dispatch.</p>
     *
     * @param method     the {@code @EventHandler}-annotated method
     * @param eventClass the event class parameter of the method
     * @return a cached or newly generated {@link EventExecutor}
     */
    @Unique
    private EventExecutor arclight$createExecutor(
            Method method,
            Class<? extends Event> eventClass
    ) throws ExecutionException {
        return ARCLIGHT$EXECUTOR_CACHE.get(method, () -> {
            // ── Strategy 1: Java 21 compatible hidden class ───────────────────
            try {
                // KEY FIX: obtain a lookup IN THE PLUGIN'S CLASS/PACKAGE context.
                // Without this, defineHiddenClass fails on Java 21 with:
                //   "XYZ$$arclight$N not in same package as lookup class"
                MethodHandles.Lookup pluginLookup = MethodHandles.privateLookupIn(
                    method.getDeclaringClass(),
                    ARCLIGHT$LOOKUP
                );

                byte[] bytecode = arclight$generateExecutorBytecode(method, eventClass);

                MethodHandles.Lookup hiddenLookup = pluginLookup.defineHiddenClass(
                    bytecode, true,
                    MethodHandles.Lookup.ClassOption.NESTMATE
                );

                @SuppressWarnings("unchecked")
                Class<? extends EventExecutor> executorClass =
                    (Class<? extends EventExecutor>) hiddenLookup.lookupClass();

                Constructor<? extends EventExecutor> ctor =
                    executorClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();

            } catch (Exception asmEx) {
                // ASM / hidden class generation failed — log at debug level
                // (this is expected for some module-encapsulated plugins)
                // then fall through to the safe reflection executor
                ArclightServer.LOGGER.debug(
                    "[J2K] ASM executor unavailable for {}.{}() — using reflection fallback. Reason: {}",
                    method.getDeclaringClass().getSimpleName(),
                    method.getName(),
                    asmEx.getMessage()
                );
            }

            // ── Strategy 2: Safe reflection-based fallback ────────────────────
            // Functionally identical to the ASM executor — just slightly slower.
            // All plugins will work correctly with this executor.
            return arclight$reflectionExecutor(method, eventClass);
        });
    }

    /**
     * Creates a reflection-based {@link EventExecutor} as a fallback.
     *
     * <p>This executor is identical in behaviour to the ASM-generated one, but uses
     * {@link Method#invoke(Object, Object...)} instead of bytecode dispatch.
     * It is safe to use with any plugin on any Java version.</p>
     */
    @Unique
    private static EventExecutor arclight$reflectionExecutor(
            Method method, Class<? extends Event> eventClass) {
        return (listener, event) -> {
            if (!eventClass.isInstance(event)) return;
            try {
                method.invoke(listener, event);
            } catch (InvocationTargetException ex) {
                throw new EventException(ex.getCause());
            } catch (Exception ex) {
                throw new EventException(ex);
            }
        };
    }

    /**
     * Generates ASM bytecode for an {@link EventExecutor} that dispatches to the
     * given listener method. The generated class:
     * <ul>
     *   <li>Implements {@link EventExecutor}</li>
     *   <li>Checks that the incoming event is an instance of {@code eventClass}</li>
     *   <li>Casts and invokes the listener method directly (no reflection)</li>
     *   <li>Wraps any thrown {@link Throwable} in an {@link EventException}</li>
     * </ul>
     */
    @Unique
    private static byte[] arclight$generateExecutorBytecode(
            Method method, Class<? extends Event> eventClass) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String internalName = Type.getInternalName(method.getDeclaringClass())
            + "$$arclight$" + ARCLIGHT$COUNTER.getAndIncrement();

        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_FINAL,
            internalName, null,
            Type.getInternalName(Object.class),
            new String[]{ Type.getInternalName(EventExecutor.class) }
        );
        cw.visitOuterClass(Type.getInternalName(method.getDeclaringClass()), null, null);

        // Constructor: public <init>() { super(); }
        MethodVisitor ctor = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "()V", null, null
        );
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            Type.getInternalName(Object.class), "<init>", "()V", false
        );
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(-1, -1);
        ctor.visitEnd();

        // execute(Listener, Event) implementation
        arclight$generateExecuteMethod(cw, method, eventClass, internalName);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates the {@code execute(Listener, Event)} method body for the ASM executor.
     *
     * <p>Pseudo-code of the generated method:</p>
     * <pre>{@code
     * public void execute(Listener listener, Event event) throws EventException {
     *     if (!(event instanceof TargetEventClass)) return;
     *     try {
     *         ((ListenerOwnerClass) listener).theHandlerMethod((TargetEventClass) event);
     *     } catch (Throwable t) {
     *         throw new EventException(t);
     *     }
     * }
     * }</pre>
     */
    @Unique
    private static void arclight$generateExecuteMethod(
            ClassVisitor cv,
            Method method,
            Class<? extends Event> eventClass,
            String internalName
    ) {
        String ownerType  = Type.getInternalName(method.getDeclaringClass());
        String eventType  = Type.getInternalName(eventClass);
        String listenerDesc = Type.getDescriptor(Listener.class);
        String eventDesc    = Type.getDescriptor(Event.class);

        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC,
            "execute",
            "(" + listenerDesc + eventDesc + ")V",
            null, null
        );
        // Suppress from stack traces
        mv.visitAnnotation(ARCLIGHT$HIDDEN_ANNOTATION, true);

        Label tryStart   = new Label();
        Label tryEnd     = new Label();
        Label catchBlock = new Label();
        Label returnLabel = new Label();

        mv.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/Throwable");

        // if (!(event instanceof TargetEventClass)) return;
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, eventType);
        mv.visitJumpInsn(Opcodes.IFEQ, returnLabel);

        // try {
        mv.visitLabel(tryStart);

        // Determine invocation opcode based on method modifiers
        int invokeOpcode;
        boolean isInterface;
        if (Modifier.isStatic(method.getModifiers())) {
            invokeOpcode = Opcodes.INVOKESTATIC;
            isInterface  = false;
        } else if (method.getDeclaringClass().isInterface()) {
            invokeOpcode = Opcodes.INVOKEINTERFACE;
            isInterface  = true;
        } else {
            invokeOpcode = Opcodes.INVOKEVIRTUAL;
            isInterface  = false;
        }

        if (invokeOpcode != Opcodes.INVOKESTATIC) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, ownerType);
        }
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, eventType);

        mv.visitMethodInsn(
            invokeOpcode, ownerType,
            method.getName(), Type.getMethodDescriptor(method), isInterface
        );

        // Discard any return value from the handler method
        int retSize = Type.getType(method.getReturnType()).getSize();
        if (retSize == 1) mv.visitInsn(Opcodes.POP);
        else if (retSize == 2) mv.visitInsn(Opcodes.POP2);

        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, returnLabel);

        // } catch (Throwable t) { throw new EventException(t); }
        mv.visitLabel(catchBlock);
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{ "java/lang/Throwable" });
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitTypeInsn(Opcodes.NEW, "org/bukkit/event/EventException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "org/bukkit/event/EventException", "<init>", "(Ljava/lang/Throwable;)V", false
        );
        mv.visitInsn(Opcodes.ATHROW);

        mv.visitLabel(returnLabel);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitMaxs(-1, -1);
        mv.visitEnd();
    }
}