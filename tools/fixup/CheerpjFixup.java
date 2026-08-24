import xyz.wagyourtail.jvmdg.shade.asm.*;
import java.io.*; import java.util.zip.*;

// Demo-only (CheerpJ) tweaks: netty's estimateMaxDirectMemory() uses MethodHandles on an interface method, which CheerpJ cannot link.
// Replace its body with `return Runtime.getRuntime().maxMemory();`
public class CheerpjFixup {
    public static void main(String[] args) throws Exception {
        for (String jar : args) {
            File in = new File(jar), tmp = new File(jar + ".tmp"); final int[] fixed = {0};
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(in)));
                 ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                ZipEntry ze;
                while ((ze = zin.getNextEntry()) != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                    while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                    byte[] data = bo.toByteArray();
                    if (ze.getName().equals("io/netty/util/internal/CleanerJava6.class")) {
                        // Neutralize the static initializer (MethodHandle on an interface method -> CheerpJ AIOOBE); isSupported() then returns false -> NOOP cleaner.
                        ClassReader cr = new ClassReader(data); ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                            public MethodVisitor visitMethod(int a, String name, String d, String s, String[] ex) {
                                MethodVisitor mv = super.visitMethod(a, name, d, s, ex);
                                if (name.equals("<clinit>")) { mv.visitCode(); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 0); mv.visitEnd(); fixed[0]++; return null; }
                                return mv;
                            }
                        }, 0);
                        data = cw.toByteArray();
                    }
                    if (ze.getName().equals("io/netty/util/internal/PlatformDependent.class")) {
                        ClassReader cr = new ClassReader(data); ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                            public MethodVisitor visitMethod(int a, String name, String d, String s, String[] ex) {
                                MethodVisitor mv = super.visitMethod(a, name, d, s, ex);
                                if (name.equals("estimateMaxDirectMemory") && d.equals("()J")) {
                                    mv.visitCode();
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Runtime", "getRuntime", "()Ljava/lang/Runtime;", false);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "maxMemory", "()J", false);
                                    mv.visitInsn(Opcodes.LRETURN);
                                    mv.visitMaxs(2, 0); mv.visitEnd();
                                    fixed[0]++;
                                    return null; // discard original body
                                }
                                return mv;
                            }
                        }, 0);
                        data = cw.toByteArray();
                    }
                    zout.putNextEntry(new ZipEntry(ze.getName())); zout.write(data); zout.closeEntry();
                }
            }
            if (fixed[0] > 0) { if (!in.delete() || !tmp.renameTo(in)) throw new IOException("rename failed"); System.out.println(jar + ": applied " + fixed[0] + " CheerpJ tweaks"); } else tmp.delete();
        }
    }
}
