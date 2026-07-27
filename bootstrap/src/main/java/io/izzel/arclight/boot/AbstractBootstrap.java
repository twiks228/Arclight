package io.izzel.arclight.boot;

import com.google.gson.internal.bind.TypeAdapters;
import com.google.gson.reflect.TypeToken;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.api.ArclightVersion;
import io.izzel.arclight.api.Unsafe;
import io.izzel.arclight.i18n.ArclightLocale;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

/**
 * Base interface for Arclight's platform-specific bootstrap implementations.
 *
 * <p>Provides two critical startup hacks that must run before any NMS or
 * Bukkit classes are loaded:</p>
 *
 * <ol>
 *   <li><b>Gson enum hack</b> ({@link #dirtyHacks}): Replaces Gson's built-in
 *       enum type adapter factory with {@link EnumTypeFactory}, which handles
 *       dynamically added enum constants without throwing {@link AssertionError}.
 *       Arclight adds new constants to Bukkit enums (Materials, EntityType, etc.)
 *       at runtime to support modded content.</li>
 *
 *   <li><b>CommandNode hack</b> ({@link #dirtyHacks}): Adds a
 *       {@code CURRENT_COMMAND} static field and a {@code removeCommand} method
 *       to Brigadier's {@code CommandNode} class via bytecode manipulation.
 *       These are used by {@link io.izzel.arclight.common.mod.compat.CommandNodeHooks}
 *       to track and remove commands at runtime. A cleaner Mixin-based version
 *       exists for Sinytra Connector compatibility.</li>
 * </ol>
 */
public interface AbstractBootstrap {

