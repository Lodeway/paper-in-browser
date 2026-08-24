import xyz.wagyourtail.jvmdg.shade.asm.*;
import xyz.wagyourtail.jvmdg.shade.asm.tree.*;
import xyz.wagyourtail.jvmdg.shade.asm.tree.analysis.*;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.zip.*;

// Post-pass over the downgraded jars: replace calls through JVMDowngrader's VarHandle emulation stub
// (J_L_I_VarHandle) with the field or array access itself, inlined at the callsite. The stub costs
// ~2.7us per access under CheerpJ (dispatch + owner checks + emulated Unsafe) vs ~30ns for a plain
// field access, and the chunk system's hot structures (MultiThreadedQueue, the concurrent hash
// tables, NewChunkHolder, ...) sit on it for every operation.
//
// The access is inlined rather than delegated to generated accessor methods: an earlier version
// generated `static labs$vh...` helpers, which were bytecode-correct (they passed HotSpot's verifier
// and ran fine on a real JDK 8) but were miscompiled by CheerpJ's JIT once hot — reads through them
// started returning null at the JIT tier-up threshold. Inlined checkcast+getfield/putfield is the
// exact shape javac emits and is what CheerpJ is best tested against.
//
// Sound only because CheerpJ runs every Java thread cooperatively on one JS thread: threads switch
// only at yield points (blocking calls, monitors, sleeps), never inside the straight-line inlined
// sequences, so plain reads/writes — and non-atomic compare-and-set sequences — observe and preserve
// the same values the atomic originals would.
//
// Mechanics, per jar set (all jars are processed in one invocation so cross-jar targets resolve):
//  1. Classes whose constant pool mentions the stub are parsed; their <clinit> is pattern-matched for
//     handle creation (ConcurrentUtil.getVarHandle/getStaticVarHandle/getArrayHandle and the
//     MethodHandles stub equivalents) with constant arguments, giving handleField -> (kind, owner,
//     field, type). The target field is verified to exist; its real descriptor wins.
//  2. Every invokevirtual on the stub whose receiver provably is `getstatic <mapped handle>` — with
//     the getstatic in the same straight-line region as the call — is planned for inlining; the
//     getstatic is deleted and the call becomes the direct access (with a branch for compare-and-set,
//     scratch locals for the read-modify-write ops). Everything else stays on the stub.
//  3. Only classes with rewrites (or in the same package, for private access) are re-serialized;
//     frames are recomputed against the full jar set. Untouched classes are copied byte-identical.
public class VarHandleFixup {
    static final String STUB = "xyz/wagyourtail/jvmdg/j9/stub/java_base/J_L_I_VarHandle";
    static final String STUB_DESC = "L" + STUB + ";";
    static final String STUB_MH = "xyz/wagyourtail/jvmdg/j9/stub/java_base/J_L_I_MethodHandles";
    static final byte[] NEEDLE = "J_L_I_VarHandle".getBytes();

    static final int KIND_INSTANCE = 0, KIND_STATIC = 1, KIND_ARRAY = 2;

    static final class Handle {
        int kind;
        String owner;      // field owner (instance/static kinds)
        String field;
        Type fieldType;    // field type, or array class type for KIND_ARRAY
    }

    // handle map key: declaringClass + "." + handleFieldName
    static final Map<String, Handle> handles = new HashMap<>();
    static final Map<String, byte[]> classBytes = new HashMap<>();      // internal name -> bytes
    static final Map<String, String> classJar = new HashMap<>();        // internal name -> jar path
    static final Map<String, ClassNode> parsed = new HashMap<>();       // internal name -> node
    static final Set<String> changed = new HashSet<>();
    static final Set<Handle> used = new HashSet<>();                    // handles with >=1 inlined callsite
    static ClassLoader loader;
    static int rewritten = 0, skipped = 0;

    // Park/unpark handshakes stay on the stub: their spin-until-CAS loops sit across LockSupport.park
    // and rely on the stub's native call being a yield point in CheerpJ's cooperative scheduler — an
    // inlined sequence never yields, and the boot deadlocks (empirically: QueueExecutorRunnable hangs
    // Bootstrap). These run once per work batch, so the stub cost is irrelevant there.
    static final String[] PARK_HANDSHAKES = { "QueueExecutorRunnable", "$TickThreadRunner" };

