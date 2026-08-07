package com.hbm.compat;

import java.io.File;

import com.hbm.main.MainRegistry;

/**
 * [modpack] WirelessCraftingTerminal(无线合成终端)性能修复。
 * 问题:VersionCheckHandler 每玩家 tick 发 HTTP 查版本,spark 报告 fYIlCDtXRl 显示约 1.2% 服务端消耗。
 * 修复:doVersionCheck=false,关闭版本检查。
 * 注:WCT 换版本后若配置路径变化,此代码会安全跳过(文件不存在/key未找到),见日志。
 */
public class WirelessCraftingTerminalFix implements IModCompat {

	@Override
	public void apply(File configDir) {
		File cfg = new File(configDir, "WirelessCraftingTerminal.cfg");
		CompatConfigUtil.setKeyValue(
				cfg,
				"B:doVersionCheck",
				"false",
				"perf: version check HTTP every player tick (B6)",
				MainRegistry.logger);
	}
}
