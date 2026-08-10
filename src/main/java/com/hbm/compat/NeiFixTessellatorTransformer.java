package com.hbm.compat;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import cpw.mods.fml.common.asm.transformers.deobf.FMLRemappingAdapter;

import com.hbm.main.MainRegistry;

// ==== [modpack] NeiFix：Tessellator.draw 注入 glFlush（原 FullscreenFix TessellatorTransformer，2026-08-08 并入）
// 目的：Tessellator.draw（SRG: func_78381_a，notch: bmh.a）方法体开头注入 GL11.glFlush()。
// 原因：GTNH NEI 渲染 HBM 3D 模型时命令量巨大，GPU 驱动来不及消费积压命令 → RTX/现代 Windows 上
//       渲染超时/卡死/驱动挂起。glFlush 强制刷命令缓冲，让驱动及时消化。
//
// ⚠️ 原 FullscreenFix-1.0.0.jar 的 bug（本次修复）：原 METHOD_NOTCH 写成 "b"，但 Tessellator.draw 的
//    notch 名实际是 "a"（映射 MD: bmh/a ()I → func_78381_a ()I）。原 jar 在整合包运行时匹配不到
//    draw → glFlush 从未注入 → 渲染超时问题一直存在。本版改用 deobf 方案 B：notch 字节先转 SRG，
//    再匹配 func_78381_a，彻底规避单字母 notch 名歧义。
//
// 无 NEI 保护：NeiFix.isNeiEnabled()=false 时直接返回原字节，不做任何改动。
public class NeiFixTessellatorTransformer implements IClassTransformer {

	private static final String DEV_CLASS  = "net.minecraft.client.renderer.Tessellator";
	private static final String PROD_CLASS = "bmh";  // notch: Tessellator
	private static final String SRG_METHOD = "func_78381_a";  // SRG: draw
	private static final String MCP_METHOD = "draw";

	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass) {
		// 无 NEI 环境：完全跳过（不注入任何字节）。
		// ⚠️ 不能用 NeiFix.isNeiEnabled()：coremod transformer 在 FML 启动早期注册，
		//    注册时 loadClass 本类 → 已注册 transformer 会对它跑 transform() → 引用 NeiFix 触发
		//    其类加载 → 又经过 transform 链 → ClassCircularityError 崩溃。
		//    因此这里自包含探测 NEI（Class.forName，不加载项目类，不触发循环）。
		if(!isNeiPresent()) return basicClass;
		if(basicClass == null) return null;

		String dotName = name.replace('/', '.');
		boolean prod = dotName.equals(PROD_CLASS);
		if(!dotName.equals(DEV_CLASS) && !prod) return basicClass;

		try {
			// prod：先把 notch 字节 deobf 成 SRG，再统一匹配 func_78381_a
			if(prod) basicClass = deobfuscate(basicClass);
			return patchDraw(basicClass);
		} catch(Throwable t) {
			MainRegistry.logger.error("[hbmcore] tessellator transformer FAILED: " + t);
			t.printStackTrace();
			return basicClass;  // 容错：不崩游戏
		}
	}

	// 自包含 NEI 探测：静态缓存，只探测一次；重入保护防 transform 链递归
	private static boolean neiChecked = false;
	private static boolean neiPresent = false;

	private static boolean isNeiPresent() {
		if(neiChecked) return neiPresent;
		try {
			// NEI 的 API 入口类；GTNH 版 NEI 同样有该包
			Class.forName("codechicken.nei.api.API", false, NeiFixTessellatorTransformer.class.getClassLoader());
			neiPresent = true;
		} catch(Throwable t) {
			neiPresent = false;
		}
		neiChecked = true;
		return neiPresent;
	}

	// notch 字节 → SRG 字节（与 DeobfuscationTransformer.transform 相同实现）
	private static byte[] deobfuscate(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		FMLRemappingAdapter adapter = new FMLRemappingAdapter(cw);
		cr.accept(adapter, ClassReader.EXPAND_FRAMES);
		return cw.toByteArray();
	}

	private byte[] patchDraw(byte[] classBytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);

		MethodNode m = findMethod(cn, SRG_METHOD, MCP_METHOD);
		if(m == null) throw new IllegalStateException("Tessellator.draw not found");

		// 在方法体最前面注入：INVOKESTATIC org/lwjgl/opengl/GL11.glFlush()
		// 找第一条非 Label/LineNumber 指令作为插入锚点
		AbstractInsnNode anchor = findFirstRealInsn(m);
		if(anchor == null) throw new IllegalStateException("no insertion anchor in draw()");

		InsnList inject = new InsnList();
		inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"org/lwjgl/opengl/GL11", "glFlush", "()V"));

		m.instructions.insertBefore(anchor, inject);

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);

		MainRegistry.logger.info("[hbmcore] injected glFlush into Tessellator." + m.name);
		return cw.toByteArray();
	}

	private static MethodNode findMethod(ClassNode cn, String srgName, String mcpName) {
		for(MethodNode m : cn.methods) {
			if(m.name.equals(srgName) || m.name.equals(mcpName)) return m;
		}
		return null;
	}

	// 跳过 Label/LineNumber，找到第一条真实指令
	private static AbstractInsnNode findFirstRealInsn(MethodNode m) {
		for(AbstractInsnNode insn : m.instructions.toArray()) {
			if(insn instanceof LabelNode || insn instanceof LineNumberNode) continue;
			if(insn.getOpcode() == Opcodes.NOP) continue;
			return insn;
		}
		return null;
	}
}
