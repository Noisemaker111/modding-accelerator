import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.function.Consumer;
import org.objectweb.asm.*;

/**
 * Applies personal launch preferences before the client opens windows or reads settings.
 * What the hidden-window transform did is left in two string system properties, so the agent's
 * status (and mmt) can tell a hidden window from one that failed to hide:
 * mmt.background.applied lists each window library rewritten, mmt.background.error the failure.
 */
final class GameLaunchAgent {
    private GameLaunchAgent() {}
    private static boolean installed;
    static synchronized void install(Instrumentation instrumentation) {
        // Installed once per JVM: a second transformer would see the first one's output.
        if (installed) return;
        installed = true;
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (Boolean.getBoolean("mmt.background")) {
                    try {
                        byte[] background = BackgroundWindows.transform(name, bytes);
                        if (background != null) {
                            String applied = System.getProperty("mmt.background.applied", "");
                            System.setProperty("mmt.background.applied", applied.isEmpty() ? name : applied + "," + name);
                            return background;
                        }
                    } catch (Throwable error) {
                        System.err.println("MMT_BACKGROUND_ERROR " + error);
                        String earlier = System.getProperty("mmt.background.error", "");
                        System.setProperty("mmt.background.error", (earlier.isEmpty() ? "" : earlier + "; ") + error.getMessage());
                        return null;
                    }
                }
                try {
                    // Every launch, so one that is not quiet restores the options a quiet one replaced.
                    if (!"net/minecraft/client/main/Main".equals(name)) return null;
                    System.getProperties().put("mmt.launch.quiet", new Consumer<Object>() {
                        public void accept(Object args) { QuietOptions.launchArgs((String[]) args, Boolean.getBoolean("mmt.quiet")); }
                    });
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    final int[] hooks = {0};
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                            MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                            if (!method.equals("main") || !descriptor.equals("([Ljava/lang/String;)V") || (access & Opcodes.ACC_STATIC) == 0) return delegate;
                            hooks[0]++;
                            return new MethodVisitor(Opcodes.ASM9, delegate) {
                                @Override public void visitCode() {
                                    super.visitCode();
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                                    super.visitLdcInsn("mmt.launch.quiet");
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", true);
                                }
                            };
                        }
                    }, 0);
                    if (hooks[0] != 1) throw new IllegalStateException("Minecraft client entry point is unsupported");
                    return writer.toByteArray();
                } catch (Throwable error) {
                    System.err.println("MMT_QUIET_ERROR " + error);
                    return null;
                }
            }
        });
    }
}
