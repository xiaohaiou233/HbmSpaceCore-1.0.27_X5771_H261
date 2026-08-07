package com.hbm.compat;

import java.io.File;

import com.hbm.main.MainRegistry;

/**
 * [modpack] AE2(Applied Energistics 2)性能修复。
 * 问题:陨石世界生成每 tick 读盘 MeteoriteWorldGen → SpawnData.loadSpawnData → FileInputStream,
 *      spark 报告 fYIlCDtXRl 显示约 10% 服务端 tick 消耗,峰值 MSPT 达 3332ms。
 * 修复:关掉陨石世界生成 MeteoriteWorldGen=false(性能换功能,旧世界陨石保留,新世界不再生成)。
 * 注:AE2 换版本后若配置路径变化,此代码会安全跳过(文件不存在/key未找到),见日志。
 */
public class AeFix implements IModCompat {

	@Override
	public void apply(File configDir) {
		File cfg = new File(configDir, "AppliedEnergistics2" + File.separatorChar + "AppliedEnergistics2.cfg");
		CompatConfigUtil.setKeyValue(
				cfg,
				"B:MeteoriteWorldGen",
				"false",
				"perf: meteorite file IO every tick causes server lag (B6)",
				MainRegistry.logger);
	}
}
