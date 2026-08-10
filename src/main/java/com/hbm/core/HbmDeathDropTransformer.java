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
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import cpw.mods.fml.common.asm.transformers.deobf.FMLRemappingAdapter;

// ==== [modpack] coremod 注入器：盔甲槽死亡保留
// 两个注入点：
//   A) EntityPlayerMP.onDeath（prod: mw.func_70645_a / dev: onDeath）—— 服务端玩家实际死亡走这里
//      （EntityPlayerMP 完整覆盖了 onDeath 且不调 super，玩家死亡不会进基类 EntityPlayer.onDeath）
//      锚点：this.inventory.dropAllItems() 调用之前 → 置空盔甲槽后主背包照常掉、盔甲不掉
//   B) EntityPlayer.onDeath（prod: yz.func_70645_a / dev: onDeath）—— 基类，客户端 EntityPlayerSP 继承
//      vanilla dropAllItems 无 isRemote 守卫，客户端也会跑 → 不置空会吐幻影盔甲掉落物
//      锚点：ForgeHooks.onLivingDeath 检查之后、super.onDeath 之前
//
// ==== [modpack] 混淆处理（2026-08-08 修复，见 docs/coremod-obfuscation-fix.md）
// 与 HbmRespawnTransformer 相同：整合包 prod 下 transformer 收到 notch 字节（方法名 "a"），
// 先 FMLRemappingAdapter deobf 成 SRG 字节，再统一用 SRG 方法名/类名匹配。
public class HbmDeathDropTransformer implements IClassTransformer {