    // debugging aids: -Dvh.ops=get,set restricts which op families are rewritten (default: all);
    // -Dvh.exclude=A,B skips rewriting inside classes whose internal name contains A or B;
    // -Dvh.novolatile skips the volatile marking; -Dvh.nulltransform re-serializes without rewriting
    static final Set<String> OPS = System.getProperty("vh.ops") == null ? null
        : new HashSet<>(Arrays.asList(System.getProperty("vh.ops").split(",")));
    static final String[] EXCLUDE = System.getProperty("vh.exclude") == null ? new String[0]
        : System.getProperty("vh.exclude").split(",");

    public static void main(String[] args) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String jar : args) urls.add(new File(jar).toURI().toURL());
        loader = new URLClassLoader(urls.toArray(new URL[0]), VarHandleFixup.class.getClassLoader());

        // pass 0: index every class in every jar (bytes only)
        for (String jar : args) {
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(jar)))) {
                ZipEntry ze;
                while ((ze = zin.getNextEntry()) != null) {
                    if (!ze.getName().endsWith(".class")) continue;
                    byte[] data = readAll(zin);
                    String name = ze.getName().substring(0, ze.getName().length() - 6);
                    classBytes.put(name, data);
                    classJar.put(name, jar);
                }
            }
        }

        // pass 1: parse candidates, collect handle definitions from <clinit>
        for (Map.Entry<String, byte[]> e : classBytes.entrySet()) {
            if (!contains(e.getValue(), NEEDLE)) continue;
            ClassNode cn = node(e.getKey());
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("<clinit>")) collectHandles(cn, mn);
            }
        }
        System.out.println("[vh] mapped " + handles.size() + " VarHandle statics");

        if (System.getProperty("vh.nulltransform") != null) {
            changed.addAll(parsed.keySet());
            writeJars(args);
            return;
        }

        // pass 2: inline callsites in every candidate class
        for (String name : new ArrayList<>(parsed.keySet())) {
            ClassNode cn = parsed.get(name);
            for (MethodNode mn : cn.methods) rewriteMethod(cn, mn);
        }
        System.out.println("[vh] inlined " + rewritten + " callsites, left " + skipped + " on the stub");

        // Plain reads can be cached by CheerpJ's JIT across a cooperative spin loop, so a thread
        // polling a flag another thread sets would never see the write. Volatile costs nothing
        // measurable under CheerpJ and forces the re-read, so every inlined target field gets it.
        int marked = 0;
        boolean noVolatile = System.getProperty("vh.novolatile") != null;
        for (Handle h : noVolatile ? Collections.<Handle>emptySet() : used) {
            if (h.kind == KIND_ARRAY) continue;
            ClassNode owner = node(h.owner);
            FieldNode f = owner == null ? null : field(owner, h.field);
            if (f == null || (f.access & (Opcodes.ACC_VOLATILE | Opcodes.ACC_FINAL)) != 0) continue;
            f.access |= Opcodes.ACC_VOLATILE;
            changed.add(h.owner);
            marked++;
        }
        System.out.println("[vh] marked " + marked + " target fields volatile");

        writeJars(args);
    }

    // write back every jar that has changed classes
    static void writeJars(String[] args) throws IOException {
        for (String jar : args) {
            boolean dirty = false;
            for (String c : changed) if (jar.equals(classJar.get(c))) { dirty = true; break; }
            if (!dirty) continue;
            File in = new File(jar), tmp = new File(jar + ".tmp");
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(in)));
                 ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                ZipEntry ze;
                while ((ze = zin.getNextEntry()) != null) {
                    byte[] data = readAll(zin);
                    String name = ze.getName().endsWith(".class") ? ze.getName().substring(0, ze.getName().length() - 6) : null;
                    if (name != null && changed.contains(name)) data = emit(parsed.get(name));
                    zout.putNextEntry(new ZipEntry(ze.getName()));
                    zout.write(data);
                    zout.closeEntry();
                }
            }
            if (!in.delete() || !tmp.renameTo(in)) throw new IOException("could not replace " + jar);
            System.out.println("[vh] " + new File(jar).getName() + " updated");
        }
    }

    static ClassNode node(String name) {
        ClassNode cn = parsed.get(name);
        if (cn == null) {
            byte[] b = classBytes.get(name);
            if (b == null) return null;
            cn = new ClassNode();
            new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES);
            parsed.put(name, cn);
        }
        return cn;
    }

    // frames are recomputed for changed classes; resolve hierarchy against the processed jar set
    static byte[] emit(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected ClassLoader getClassLoader() { return loader; }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    // ---- handle collection ------------------------------------------------------------------------------

    static void collectHandles(ClassNode cn, MethodNode mn) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FieldInsnNode)) continue;
            FieldInsnNode put = (FieldInsnNode) insn;
            if (put.getOpcode() != Opcodes.PUTSTATIC || !put.desc.equals(STUB_DESC)) continue;
            AbstractInsnNode prev = real(insn.getPrevious());
            if (!(prev instanceof MethodInsnNode)) continue;
            Handle h = matchFactory((MethodInsnNode) prev);
            String key = put.owner + "." + put.name;
            if (h == null) { handles.remove(key); continue; } // unknown factory: never trust this field
            if (h.kind != KIND_ARRAY) {
                // the factory's type argument should match the real field; the real descriptor wins
                ClassNode ownerNode = node(h.owner);
                FieldNode target = ownerNode == null ? null : field(ownerNode, h.field);
                if (target == null) continue;
                h.fieldType = Type.getType(target.desc);
            }
            if (handles.containsKey(key)) handles.remove(key); // assigned twice: ambiguous, drop
            else handles.put(key, h);
        }
    }

    static Handle matchFactory(MethodInsnNode fac) {
        boolean cu = fac.owner.equals("ca/spottedleaf/concurrentutil/util/ConcurrentUtil");
        boolean mh = fac.owner.equals(STUB_MH) || fac.owner.equals(STUB_MH + "$Lookup");
        if (!cu && !mh) return null;
        Handle h = new Handle();
        if (fac.name.equals("getVarHandle") || fac.name.equals("findVarHandle")
            || fac.name.equals("getStaticVarHandle") || fac.name.equals("findStaticVarHandle")) {
            h.kind = fac.name.contains("Static") ? KIND_STATIC : KIND_INSTANCE;
            // last three args are (ownerClass, name, typeClass) constants
            AbstractInsnNode a3 = real(fac.getPrevious());
            AbstractInsnNode a2 = a3 == null ? null : real(a3.getPrevious());
            AbstractInsnNode a1 = a2 == null ? null : real(a2.getPrevious());
            Type owner = constClass(a1);
            String name = constString(a2);
            Type type = constClass(a3);
            if (owner == null || name == null || type == null || owner.getSort() != Type.OBJECT) return null;
            h.owner = owner.getInternalName();
            h.field = name;
            h.fieldType = type;
            return h;
        }
        if (fac.name.equals("getArrayHandle") || fac.name.equals("arrayElementVarHandle")) {
            Type arr = constClass(real(fac.getPrevious()));
            if (arr == null || arr.getSort() != Type.ARRAY) return null;
            h.kind = KIND_ARRAY;
            h.fieldType = arr;
            return h;
        }
        return null;
    }

    static Type constClass(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Type) return (Type) ((LdcInsnNode) insn).cst;
        if (insn instanceof FieldInsnNode) { // Integer.TYPE etc.
            FieldInsnNode f = (FieldInsnNode) insn;
            if (f.getOpcode() == Opcodes.GETSTATIC && f.name.equals("TYPE")) {
                switch (f.owner) {
                    case "java/lang/Boolean": return Type.BOOLEAN_TYPE;
                    case "java/lang/Byte": return Type.BYTE_TYPE;
                    case "java/lang/Character": return Type.CHAR_TYPE;
                    case "java/lang/Short": return Type.SHORT_TYPE;
                    case "java/lang/Integer": return Type.INT_TYPE;
                    case "java/lang/Long": return Type.LONG_TYPE;
                    case "java/lang/Float": return Type.FLOAT_TYPE;
                    case "java/lang/Double": return Type.DOUBLE_TYPE;
                }
            }
        }
        return null;
    }

    static String constString(AbstractInsnNode insn) {
        return insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof String ? (String) ((LdcInsnNode) insn).cst : null;
    }

    static AbstractInsnNode real(AbstractInsnNode insn) {
        while (insn != null && (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode)) insn = insn.getPrevious();
        return insn;
    }

    static FieldNode field(ClassNode cn, String name) {
        for (FieldNode f : cn.fields) if (f.name.equals(name)) return f;
        return null;
    }

    // ---- callsite inlining ------------------------------------------------------------------------------

    static final class Plan {
        FieldInsnNode getstatic;
        MethodInsnNode call;
        Handle handle;
        String base;
    }

    static void rewriteMethod(ClassNode cn, MethodNode mn) {
        for (String e : PARK_HANDSHAKES) if (cn.name.contains(e)) return;
        for (String e : EXCLUDE) if (!e.isEmpty() && cn.name.contains(e)) return;
        boolean any = false;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode && insn.getOpcode() == Opcodes.INVOKEVIRTUAL
                && ((MethodInsnNode) insn).owner.equals(STUB)) { any = true; break; }
        }
        if (!any) return;

        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(cn.name, mn);
        } catch (AnalyzerException e) {
            System.out.println("[vh] analyze failed for " + cn.name + "." + mn.name + ": " + e);
            return;
        }

        // plan first (frame indices are only valid on the unmodified method), then mutate
        List<Plan> plans = new ArrayList<>();
        AbstractInsnNode[] insns = mn.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof MethodInsnNode) || insns[i].getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            MethodInsnNode call = (MethodInsnNode) insns[i];
            if (!call.owner.equals(STUB)) continue;
            Plan p = plan(cn, frames[i], call);
            if (p == null) skipped++;
            else plans.add(p);
        }
        for (Plan p : plans) {
            apply(mn, p);
            used.add(p.handle);
            rewritten++;
            changed.add(cn.name);
            // a private field inlined from another class of the same package (nestmates) needs the
            // private bit cleared; package access is enough
            if (p.handle.kind != KIND_ARRAY && !p.handle.owner.equals(cn.name)) {
                ClassNode oc = node(p.handle.owner);
                FieldNode f = oc == null ? null : field(oc, p.handle.field);
                if (f != null && (f.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) != 0) {
                    f.access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
                    changed.add(p.handle.owner);
                }
            }
        }
    }

    /** Decides whether this callsite can be inlined; returns the plan, or null to leave it on the stub. */
    static Plan plan(ClassNode cn, Frame<SourceValue> frame, MethodInsnNode call) {
        if (frame == null) return null;
        String base = opBase(call.name);
        if (base == null || (OPS != null && !OPS.contains(base))) return null;
        Type[] args = Type.getArgumentTypes(call.desc);
        Type ret = Type.getReturnType(call.desc);
        int recvSlot = frame.getStackSize() - args.length - 1;
        if (recvSlot < 0) return null;
        SourceValue recv = frame.getStack(recvSlot);
        if (recv == null || recv.insns.size() != 1) return null;
        AbstractInsnNode src = recv.insns.iterator().next();
        if (!(src instanceof FieldInsnNode) || src.getOpcode() != Opcodes.GETSTATIC) return null;
        FieldInsnNode gs = (FieldInsnNode) src;
        Handle h = handles.get(gs.owner + "." + gs.name);
        if (h == null) return null;
        if (h.kind != KIND_ARRAY && !samePackage(cn.name, h.owner)) return null;

        // the getstatic must sit in the same straight-line region as the call: no labels (jump
        // targets, try-catch edges), no branches, and no frames between them
        int hops = 0;
        for (AbstractInsnNode n = gs.getNext(); n != call; n = n.getNext()) {
            if (n == null || n instanceof LabelNode || n instanceof FrameNode || n instanceof JumpInsnNode
                || n instanceof TableSwitchInsnNode || n instanceof LookupSwitchInsnNode
                || ++hops > 64) return null;
        }

        Type ft = h.kind == KIND_ARRAY ? h.fieldType.getElementType() : h.fieldType;
        if (ft.getSort() == Type.FLOAT || ft.getSort() == Type.DOUBLE) return null; // bitwise CAS semantics differ; not needed
        if (h.kind == KIND_ARRAY && h.fieldType.getDimensions() != 1) return null;

        // expected argument shape
        int fixed = h.kind == KIND_INSTANCE ? 1 : h.kind == KIND_ARRAY ? 2 : 0;
        int values = base.equals("get") ? 0 : (base.equals("cas") || base.equals("cax")) ? 2 : 1;
        if (args.length != fixed + values) return null;
        if (h.kind != KIND_STATIC && args[0].getSort() != Type.OBJECT) return null;
        if (h.kind == KIND_ARRAY && args[1].getSort() != Type.INT) return null;
        for (int i = fixed; i < args.length; i++) if (!compatible(args[i], ft)) return null;
        if (base.equals("get") || base.equals("cax") || base.equals("gas") || base.equals("gaa") || base.equals("gor")) {
            if (!compatible(ret, ft)) return null;
        } else if (base.equals("cas")) {
            if (ret.getSort() != Type.BOOLEAN) return null;
        } else { // set
            if (ret.getSort() != Type.VOID) return null;
        }
        if (base.equals("gaa") && ft.getSort() != Type.INT && ft.getSort() != Type.LONG) return null;
        if (base.equals("gor") && ft.getSort() != Type.INT) return null;
        if (h.kind == KIND_STATIC && !(base.equals("get") || base.equals("set"))) return null;

        Plan p = new Plan();
        p.getstatic = gs;
        p.call = call;
        p.handle = h;
        p.base = base;
        return p;
    }

    static String opBase(String n) {
        if (n.startsWith("compareAndExchange")) return "cax";
        if (n.startsWith("compareAndSet") || n.startsWith("weakCompareAndSet")) return "cas";
        if (n.startsWith("getAndSet")) return "gas";
        if (n.startsWith("getAndAdd")) return "gaa";
        if (n.startsWith("getAndBitwiseOr")) return "gor";
        if (n.startsWith("set")) return "set";
        if (n.startsWith("get")) return "get";
        return null;
    }

    /** Deletes the handle getstatic and replaces the call with the inlined access. */
    static void apply(MethodNode mn, Plan p) {
        Handle h = p.handle;
        Type ft = h.kind == KIND_ARRAY ? h.fieldType.getElementType() : h.fieldType;
        Type[] args = Type.getArgumentTypes(p.call.desc);
        int fixed = h.kind == KIND_INSTANCE ? 1 : h.kind == KIND_ARRAY ? 2 : 0;
        String owner = h.owner, fname = h.field, fdesc = ft.getDescriptor();
        String arrType = h.kind == KIND_ARRAY ? h.fieldType.getInternalName() : null;
        boolean castValue = isRef(ft) && !ft.getInternalName().equals("java/lang/Object");

        InsnList c = new InsnList();
        // scratch locals (typed; sized) allocated past the current frame
        int scratch = mn.maxLocals;
        int lRef = -1, lIdx = -1, lVal = -1, lExp = -1, lOld = -1;
        switch (p.base) {
            case "get": {
                // [obj] / [arr, idx] / []
                if (h.kind == KIND_INSTANCE) {
                    c.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
                    c.add(new FieldInsnNode(Opcodes.GETFIELD, owner, fname, fdesc));
                } else if (h.kind == KIND_ARRAY) {
                    c.add(new InsnNode(Opcodes.SWAP)); // [idx, arr]
                    c.add(new TypeInsnNode(Opcodes.CHECKCAST, arrType));
                    c.add(new InsnNode(Opcodes.SWAP)); // [arr, idx]
                    c.add(new InsnNode(ft.getOpcode(Opcodes.IALOAD)));
                } else {
                    c.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, fname, fdesc));
                }
                break;
            }
            case "set": {
                Type vt = args[fixed];
                if (h.kind == KIND_INSTANCE) {
                    if (vt.getSize() == 1) {
                        c.add(new InsnNode(Opcodes.SWAP)); // [v, obj]
                        c.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
                        c.add(new InsnNode(Opcodes.SWAP)); // [obj, v]
                    } else {
                        lVal = scratch; scratch += 2;
                        c.add(new VarInsnNode(vt.getOpcode(Opcodes.ISTORE), lVal));
                        c.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
                        c.add(new VarInsnNode(vt.getOpcode(Opcodes.ILOAD), lVal));
                    }
                    if (castValue) c.add(new TypeInsnNode(Opcodes.CHECKCAST, ft.getInternalName()));
                    c.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, fname, fdesc));
                } else if (h.kind == KIND_ARRAY) {
                    // [arr, idx, v]
                    lVal = scratch; scratch += vt.getSize();
                    lIdx = scratch; scratch += 1;
                    c.add(new VarInsnNode(vt.getOpcode(Opcodes.ISTORE), lVal));
                    c.add(new VarInsnNode(Opcodes.ISTORE, lIdx));
                    c.add(new TypeInsnNode(Opcodes.CHECKCAST, arrType));
                    c.add(new VarInsnNode(Opcodes.ILOAD, lIdx));
                    c.add(new VarInsnNode(vt.getOpcode(Opcodes.ILOAD), lVal));
                    if (castValue) c.add(new TypeInsnNode(Opcodes.CHECKCAST, ft.getInternalName()));
                    c.add(new InsnNode(ft.getOpcode(Opcodes.IASTORE)));
                } else {
                    if (castValue) c.add(new TypeInsnNode(Opcodes.CHECKCAST, ft.getInternalName()));
                    c.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, fname, fdesc));
                }
                break;
            }
            case "gas": case "gaa": case "gor": {
                Type vt = args[fixed];
                lVal = scratch; scratch += vt.getSize();
                lOld = scratch; scratch += ft.getSize();
                c.add(new VarInsnNode(vt.getOpcode(Opcodes.ISTORE), lVal));
                if (h.kind == KIND_INSTANCE) {
                    // [obj]
                    c.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
                    c.add(new InsnNode(Opcodes.DUP)); // [o, o]
                    c.add(new FieldInsnNode(Opcodes.GETFIELD, owner, fname, fdesc)); // [o, old]
                    c.add(new VarInsnNode(ft.getOpcode(Opcodes.ISTORE), lOld)); // [o]
                    if (p.base.equals("gas")) {
                        c.add(new VarInsnNode(vt.getOpcode(Opcodes.ILOAD), lVal));
                        if (castValue) c.add(new TypeInsnNode(Opcodes.CHECKCAST, ft.getInternalName()));
                    } else {
                        c.add(new VarInsnNode(ft.getOpcode(Opcodes.ILOAD), lOld));
                        c.add(new VarInsnNode(vt.getOpcode(Opcodes.ILOAD), lVal));
                        c.add(new InsnNode(ft.getOpcode(p.base.equals("gaa") ? Opcodes.IADD : Opcodes.IOR)));
                    }
                    c.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, fname, fdesc)); // []
                } else { // array
                    // [arr, idx]
                    lIdx = scratch; scratch += 1;
                    lRef = scratch; scratch += 1;
                    c.add(new VarInsnNode(Opcodes.ISTORE, lIdx));
                    c.add(new TypeInsnNode(Opcodes.CHECKCAST, arrType));
                    c.add(new VarInsnNode(Opcodes.ASTORE, lRef)); // []
                    c.add(new VarInsnNode(Opcodes.ALOAD, lRef));
                    c.add(new VarInsnNode(Opcodes.ILOAD, lIdx));
                    c.add(new InsnNode(ft.getOpcode(Opcodes.IALOAD)));
                    c.add(new VarInsnNode(ft.getOpcode(Opcodes.ISTORE), lOld));
                    c.add(new VarInsnNode(Opcodes.ALOAD, lRef));
                    c.add(new VarInsnNode(Opcodes.ILOAD, lIdx));
                    if (p.base.equals("gas")) {
                        c.add(new VarInsnNode(vt.getOpcode(Opcodes.ILOAD), lVal));
                        if (castValue) c.add(new TypeInsnNode(Opcodes.CHECKCAST, ft.getInternalName()));
                    } else {
                        c.add(new VarInsnNode(ft.getOpcode(Opcodes.ILOAD), lOld));
                        c.add(new VarInsnNode(vt.getOpcode(Opcodes.ILOAD), lVal));
                        c.add(new InsnNode(ft.getOpcode(p.base.equals("gaa") ? Opcodes.IADD : Opcodes.IOR)));
                    }
                    c.add(new InsnNode(ft.getOpcode(Opcodes.IASTORE)));
                }
                c.add(new VarInsnNode(ft.getOpcode(Opcodes.ILOAD), lOld));
                break;
            }
            case "cas": case "cax": {
                Type et = args[fixed], nt = args[fixed + 1];
                lVal = scratch; scratch += nt.getSize();  // new value
                lExp = scratch; scratch += et.getSize();  // expected
                lRef = scratch; scratch += 1;             // receiver (instance/array)
                lIdx = scratch; scratch += 1;             // array index
                lOld = scratch; scratch += ft.getSize();
                LabelNode fail = new LabelNode(), end = new LabelNode();
                final int fRef = lRef, fIdx = lIdx;
                c.add(new VarInsnNode(nt.getOpcode(Opcodes.ISTORE), lVal));
                c.add(new VarInsnNode(et.getOpcode(Opcodes.ISTORE), lExp));
                Runnable loadTarget, storePrep;
                if (h.kind == KIND_ARRAY) {
                    c.add(new VarInsnNode(Opcodes.ISTORE, lIdx));
                    c.add(new TypeInsnNode(Opcodes.CHECKCAST, arrType));
                    c.add(new VarInsnNode(Opcodes.ASTORE, lRef));
                    loadTarget = () -> {
                        c.add(new VarInsnNode(Opcodes.ALOAD, fRef));
                        c.add(new VarInsnNode(Opcodes.ILOAD, fIdx));
                        c.add(new InsnNode(ft.getOpcode(Opcodes.IALOAD)));
                    };
                    storePrep = () -> {
                        c.add(new VarInsnNode(Opcodes.ALOAD, fRef));
                        c.add(new VarInsnNode(Opcodes.ILOAD, fIdx));
                    };
                } else {
                    c.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
                    c.add(new VarInsnNode(Opcodes.ASTORE, lRef));
                    loadTarget = () -> {
                        c.add(new VarInsnNode(Opcodes.ALOAD, fRef));
                        c.add(new FieldInsnNode(Opcodes.GETFIELD, owner, fname, fdesc));
                    };
                    storePrep = () -> c.add(new VarInsnNode(Opcodes.ALOAD, fRef));
                }
                loadTarget.run();
                c.add(new VarInsnNode(ft.getOpcode(Opcodes.ISTORE), lOld));
                c.add(new VarInsnNode(ft.getOpcode(Opcodes.ILOAD), lOld));
                c.add(new VarInsnNode(et.getOpcode(Opcodes.ILOAD), lExp));
                switch (ft.getSort()) {
                    case Type.LONG:
                        c.add(new InsnNode(Opcodes.LCMP));
                        c.add(new JumpInsnNode(Opcodes.IFNE, fail));
                        break;
                    case Type.OBJECT: case Type.ARRAY:
                        c.add(new JumpInsnNode(Opcodes.IF_ACMPNE, fail));
                        break;
                    default:
                        c.add(new JumpInsnNode(Opcodes.IF_ICMPNE, fail));
                }
                storePrep.run();
                c.add(new VarInsnNode(nt.getOpcode(Opcodes.ILOAD), lVal));
                if (castValue) c.add(new TypeInsnNode(Opcodes.CHECKCAST, ft.getInternalName()));
                if (h.kind == KIND_ARRAY) c.add(new InsnNode(ft.getOpcode(Opcodes.IASTORE)));
                else c.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, fname, fdesc));
                if (p.base.equals("cas")) {
                    c.add(new InsnNode(Opcodes.ICONST_1));
                    c.add(new JumpInsnNode(Opcodes.GOTO, end));
                    c.add(fail);
                    c.add(new InsnNode(Opcodes.ICONST_0));
                    c.add(end);
                } else {
                    c.add(fail);
                    c.add(new VarInsnNode(ft.getOpcode(Opcodes.ILOAD), lOld));
                }
                break;
            }
        }
        mn.maxLocals = Math.max(mn.maxLocals, scratch);
        mn.instructions.remove(p.getstatic);
        mn.instructions.insertBefore(p.call, c);
        mn.instructions.remove(p.call);
    }

    static boolean isRef(Type t) { return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY; }

    static boolean samePackage(String a, String b) {
        int ia = a.lastIndexOf('/'), ib = b.lastIndexOf('/');
        return ia == ib && (ia < 0 || a.substring(0, ia).equals(b.substring(0, ib)));
    }

    /** callsite type vs field type: same primitive family, or both references (no boxing supported). */
    static boolean compatible(Type callsite, Type ft) {
        if (isRef(ft)) return isRef(callsite);
        switch (ft.getSort()) {
            case Type.BOOLEAN: case Type.BYTE: case Type.CHAR: case Type.SHORT: case Type.INT:
                switch (callsite.getSort()) {
                    case Type.BOOLEAN: case Type.BYTE: case Type.CHAR: case Type.SHORT: case Type.INT: return true;
                    default: return false;
                }
            default:
                return callsite.getSort() == ft.getSort();
        }
    }

    static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
