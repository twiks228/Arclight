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
import org.spongepowered.asm.mixin.*;
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

@Mixin(value = JavaPluginLoader.class, remap = false)
public abstract class JavaPluginLoaderMixin implements JavaPluginLoaderBridge {

    // @formatter:off
    @Shadow @Final private Server server;
    @Invoker("setClass") public abstract void bridge$setClass(final String name, final Class<?> clazz);
    @Invoker("getClassByName") public abstract Class<?> arclight$getClassByName(String name, boolean resolve, PluginDescriptionFile description);
    @Accessor("loaders") public abstract <T extends URLClassLoader & PluginClassLoaderBridge> List<T> arclight$getLoaders();
    // @formatter:on

    @Unique
    private MethodHandle arclight$mh_ctorPcl;
    
    @Unique
    private static final AtomicInteger COUNTER = new AtomicInteger();
    
    @Unique
    private static final Cache<Method, Class<? extends EventExecutor>> EXECUTOR_CACHE = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build();

    @Unique
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    @Unique
    private static final String HIDDEN_FORM =
        Float.parseFloat(System.getProperty("java.class.version")) < 57
            ? "Ljava/lang/invoke/LambdaForm$Hidden;"
            : "Ljdk/internal/vm/annotation/Hidden;";

    @Inject(method = "<init>", at = @At("RETURN"))
    private void arclight$initMH(Server instance, CallbackInfo ci) {
        try {
            Class<?> clz = Class.forName("org.bukkit.plugin.java.PluginClassLoader", true, getClass().getClassLoader());
            arclight$mh_ctorPcl = MethodHandles.lookup().findConstructor(
                clz, 
                MethodType.methodType(void.class, String.class, JavaPluginLoader.class, ClassLoader.class, PluginDescriptionFile.class, File.class, File.class, ClassLoader.class)
            );
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to initialize PluginClassLoader MethodHandle", e);
        }
    }

