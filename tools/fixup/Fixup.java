import xyz.wagyourtail.jvmdg.shade.asm.*;
import java.io.*; import java.util.zip.*;

// Post-pass over downgraded jars: repair JVMDowngrader output that references members missing from JDK 8.
//  - StringBuilder.append(B)/append(S) (emitted by the string-concat desugaring) -> append(I)
public class Fixup {
    public static void main(String[] args) throws Exception {
        for (String jar : args) {
            File in = new File(jar), tmp = new File(jar + ".tmp"); int fixed = 0;
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(in)));
                 ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                ZipEntry ze;
                while ((ze = zin.getNextEntry()) != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
                    while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                    byte[] data = bo.toByteArray();
                    if (ze.getName().endsWith(".class")) {
                        final boolean[] changed = {false};
                        ClassReader cr = new ClassReader(data); ClassWriter cw = new ClassWriter(cr, 0);
                        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                            public MethodVisitor visitMethod(int a, String name, String d, String s, String[] ex) {
                                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(a, name, d, s, ex)) {
                                    public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                                        if (owner.equals("java/lang/StringBuilder") && name.equals("append") && (desc.equals("(B)Ljava/lang/StringBuilder;") || desc.equals("(S)Ljava/lang/StringBuilder;"))) {
                                            desc = "(I)Ljava/lang/StringBuilder;"; changed[0] = true;
                                        }
                                        super.visitMethodInsn(op, owner, name, desc, itf);
                                    }
                                };
                            }
                        }, 0);
                        if (changed[0]) { data = cw.toByteArray(); fixed++; }
                    }
                    ZipEntry out = new ZipEntry(ze.getName()); zout.putNextEntry(out); zout.write(data); zout.closeEntry();
                }
            }
            if (fixed > 0) { if (!in.delete() || !tmp.renameTo(in)) throw new IOException("rename failed: " + jar); System.out.println(jar + ": fixed " + fixed + " classes"); }
            else tmp.delete();
        }
    }
}
