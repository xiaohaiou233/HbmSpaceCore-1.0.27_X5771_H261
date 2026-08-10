package com.hbm.compat;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;

import com.hbm.main.MainRegistry;

// ==== [modpack] NeiFix：全屏切换屏蔽 hook（原 FullscreenFix MinecraftHooks，2026-08-08 并入）
// 被 NeiFixMinecraftTransformer 注入的字节码调用：ALOAD 0 → this(Minecraft)
// 作用：接管 Minecraft.toggleFullscreen，把全屏切换变成 no-op（保持当前窗口模式）。
//       关键：只把 isFullScreen 字段置 false，**不**调用 Display.setFullscreen ——
//       调用 Display.setFullscreen 会销毁/重建 GL 上下文（正是要避免的 RTX 驱动挂起源）。
//       用户要的就是"全屏被禁用"（按 F11 不再进全屏），保持窗口化即可。
// 安全降级：字段找不到/反射失败时静默 no-op，不崩游戏。
public class NeiFixHooks {

	private static Field fIsFullScreen;
	private static Field fDisplayWidth;
	private static Field fDisplayHeight;
	private static boolean reflectionOk = false;
	private static int toggleCount = 0;

	public static void toggleFullscreen(Minecraft mc) {
		toggleCount++;
		MainRegistry.logger.info("[hbmcore] fullscreen toggle #" + toggleCount + " suppressed (borderless mode)");

		if(!reflectionOk || fIsFullScreen == null || mc == null) return;

		try {
			fIsFullScreen.setBoolean(mc, false);
			MainRegistry.logger.info("[hbmcore] fullscreen suppressed, display="
					+ mc.displayWidth + "x" + mc.displayHeight);
		} catch(Exception e) {
			MainRegistry.logger.warn("[hbmcore] fullscreen suppression failed, falling back to vanilla: " + e.getMessage());
		}
	}

	private static Field findField(Class<?> clazz, String... names) throws NoSuchFieldException {
		return findField(clazz, null, names);
	}

	private static Field findField(Class<?> clazz, Class<?> type, String... names) throws NoSuchFieldException {
		for(String name : names) {
			try {
				Field f = clazz.getDeclaredField(name);
				if(type != null && f.getType() != type) {
					continue; // 类型不匹配，试下一个候选名
				}
				f.setAccessible(true);
				return f;
			} catch(NoSuchFieldException e) {
				// 试下一个候选名
			}
		}
		throw new NoSuchFieldException("None of " + java.util.Arrays.toString(names) + " found in " + clazz.getName());
	}

	static {
		try {
			// isFullScreen 是 Minecraft 的 boolean 私有字段：MCP 名 field_71445_n，notch 名 N
			fIsFullScreen = findField(Minecraft.class, boolean.class,
					"field_71445_n", "field_71431_Q", "field_71434_R", "field_71468_ad", "field_71455_al", "N", "field_71436_v", "isFullScreen");

			// displayWidth 是 public int：MCP 名 field_71440_d，notch 名 d
			fDisplayWidth = findField(Minecraft.class, int.class,
					"field_71440_d", "field_71443_c", "d", "field_71446_x", "displayWidth");

			// displayHeight：MCP 名 field_71443_c，notch 名 e
			fDisplayHeight = findField(Minecraft.class, int.class,
					"field_71443_c", "field_71440_d", "e", "field_71443_y", "displayHeight");

			reflectionOk = (fIsFullScreen != null);
			MainRegistry.logger.info("[hbmcore] fullscreen suppression reflection OK (isFullScreen="
					+ (fIsFullScreen != null ? fIsFullScreen.getName() : "null") + ")");
		} catch(Throwable t) {
			reflectionOk = false;
			MainRegistry.logger.warn("[hbmcore] fullscreen suppression reflection FAILED, feature disabled: " + t.getMessage());
		}
	}
}