    @Redirect(method = "loadPlugin", at = @At(value = "NEW", target = "(Lorg/bukkit/plugin/java/JavaPluginLoader;Ljava/lang/ClassLoader;Lorg/bukkit/plugin/PluginDescriptionFile;Ljava/io/File;Ljava/io/File;Ljava/lang/ClassLoader;)Lorg/bukkit/plugin/java/PluginClassLoader;"))
    @Coerce
    private Object arclight$debug$redirectConstructor(JavaPluginLoader loader, ClassLoader parent, PluginDescriptionFile desc, File file, File file2, ClassLoader ex) {
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

    /**
     * @author InitAuther97
     * @reason Support plugin class loader isolation for hybrid environments
     */
    @Overwrite
    Class<?> getClassByName(String name, boolean resolve, PluginDescriptionFile description) {
        SimplePluginManager manager = (SimplePluginManager) this.server.getPluginManager();
        if (ArclightConfig.spec().getCompat().isIsolatedPluginClassLoaders(name)) {
            Set<String> loaders = ArclightServer.iterateDepends(description);
            for (PluginClassLoaderBridge loader : arclight$getLoaders()) {
                PluginDescriptionFile desc = loader.arclight$desc();
                if (loaders.contains(desc.getName()) || !Collections.disjoint(loaders, desc.getProvides())) {
                    try {
                        return loader.arclight$loadFromExternal(name, resolve, true);
                    } catch (ClassNotFoundException ignored) {
                        // Ignore and try next loader
                    }
                }
            }
        } else {
            for (PluginClassLoaderBridge loader : arclight$getLoaders()) {
                try {
                    return loader.arclight$loadFromExternal(name, resolve, manager.isTransitiveDepend(description, loader.arclight$desc()));
                } catch (ClassNotFoundException ignored) {
                    // Ignore and try next loader
                }
            }
        }
        return null;
    }

    /**
     * @author IzzelAliz, Jake J2K
     * @reason Use ASM event executor with Java 21+ compatible hidden classes and proper error logging.
     */
    @Overwrite
    @NotNull
    public Map<Class<? extends Event>, Set<RegisteredListener>> createRegisteredListeners(@NotNull Listener listener, @NotNull Plugin plugin) {
        Validate.notNull(plugin, "Plugin can not be null");
        Validate.notNull(listener, "Listener can not be null");

        Map<Class<? extends Event>, Set<RegisteredListener>> ret = new HashMap<>();
        Set<Method> methods;
        try {
            Method[] publicMethods = listener.getClass().getMethods();
            Method[] privateMethods = listener.getClass().getDeclaredMethods();
            methods = new HashSet<>(publicMethods.length + privateMethods.length, 1.0f);
            methods.addAll(Arrays.asList(publicMethods));
            methods.addAll(Arrays.asList(privateMethods));
        } catch (NoClassDefFoundError e) {
            plugin.getLogger().severe("Plugin " + plugin.getDescription().getFullName() + " has failed to register events for " + listener.getClass() + " because " + e.getMessage() + " does not exist.");
            return ret;
        }

        for (final Method method : methods) {
            final EventHandler eh = method.getAnnotation(EventHandler.class);
            if (eh == null) continue;
            
            // Do not register bridge or synthetic methods to avoid event duplication (Fixes SPIGOT-893)
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            
            final Class<?> checkClass;
            if (method.getParameterTypes().length != 1 || !Event.class.isAssignableFrom(checkClass = method.getParameterTypes()[0])) {
                plugin.getLogger().severe(plugin.getDescription().getFullName() + " attempted to register an invalid EventHandler method signature \"" + method.toGenericString() + "\" in " + listener.getClass());
                continue;
            }
            
            final Class<? extends Event> eventClass = checkClass.asSubclass(Event.class);
            method.setAccessible(true);
            
            Set<RegisteredListener> eventSet = ret.get(eventClass);
            if (eventSet == null) {
                eventSet = new HashSet<>();
                ret.put(eventClass, eventSet);
            }

            for (Class<?> clazz = eventClass; Event.class.isAssignableFrom(clazz); clazz = clazz.getSuperclass()) {
                if (clazz.getAnnotation(Deprecated.class) != null) {
                    Warning warning = clazz.getAnnotation(Warning.class);
                    Warning.WarningState warningState = server.getWarningState();
                    if (!warningState.printFor(warning)) {
                        break;
                    }
                    
                    String message = String.format(
                        "\"%s\" has registered a listener for %s on method \"%s\", but the event is Deprecated. \"%s\"; please notify the authors %s.",
                        plugin.getDescription().getFullName(),
                        clazz.getName(),
                        method.toGenericString(),
                        (warning != null && warning.reason().length() != 0) ? warning.reason() : "Server performance will be affected",
                        Arrays.toString(plugin.getDescription().getAuthors().toArray())
                    );
                    
                    // FIX: Explicitly typing as Throwable resolves the ambiguous method reference for Logger.log()
                    Throwable ex = warningState == Warning.WarningState.ON ? new AuthorNagException(null) : null;
                    plugin.getLogger().log(Level.WARNING, message, ex);
                    
                    break;
                }
            }

            try {
                Class<? extends EventExecutor> executorClass = createExecutor(method, eventClass);
                Constructor<? extends EventExecutor> constructor = executorClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                EventExecutor executor = constructor.newInstance();
                eventSet.add(new RegisteredListener(listener, executor, eh.priority(), plugin, eh.ignoreCancelled()));
            } catch (Exception e) { // Changed from Throwable to Exception to avoid catching Errors like OutOfMemory
                plugin.getLogger().log(
                    Level.SEVERE, 
                    "Failed to create ASM event executor for method '" + method.getName() + "' in listener '" + listener.getClass().getName() + "'. Plugin: " + plugin.getDescription().getFullName(), 
                    e
                );
            }
        }
        return ret;
    }

    @SuppressWarnings("unchecked")
    private Class<? extends EventExecutor> createExecutor(Method method, Class<? extends Event> eventClass) throws ExecutionException {
        return EXECUTOR_CACHE.get(method, () -> {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            String internalName = Type.getInternalName(method.getDeclaringClass()) + "$$arclight$" + COUNTER.getAndIncrement();
            
            cw.visit(
                Opcodes.V1_8, // V1_8 is kept for maximum bytecode compatibility across different plugin compilations
                Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_FINAL,
                internalName,
                null,
                Type.getInternalName(Object.class),
                new String[]{Type.getInternalName(EventExecutor.class)}
            );
            cw.visitOuterClass(Type.getInternalName(method.getDeclaringClass()), null, null);
            
            createConstructor(cw);
            createImpl(method, eventClass, cw);
            cw.visitEnd();

            // JAVA 21+ COMPATIBLE HIDDEN CLASS GENERATION
            MethodHandles.Lookup hiddenLookup = LOOKUP.defineHiddenClass(cw.toByteArray(), true, MethodHandles.Lookup.ClassOption.NESTMATE);
            return (Class<? extends EventExecutor>) hiddenLookup.lookupClass();
        });
    }

    private void createConstructor(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(Object.class), "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(-1, -1);
        mv.visitEnd();
    }

    private void createImpl(Method method, Class<? extends Event> eventClass, ClassVisitor cv) {
        String ownerType = Type.getInternalName(method.getDeclaringClass());
        MethodVisitor mv = cv.visitMethod(
            Opcodes.ACC_PUBLIC,
            "execute",
            Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(Listener.class), Type.getType(Event.class)),
            null, 
            null
        );
        mv.visitAnnotation(HIDDEN_FORM, true);

        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();
        mv.visitTryCatchBlock(label0, label1, label2, "java/lang/Throwable");
        
        Label label3 = new Label();
        Label label4 = new Label();
        
        mv.visitTryCatchBlock(label3, label4, label2, "java/lang/Throwable");
        
        mv.visitLabel(label0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName(eventClass));
        mv.visitJumpInsn(Opcodes.IFNE, label3);
        
        mv.visitLabel(label1);
        mv.visitInsn(Opcodes.RETURN);
        
        mv.visitLabel(label3);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        
        int invokeCode;
        if (Modifier.isStatic(method.getModifiers())) {
            invokeCode = Opcodes.INVOKESTATIC;
        } else if (method.getDeclaringClass().isInterface()) {
            invokeCode = Opcodes.INVOKEINTERFACE;
        } else {
            invokeCode = Opcodes.INVOKEVIRTUAL;
        }
        
        if (invokeCode != Opcodes.INVOKESTATIC) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, ownerType);
        }
        
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(eventClass));
        mv.visitMethodInsn(invokeCode, ownerType, method.getName(), Type.getMethodDescriptor(method), invokeCode == Opcodes.INVOKEINTERFACE);
        
        int retSize = Type.getType(method.getReturnType()).getSize();
        if (retSize > 0) {
            mv.visitInsn(Opcodes.POP + retSize - 1);
        }
        
        mv.visitLabel(label4);
        Label label5 = new Label();
        mv.visitJumpInsn(Opcodes.GOTO, label5);
        
        mv.visitLabel(label2);
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"});
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        
        mv.visitTypeInsn(Opcodes.NEW, "org/bukkit/event/EventException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/bukkit/event/EventException", "<init>", "(Ljava/lang/Throwable;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        
        mv.visitLabel(label5);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(Opcodes.RETURN);
        
        mv.visitMaxs(-1, -1);
        mv.visitEnd();
    }
}