	private static final String DEV_EP    = "net.minecraft.entity.player.EntityPlayer";
	private static final String PROD_EP   = "yz";  // notch: EntityPlayer
	private static final String DEV_EMP   = "net.minecraft.entity.player.EntityPlayerMP";
	private static final String PROD_EMP  = "mw";  // notch: EntityPlayerMP
	private static final String ELB       = "net/minecraft/entity/EntityLivingBase";
	private static final String EP        = "net/minecraft/entity/player/EntityPlayer";
	private static final String EMP       = "net/minecraft/entity/player/EntityPlayerMP";
	private static final String INV       = "net/minecraft/entity/player/InventoryPlayer";
	// SRG 名（prod deobf 后）/ MCP 名（dev runClient）——两个都得试，否则 dev 下注入失败（见 docs/coremod-obfuscation-fix.md）
	private static final String METHOD_ON_DEATH_SRG = "func_70645_a";  // SRG: onDeath
	private static final String METHOD_ON_DEATH_MCP = "onDeath";       // dev: MCP
	private static final String METHOD_DROP_ALL_SRG = "func_70436_m";  // SRG: dropAllItems
	private static final String METHOD_DROP_ALL_MCP = "dropAllItems";  // dev: MCP
	private static final String HOOK_CLASS = "com/hbm/core/HbmDeathDropHook";

	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass) {
		if(basicClass == null) return null;
		String dotName = name.replace('/', '.');
		boolean isEP  = dotName.equals(DEV_EP)  || dotName.equals(PROD_EP);
		boolean isEMP = dotName.equals(DEV_EMP) || dotName.equals(PROD_EMP);
		if(!isEP && !isEMP) return basicClass;
		boolean prod = dotName.equals(PROD_EP) || dotName.equals(PROD_EMP);

		try {
			// prod：先把 notch 字节 deobf 成 SRG，再统一走 SRG 匹配
			if (prod) basicClass = deobfuscate(basicClass);
			if(isEMP) {
				return patchEntityPlayerMPOnDeath(basicClass);
			} else {
				return patchEntityPlayerOnDeath(basicClass);
			}
		} catch(Throwable t) {
			System.err.println("[hbmcore] armor-drop transformer FAILED: " + t);
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

	// 注入点 B：基类 EntityPlayer（客户端 EntityPlayerSP 用它防幻影掉落）
	private byte[] patchEntityPlayerOnDeath(byte[] classBytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);

		MethodNode m = findMethod(cn, METHOD_ON_DEATH_SRG, METHOD_ON_DEATH_MCP);
		if(m == null) throw new IllegalStateException("method not found: " + METHOD_ON_DEATH_SRG);

		// onDeath 里唯一的 super 调用：INVOKESPECIAL EntityLivingBase.onDeath
		MethodInsnNode superCall = findSuperOnDeathCall(m, ELB, METHOD_ON_DEATH_SRG, METHOD_ON_DEATH_MCP);
		if(superCall == null) throw new IllegalStateException("super.onDeath call not found");

		AbstractInsnNode anchor = walkBackPastArgs(superCall);
		if(anchor == null) throw new IllegalStateException("anchor not found");

		m.instructions.insert(anchor, buildHook());

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);

		System.out.println("[hbmcore] injected armor-drop hook into EntityPlayer." + METHOD_ON_DEATH_SRG);
		return cw.toByteArray();
	}

	// 注入点 A：EntityPlayerMP（服务端玩家实际死亡路径）
	private byte[] patchEntityPlayerMPOnDeath(byte[] classBytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);

		MethodNode m = findMethod(cn, METHOD_ON_DEATH_SRG, METHOD_ON_DEATH_MCP);
		if(m == null) throw new IllegalStateException("method not found: " + METHOD_ON_DEATH_SRG);

		// onDeath 里唯一的 this.inventory.dropAllItems() 调用
		MethodInsnNode drop = findDropAllItemsCall(m, INV, METHOD_DROP_ALL_SRG, METHOD_DROP_ALL_MCP);
		if(drop == null) throw new IllegalStateException("dropAllItems call not found");

		// 精确回退参数链：getfield inventory → aload_0，停在 aload_0 上（链起点，栈空）
		AbstractInsnNode anchor = walkBackToArgStart(drop);
		if(anchor == null) throw new IllegalStateException("dropAllItems arg start not found");

		m.instructions.insertBefore(anchor, buildHook());

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);

		System.out.println("[hbmcore] injected armor-drop hook into EntityPlayerMP." + METHOD_ON_DEATH_SRG
				+ " before " + METHOD_DROP_ALL_SRG);
		return cw.toByteArray();
	}

	private static InsnList buildHook() {
		InsnList inject = new InsnList();
		inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
		inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				HOOK_CLASS, "saveArmorBeforeDrop",
				"(L" + EP + ";)V"));
		return inject;
	}

	private static MethodNode findMethod(ClassNode cn, String... names) {
		for(String name : names) {
			for(MethodNode m : cn.methods) if(m.name.equals(name)) return m;
		}
		return null;
	}

	private static MethodInsnNode findSuperOnDeathCall(MethodNode m, String elb, String... methodNames) {
		for(AbstractInsnNode insn : m.instructions.toArray()) {
			if(insn instanceof MethodInsnNode) {
				MethodInsnNode mi = (MethodInsnNode) insn;
				if(mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.owner.equals(elb)) {
					for(String name : methodNames) if(mi.name.equals(name)) return mi;
				}
			}
		}
		return null;
	}

	private static MethodInsnNode findDropAllItemsCall(MethodNode m, String inv, String... methodNames) {
		for(AbstractInsnNode insn : m.instructions.toArray()) {
			if(insn instanceof MethodInsnNode) {
				MethodInsnNode mi = (MethodInsnNode) insn;
				if(mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.owner.equals(inv)) {
					for(String name : methodNames) if(mi.name.equals(name)) return mi;
				}
			}
		}
		return null;
	}

	// 从 super 调用往回走，跳过其参数压栈 + label/line 噪声，停在 Label（ForgeHooks 分支 continue 目标）上
	private static AbstractInsnNode walkBackPastArgs(AbstractInsnNode call) {
		AbstractInsnNode cur = call.getPrevious();
		int skipped = 0;
		while(cur != null && skipped < 24) {
			if(isSkippable(cur)) { cur = cur.getPrevious(); skipped++; continue; }
			return cur;
		}
		return null;
	}

	private static boolean isSkippable(AbstractInsnNode insn) {
		if(insn instanceof LineNumberNode) return true;
		if(insn instanceof VarInsnNode) return true;
		if(insn instanceof MethodInsnNode) return true;
		return false;
	}

	// 从 dropAllItems 调用精确回退参数链：getfield(inventory) → aload_0，返回 aload_0（链起点，栈空处）
	private static AbstractInsnNode walkBackToArgStart(AbstractInsnNode call) {
		AbstractInsnNode cur = call.getPrevious();
		int walked = 0;
		while(cur != null && walked < 4) {
			if(cur instanceof LineNumberNode || cur instanceof FrameNode || cur instanceof LabelNode) {
				cur = cur.getPrevious();
				continue;
			}
			if(cur instanceof FieldInsnNode || cur instanceof VarInsnNode) {
				walked++;
				if(walked >= 2) return cur; // 已走过 getfield + aload_0，返回链起点 aload_0
				cur = cur.getPrevious();
				continue;
			}
			break;
		}
		return null;
	}
}
