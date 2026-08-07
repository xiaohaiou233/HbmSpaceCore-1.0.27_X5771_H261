package com.hbm.compat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.hbm.main.MainRegistry;

/**
 * [modpack] 第三方 mod 兼容/修复注册器。
 * 在 MainRegistry.PreLoad 中调用 registerAll() 注册,再 applyAll(configDir) 逐个执行。
 * 新增修复:实现 IModCompat 后在 registerAll() 加一行。
 */
public final class ModCompatRegistry {

	private static final List<IModCompat> COMPATS = new ArrayList<>();

	private ModCompatRegistry() {}

	/** 注册所有兼容/修复。在 MainRegistry.PreLoad 中调用。 */
	public static void registerAll() {
		register(new AeFix());
		register(new WirelessCraftingTerminalFix());
	}

	public static void register(IModCompat compat) {
		COMPATS.add(compat);
	}

	public static void applyAll(File configDir) {
		for (IModCompat compat : COMPATS) {
			try {
				compat.apply(configDir);
			} catch (Throwable t) {
				// 单个修复失败不中断启动;记录后继续下一个
				MainRegistry.logger.warn("[hbmcore][compat] {} failed: {}", compat.getClass().getSimpleName(), t);
			}
		}
	}
}