    /**
     * Applies the Gson enum hack and the Brigadier CommandNode hack.
     *
     * <p><b>Gson hack:</b> Replaces the private {@code ENUM_FACTORY} static field
     * in {@link TypeAdapters} with an instance of {@link EnumTypeFactory} using
     * {@link Unsafe} to bypass field accessibility restrictions.</p>
     *
     * <p><b>CommandNode hack:</b> Loads {@code CommandNode.class} bytes, adds a
     * public static volatile {@code CURRENT_COMMAND} field, injects assignments
     * around the {@code Predicate.test()} call in {@code canUse()}, and adds a
     * {@code removeCommand(String)} method that removes from all three internal maps.
     * The modified class is defined in the MC bootstrap class loader so all code
     * shares the same modified version.</p>
     *
     * @throws Exception if any reflection or bytecode operation fails
     */
    @SuppressWarnings("JavadocReference")
    default void dirtyHacks() throws Exception {
        // ── Gson enum hack ─────────────────────────────────────────────────────
        // Trigger lazy initialization of the ENUM_FACTORY field so it's not null
        TypeAdapters.ENUM_FACTORY.create(null, TypeToken.get(Object.class));

        Field enumFactoryField = TypeAdapters.class.getDeclaredField("ENUM_FACTORY");
        Object base   = Unsafe.staticFieldBase(enumFactoryField);
        long   offset = Unsafe.staticFieldOffset(enumFactoryField);
        // Volatile write — immediately visible to all threads
        Unsafe.putObjectVolatile(base, offset, new EnumTypeFactory());

        // ── CommandNode bytecode hack ──────────────────────────────────────────
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("com/mojang/brigadier/tree/CommandNode.class")) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);

            // Step 1: Add public static volatile CommandNode CURRENT_COMMAND
            FieldNode currentCommandField = new FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                "CURRENT_COMMAND",
                "Lcom/mojang/brigadier/tree/CommandNode;",
                null, null
            );
            node.fields.add(currentCommandField);

            // Step 2: In canUse(), inject CURRENT_COMMAND = this before the predicate
            // call and CURRENT_COMMAND = null after it
            for (MethodNode method : node.methods) {
                if (!"canUse".equals(method.name)) continue;
                for (AbstractInsnNode insn : method.instructions) {
                    int opcode = insn.getOpcode();
                    if (opcode != Opcodes.INVOKEINTERFACE && opcode != Opcodes.INVOKEVIRTUAL) {
                        continue;
                    }
                    // Before: CURRENT_COMMAND = this
                    InsnList assign = new InsnList();
                    assign.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    assign.add(new FieldInsnNode(Opcodes.PUTSTATIC,
                        "com/mojang/brigadier/tree/CommandNode",
                        currentCommandField.name,
                        currentCommandField.desc));
                    method.instructions.insertBefore(insn, assign);

                    // After: CURRENT_COMMAND = null
                    InsnList reset = new InsnList();
                    reset.add(new InsnNode(Opcodes.ACONST_NULL));
                    reset.add(new FieldInsnNode(Opcodes.PUTSTATIC,
                        "com/mojang/brigadier/tree/CommandNode",
                        currentCommandField.name,
                        currentCommandField.desc));
                    method.instructions.insert(insn, reset);

                    // Only patch the first matching call site
                    break;
                }
            }

            // Step 3: Add public void removeCommand(String command)
            // Removes the entry from all three internal Brigadier maps:
            // children, literals, arguments
            MethodNode removeCommand = new MethodNode();
            removeCommand.access = Opcodes.ACC_PUBLIC;
            removeCommand.name   = "removeCommand";
            removeCommand.desc   = Type.getMethodDescriptor(
                Type.VOID_TYPE, Type.getType(String.class));

            String owner       = "com/mojang/brigadier/tree/CommandNode";
            String mapDesc     = Type.getDescriptor(Map.class);
            String removeDesc  = "(Ljava/lang/Object;)Ljava/lang/Object;";
            String mapInternal = Type.getInternalName(Map.class);

            // this.children.remove(command)
            removeCommand.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            removeCommand.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "children", mapDesc));
            removeCommand.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            removeCommand.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, mapInternal, "remove", removeDesc, true));
            removeCommand.instructions.add(new InsnNode(Opcodes.POP));

            // this.literals.remove(command)
            removeCommand.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            removeCommand.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "literals", mapDesc));
            removeCommand.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            removeCommand.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, mapInternal, "remove", removeDesc, true));
            removeCommand.instructions.add(new InsnNode(Opcodes.POP));

            // this.arguments.remove(command)
            removeCommand.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            removeCommand.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "arguments", mapDesc));
            removeCommand.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            removeCommand.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, mapInternal, "remove", removeDesc, true));
            removeCommand.instructions.add(new InsnNode(Opcodes.POP));

            removeCommand.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(removeCommand);

            // Step 4: Write modified bytecode and define the class in the
            // MC bootstrap classloader so all code shares this patched version
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(cw);
            byte[] bytes = cw.toByteArray();
            Unsafe.defineClass(
                "com.mojang.brigadier.tree.CommandNode",
                bytes, 0, bytes.length,
                getClass().getClassLoader(),
                getClass().getProtectionDomain()
            );
        }
    }

    /**
     * Configures the Arclight version, platform, and extracts the common jar.
     *
     * @param platform the platform being bootstrapped
     * @throws Exception if the manifest cannot be read or extraction fails
     */
    default void setupMod(ArclightPlatform platform) throws Exception {
        setupMod(platform, true);
    }

    /**
     * Configures the Arclight version and platform.
     * Optionally extracts the common jar to {@code .arclight/mod_file/}.
     *
     * @param platform the platform being bootstrapped
     * @param extract  whether to extract the common jar
     * @throws Exception if the manifest cannot be read or extraction fails
     */
    default void setupMod(ArclightPlatform platform, boolean extract) throws Exception {
        ArclightVersion.setVersion(ArclightVersion.FEUDAL_KINGS);
        ArclightPlatform.setPlatform(platform);

        try (InputStream stream = getClass().getResourceAsStream("/META-INF/MANIFEST.MF")) {
            Manifest manifest = new Manifest(stream);
            Attributes attributes = manifest.getMainAttributes();
            String version = attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION);

            if (extract) {
                extract(getClass().getModule().getResourceAsStream("/common.jar"), version);
            }

            String buildTime = attributes.getValue("Implementation-Timestamp");
            LogManager.getLogger("Arclight").info(
                ArclightLocale.getInstance().get("logo"),
                ArclightLocale.getInstance().get(
                    "release-name." + ArclightVersion.current().getReleaseName()
                ),
                version, buildTime
            );
        }
    }

    /**
     * Extracts the common jar from the bootstrap jar to {@code .arclight/mod_file/}.
     *
     * <p>Extraction is skipped if the jar already exists at the expected path,
     * unless {@code arclight.alwaysExtract=true} is set (useful for development).</p>
     *
     * @param path    the input stream for the common jar resource
     * @param version the version string used to name the output file
     * @throws Exception if directory creation or file copy fails
     */
    private void extract(InputStream path, String version) throws Exception {
        System.setProperty("arclight.version", version);

        Path dir = Paths.get(".arclight", "mod_file");
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        Path mod = dir.resolve(version + ".jar");
        if (!Files.exists(mod) || Boolean.getBoolean("arclight.alwaysExtract")) {
            // Remove old versions before copying the new one to avoid stale jars
            for (Path old : Files.list(dir).collect(Collectors.toList())) {
                Files.delete(old);
            }
            Files.copy(path, mod);
        }
    }
}