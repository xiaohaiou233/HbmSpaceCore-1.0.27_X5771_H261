package com.hbm.compat;

import java.io.File;

import cpw.mods.fml.common.Loader;

/**
 * [modpack] NotEnoughItems 兼容修复（原独立 FullscreenFix-1.0.0.jar，2026-08-08 并入 HBM compat 包）。
 *
 * 问题背景：整合包（GTNH 版 NEI + AE2 等）渲染 HBM 的 3D 模型时，一帧内 Tessellator 命令量巨大，
 * 现代 Windows + RTX 显卡上 GPU 驱动来不及消费积压的命令 → 渲染超时/卡死/驱动挂起（表现为"进游戏瞬间
 * 卡 30 秒后恢复"或"日志消失后崩溃"）。此外 1.7.10 独占全屏切换会销毁/重建 OpenGL 上下文，同样触发驱动挂起。
 *
 * 修复（两个 ASM transformer，注册于 HbmLoadingPlugin）：
 *   1. NeiFixTessellatorTransformer —— Tessellator.draw 注入 GL11.glFlush()，强制刷命令缓冲，防积压。
 *   2. NeiFixMinecraftTransformer   —— 屏蔽 toggleFullscreen（不销毁 GL 上下文），防全屏切换挂起。
 *
 * 无 NEI 保护：NEI 未安装时 isNeiEnabled()=false，两个 transformer 直接跳过（不注入任何字节）。
 * 注意：这两个 transformer 是 coremod（须在 FML 启动早期注册），无法走 IModCompat.apply 的普通事件链，
 *       因此这里只提供 NEI 检测开关，transformer 注册在 HbmLoadingPlugin.getASMTransformerClass()。
 */
public class NeiFix implements IModCompat {

	/** NEI 是否已加载。transformer 在 transform() 时检查此开关，false 则跳过。 */
	private static boolean neiEnabled = false;

	public static boolean isNeiEnabled() {
		return neiEnabled;
	}

	@Override
	public void apply(File configDir) {
		// 在 FMLPreInitializationEvent 阶段检测（此时 mod 列表已确定）
		neiEnabled = Loader.isModLoaded("NotEnoughItems");
		com.hbm.main.MainRegistry.logger.info("[hbmcore][compat] NeiFix: NotEnoughItems "
				+ (neiEnabled ? "detected, ASM fixes enabled" : "NOT present, ASM fixes skipped"));
	}
}
