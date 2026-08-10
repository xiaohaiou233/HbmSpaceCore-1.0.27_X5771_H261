package com.hbm.compat;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import cpw.mods.fml.common.asm.transformers.deobf.FMLRemappingAdapter;

import com.hbm.main.MainRegistry;

// ==== [modpack] NeiFix：屏蔽 Minecraft.toggleFullscreen（原 FullscreenFix MinecraftTransformer，2026-08-08 并入）
// 目的：把 Minecraft.toggleFullscreen（SRG: func_71352_i，notch: o）整个替换为对 FixFullscreenHooks.toggleFullscreen 的调用，
//       禁用独占全屏切换。原因：1.7.10 独占全屏切换销毁/重建 OpenGL 上下文，RTX/现代 Windows 上触发 GPU 驱动挂起。
//
// ==== 混淆处理（复用方案 B，见 docs/coremod-obfuscation-fix.md）
// 整合包运行时 coremod 先收到 notch 字节，这里先 FMLRemappingAdapter deobf 成 SRG 再统一匹配 func_71352_i。
//
// 无 NEI 保护：NeiFix.isNeiEnabled()=false 时直接返回原字节。
public class NeiFixMinecraftTransformer implements IClassTransformer {

	private static final String DEV_CLASS  = "net.minecraft.client.Minecraft";
	private static final String PROD_CLASS = "bao";  // notch: Minecraft
	private static final String SRG_METHOD = "func_71352_k";  // SRG: toggleFullscreen（⚠️ 注意是 _k 不是 _i，见 srg 映射）
	private static final String MCP_METHOD = "toggleFullscreen";
	private static final String HOOK_CLASS = "com/hbm/compat/NeiFixHooks";

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
			// prod：先把 notch 字节 deobf 成 SRG，再统一走 SRG 匹配
			if(prod) basicClass = deobfuscate(basicClass);
			return patchToggleFullscreen(basicClass);
		} catch(Throwable t) {
			MainRegistry.logger.error("[hbmcore] fullscreen transformer FAILED: " + t);
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
			Class.forName("codechicken.nei.api.API", false, NeiFixMinecraftTransformer.class.getClassLoader());
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

	private byte[] patchToggleFullscreen(byte[] classBytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);

		MethodNode m = findMethod(cn, SRG_METHOD, MCP_METHOD);
		if(m == null) throw new IllegalStateException("toggleFullscreen not found");

		// 整个方法体替换为：ALOAD 0; INVOKESTATIC NeiFixHooks.toggleFullscreen(Minecraft); RETURN
		// ⚠️ 必须同时清掉 tryCatchBlocks + localVariables：原方法体有 try-catch（LabelNode 指向旧指令），
		//    instructions.clear() 后这些 Label 悬空 → ClassWriter 写回时 NullPointerException（已实测 13:22:09 崩溃）。
		InsnList replacement = new InsnList();
		replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
		replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				HOOK_CLASS, "toggleFullscreen",
				"(Lnet/minecraft/client/Minecraft;)V"));
		replacement.add(new InsnNode(Opcodes.RETURN));

		m.instructions.clear();
		m.instructions.add(replacement);
		m.tryCatchBlocks.clear();
		m.localVariables.clear();
		m.maxStack = 1;

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);

		MainRegistry.logger.info("[hbmcore] fullscreen toggle suppressed, injected hook into Minecraft." + m.name);
		return cw.toByteArray();
	}

	private static MethodNode findMethod(ClassNode cn, String srgName, String mcpName) {
		for(MethodNode m : cn.methods) {
			if(m.name.equals(srgName) || m.name.equals(mcpName)) return m;
		}
		return null;
	}
}
