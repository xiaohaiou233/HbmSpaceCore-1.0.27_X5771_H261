package com.hbm.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import cpw.mods.fml.common.asm.transformers.deobf.FMLRemappingAdapter;

// ==== [modpack] coremod 注入器：0 重力玩家豁免 vanilla anti-fly 踢人
// vanilla NetHandlerPlayServer.processPlayer（处理 Packet10Flying 移动包）内置悬浮检测：
//   非单机 && 非创造 && 脚下 0.55 格无方块 && Y位移≥-0.03125 → floatingTickCount++，连续 80 tick（4 秒）踢出
//   "Flying is not enabled on this server"。检测只看 theItemInWorldManager.isCreative()，不读 capabilities.allowFlying。
// 0 重力环境（轨道/太空/无重力空间站）玩家 4 豁免条件全不满足 → 必被踢。
//
// 注入点：processPlayer 里 floatingTickCount > 80 的 `bipush 80 → if_icmple` 判断之后，
//         进入 warn+kick 分支前插入 hook：0 重力 → 跳回 if_icmple 的目标 label（正常流程，跳过踢人）。
//
// 混淆处理（与 HbmRespawnTransformer 相同，见 docs/coremod-obfuscation-fix.md）：
// prod 下 transformer 收到 notch 字节（类名 nh，方法名 a），先 FMLRemappingAdapter deobf 成 SRG 字节，
// 再统一用 SRG 名匹配。processPlayer 的 SRG 名为 func_147347_a（mcp-srg.srg 已核实，func_147359_a 是 sendPacket）。
public class HbmAntiFlyTransformer implements IClassTransformer {

	private static final String DEV_NH  = "net.minecraft.network.NetHandlerPlayServer";
	private static final String PROD_NH = "nh";   // notch: NetHandlerPlayServer
	private static final String NH      = "net/minecraft/network/NetHandlerPlayServer";
	// SRG 名（prod deobf 后）/ MCP 名（dev runClient）——两个都得试，否则 dev 下注入失败
	private static final String METHOD_PROCESS_SRG = "func_147347_a";  // SRG: processPlayer(C03PacketPlayer)
	private static final String METHOD_PROCESS_MCP = "processPlayer";  // dev: MCP
	private static final String HOOK_CLASS = "com/hbm/core/HbmAntiFlyHook";
	private static final String HOOK_METHOD = "shouldSkipAntiFly";

	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass) {
		if(basicClass == null) return null;
		String dotName = name.replace('/', '.');
		boolean prod = dotName.equals(PROD_NH);
		if(!dotName.equals(DEV_NH) && !prod) return basicClass;

		try {
			// prod：先把 notch 字节 deobf 成 SRG，再统一走 SRG 匹配
			if(prod) basicClass = deobfuscate(basicClass);
			return patchProcessPlayer(basicClass);
		} catch(Throwable t) {
			System.err.println("[hbmcore] anti-fly transformer FAILED: " + t);
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

	private byte[] patchProcessPlayer(byte[] classBytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);

		MethodNode m = findMethod(cn, METHOD_PROCESS_SRG, METHOD_PROCESS_MCP);
		if(m == null) throw new IllegalStateException("method not found: " + METHOD_PROCESS_SRG);

		// 定位 `bipush 80 → if_icmple <skip>`（floatingTickCount > 80 判断）及其跳转目标 label
		JumpInsnNode icmple = findBipush80Icmple(m);
		if(icmple == null) throw new IllegalStateException("bipush 80 + if_icmple not found in " + METHOD_PROCESS_SRG);

		// 注入：if_icmple 之后、kick 分支之前
		//   ALOAD 0                      → this (NetHandlerPlayServer)
		//   INVOKESTATIC shouldSkipAntiFly
		//   IFNE <icmple.label>          → 0 重力，跳回正常流程，跳过 warn+kick
		InsnList inject = new InsnList();
		inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
		inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				HOOK_CLASS, HOOK_METHOD,
				"(L" + NH + ";)Z"));
		inject.add(new JumpInsnNode(Opcodes.IFNE, icmple.label));
		m.instructions.insert(icmple, inject);

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);

		System.out.println("[hbmcore] injected 0-gravity anti-fly exemption into " + METHOD_PROCESS_SRG
				+ " (before kick branch)");
		return cw.toByteArray();
	}

	// 找 `bipush 80` 紧跟（跳过 line/label/frame 噪声）的 `if_icmple` 跳转
	// 这是 floatingTickCount > 80 的唯一判断点（anchor 到 kick 分支的入口）
	private static JumpInsnNode findBipush80Icmple(MethodNode m) {
		AbstractInsnNode[] arr = m.instructions.toArray();
		for(int i = 0; i < arr.length; i++) {
			AbstractInsnNode a = arr[i];
			if(!(a instanceof IntInsnNode) || a.getOpcode() != Opcodes.BIPUSH
					|| ((IntInsnNode) a).operand != 80) {
				continue;
			}
			// bipush 80 之后跳过 line/label/frame，找 if_icmple
			AbstractInsnNode next = a.getNext();
			while(next != null) {
				if(next instanceof LineNumberNode || next instanceof LabelNode || next instanceof FrameNode) {
					next = next.getNext();
					continue;
				}
				break;
			}
			if(next instanceof JumpInsnNode && next.getOpcode() == Opcodes.IF_ICMPLE) {
				return (JumpInsnNode) next;
			}
		}
		return null;
	}

	private static MethodNode findMethod(ClassNode cn, String... names) {
		for(String name : names) {
			for(MethodNode m : cn.methods) if(m.name.equals(name)) return m;
		}
		return null;
	}
}
