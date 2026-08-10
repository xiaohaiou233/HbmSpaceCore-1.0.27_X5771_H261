package com.hbm.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import cpw.mods.fml.common.asm.transformers.deobf.FMLRemappingAdapter;

// ==== [modpack] coremod 注入器：两个注入点
// 注入点 A（维度）：vanilla 做完 canRespawnHere/getRespawnDimension 后、getBedLocation 之前
//                  强制把最终维度 p_72368_2_ 覆盖为我们的维度（防止 attemptingSleep 泄露跳过我们的逻辑）
// 注入点 B（位置）：vanilla 床验证块之后、无条件 loadChunk 之前
//                  调用 resolveRespawnPosition，按模组规则定位置（完全覆盖原版位置判定）
//
// ==== [modpack] 混淆处理（2026-08-08 修复，见 docs/coremod-obfuscation-fix.md）
// 整合包运行时 transform 链：coremod 先注册收到 notch 字节 → DeobfuscationTransformer 最后才转 SRG。
// 因此 prod 下收到的字节是 notch 名（方法名 "a"，不是 func_72368_a），直接匹配 SRG 名必失败。
// 修复：在 transformer 内先用 FMLRemappingAdapter 把 notch 字节 deobf 成 SRG 字节，
//       再统一用 SRG 名匹配（与 dev 同一套逻辑）。FMLRemappingAdapter 对 SRG 名幂等，Deobf 二次处理不破坏注入。
public class HbmRespawnTransformer implements IClassTransformer {

