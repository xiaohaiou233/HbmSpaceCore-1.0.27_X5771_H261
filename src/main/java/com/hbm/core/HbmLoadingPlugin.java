package com.hbm.core;

import java.util.Map;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

// ==== [modpack] coremod 入口：把 HbmRespawnTransformer 注册进 FML
// 发现方式（1.7.10 只有 manifest 这一种，无注解/classpath 自动发现）：
//   prod（整合包）：FML 扫 mods/ 目录里 jar 的 META-INF/MANIFEST.MF，读 FMLCorePlugin 属性
//   dev（runClient）：ForgeGradle 扫 classpath 目录/jar 的 META-INF/MANIFEST.MF
//     （见 src/main/resources/META-INF/MANIFEST.MF），读到后设 fml.coreMods.load 属性
public class HbmLoadingPlugin implements IFMLLoadingPlugin {

	@Override
	public String[] getASMTransformerClass() {
		// ==== [modpack] 2026-08-08：并入原独立 FullscreenFix-1.0.0.jar 的两个 transformer（compat 包）
		// NeiFixMinecraftTransformer：屏蔽独占全屏切换（防 GL 上下文重建/驱动挂起）
		// NeiFixTessellatorTransformer：Tessellator.draw 注入 glFlush（防 GPU 渲染命令积压）
		// 两者在 transform() 内检查 NeiFix.isNeiEnabled()，无 NEI 环境直接跳过。
		// ==== [modpack] 2026-08-11：移除 HbmAntiStuckTransformer
		// B4 上飘根因调查确认 func_145771_j（"脱离卡死"）在客户端 EntityPlayerSP 被覆写为水平推挤
		// （motionX/Z ±0.1，从不写 motionY），不是上抛源 → 守卫多余，已移除。
		// 上抛真根因在 mod 0 重力逻辑（ModEventHandler.onLivingUpdate），已直接修源码。
		return new String[] {
				"com.hbm.core.HbmRespawnTransformer",
				"com.hbm.core.HbmDeathDropTransformer",
				"com.hbm.core.HbmAntiFlyTransformer",
				"com.hbm.compat.NeiFixMinecraftTransformer",
				"com.hbm.compat.NeiFixTessellatorTransformer"
		};
	}

	@Override
	public String getModContainerClass() {
		return null; // 无独立 ModContainer，coremod 与主 @Mod 同 jar
	}

	@Override
	public String getSetupClass() {
		return null;
	}

	@Override
	public void injectData(Map<String, Object> data) {
		// 暂无需要注入的启动数据
	}

	@Override
	public String getAccessTransformerClass() {
		return null; // AT 已有独立 HBM_at.cfg，这里不需要额外的 AT 类
	}
}