	private static final String DEV_SCM  = "net.minecraft.server.management.ServerConfigurationManager";
	private static final String PROD_SCM  = "oi";
	private static final String EHMP     = "net/minecraft/entity/player/EntityPlayerMP";  // SRG 类名（MCP=SRG 相同）
	// ==== [modpack] 方法名兼容（B4 调查发现 dev 环境注入失败）
	// dev（runClient）：类名是 DEV_SCM，方法名是 MCP 名 respawnPlayer
	// prod（reobf 整合包）：类名是 oi，先 deobf 成 SRG 字节，方法名是 func_72368_a
	private static final String[] METHODS = {"respawnPlayer", "func_72368_a"};
	private static final String HOOK_CLASS = "com/hbm/core/HbmRespawnHook";

	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass) {
		if (basicClass == null) return null;
		String dotName = name.replace('/', '.');
		boolean prod = dotName.equals(PROD_SCM);
		if (!dotName.equals(DEV_SCM) && !prod) return basicClass;

		try {
			// prod：先把 notch 字节 deobf 成 SRG，再统一走 SRG 匹配
			if (prod) basicClass = deobfuscate(basicClass);
			return patchRespawnPlayer(basicClass);
		} catch (Throwable t) {
			System.err.println("[hbmcore] respawn transformer FAILED: " + t);
			t.printStackTrace();
			return basicClass;
		}
	}

	// notch 字节 → SRG 字节（与 DeobfuscationTransformer.transform 相同实现）
	private static byte[] deobfuscate(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		FMLRemappingAdapter adapter = new FMLRemappingAdapter(cw);
		cr.accept(adapter, ClassReader.EXPAND_FRAMES);
		return cw.toByteArray();
	}

	private byte[] patchRespawnPlayer(byte[] classBytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);

		MethodNode m = findMethod(cn, METHODS);
		if (m == null) throw new IllegalStateException("method not found: " + java.util.Arrays.toString(METHODS));

		// ==== 注入点 A：维度覆盖（getBedLocation 之前） ====
		// getBedLocation(int) 是 respawnPlayer 里第一个使用 p_72368_2_ 的调用
		MethodInsnNode getBed = findGetBedLocationCall(m, EHMP);
		if (getBed == null) throw new IllegalStateException("getBedLocation not found");

		AbstractInsnNode beforeGetBed = walkBackPastArgs(getBed);
		// ALOAD oldPlayer(slot=1); INVOKESTATIC resolveRespawnDimensionFor; ISTORE dim(slot=2)
		InsnList injectDim = new InsnList();
		injectDim.add(new VarInsnNode(Opcodes.ALOAD, 1));
		injectDim.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				HOOK_CLASS, "resolveRespawnDimensionFor",
				"(L" + EHMP + ";)I"));
		injectDim.add(new VarInsnNode(Opcodes.ISTORE, 2));
		m.instructions.insert(beforeGetBed, injectDim);

		// ==== 注入点 B：位置覆盖（loadChunk 之前） ====
		int newPlayerVar = findNewPlayerVar(m, EHMP);
		if (newPlayerVar < 0) throw new IllegalStateException("EntityPlayerMP store not found");

		MethodInsnNode loadChunk = findLoadChunkCall(m);
		if (loadChunk == null) throw new IllegalStateException("loadChunk not found");

		AbstractInsnNode beforeLoadChunk = walkBackPastArgs(loadChunk);
		InsnList injectPos = new InsnList();
		injectPos.add(new VarInsnNode(Opcodes.ALOAD, newPlayerVar));
		injectPos.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				HOOK_CLASS, "onVanillaRespawnFallback",
				"(L" + EHMP + ";)V"));
		m.instructions.insert(beforeLoadChunk, injectPos);

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);

		System.out.println("[hbmcore] injected dimension+position hooks into " + m.name
				+ " (newPlayerVar=" + newPlayerVar + ")");
		return cw.toByteArray();
	}

	// ---- helpers ----

	private static MethodNode findMethod(ClassNode cn, String... names) {
		for (MethodNode m : cn.methods) {
			for (String name : names) if (m.name.equals(name)) return m;
		}
		return null;
	}

	// find the ONLY INVOKEVIRTUAL on EntityPlayerMP/mw that takes int → returns object (= getBedLocation)
	private static MethodInsnNode findGetBedLocationCall(MethodNode m, String ehmp) {
		for (AbstractInsnNode insn : m.instructions.toArray()) {
			if (insn instanceof MethodInsnNode) {
				MethodInsnNode mi = (MethodInsnNode) insn;
				if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL
						&& mi.owner.equals(ehmp)
						&& mi.desc.startsWith("(I)L")) {
					return mi;
				}
			}
		}
		return null;
	}

	private static int findNewPlayerVar(MethodNode m, String ehmp) {
		for (AbstractInsnNode insn : m.instructions.toArray()) {
			if (!(insn instanceof MethodInsnNode)) continue;
			MethodInsnNode mi = (MethodInsnNode) insn;
			if (mi.getOpcode() != Opcodes.INVOKESPECIAL || !mi.name.equals("<init>") || !mi.owner.equals(ehmp)) continue;
			AbstractInsnNode cur = mi.getNext();
			while (cur != null) {
				if (cur instanceof VarInsnNode && cur.getOpcode() == Opcodes.ASTORE) return ((VarInsnNode) cur).var;
				if (cur instanceof LabelNode || cur instanceof LineNumberNode || cur instanceof FrameNode) { cur = cur.getNext(); continue; }
				break;
			}
		}
		return -1;
	}

	private static MethodInsnNode findLoadChunkCall(MethodNode m) {
		for (AbstractInsnNode insn : m.instructions.toArray()) {
			if (insn instanceof MethodInsnNode) {
				MethodInsnNode mi = (MethodInsnNode) insn;
				if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL
						&& (mi.name.equals("loadChunk") || mi.name.equals("func_73158_c"))) return mi;
			}
		}
		return null;
	}

	// walk back from a call instruction past its argument-pushing instructions + frame/label/line noise
	private static AbstractInsnNode walkBackPastArgs(AbstractInsnNode call) {
		AbstractInsnNode cur = call.getPrevious();
		int skipped = 0;
		while (cur != null && skipped < 24) {
			if (isSkippable(cur)) { cur = cur.getPrevious(); skipped++; continue; }
			return cur; // landed on the statement before the call's operand chain
		}
		return null;
	}

	private static boolean isSkippable(AbstractInsnNode insn) {
		if (insn instanceof LineNumberNode) return true;
		if (insn instanceof VarInsnNode || insn instanceof FieldInsnNode || insn instanceof IntInsnNode) return true;
		if (insn instanceof MethodInsnNode) return true; // chained calls like getServerForPlayer()
		if (insn instanceof InsnNode) {
			int op = insn.getOpcode();
			return (op >= 2 && op <= 15) || (op >= 120 && op <= 147); // const / cast / shift
		}
		return false;
	}
}
