package com.hbm.compat;

import java.io.File;

/**
 * [modpack] 第三方 mod 兼容/修复接口。
 * Compat = 模组间互操作兼容;Fix = mod 自身 bug/性能修复。
 * 所有实现必须处理"目标 mod 不存在"的情况:配置文件不存在时安全跳过,不创建/修改任何文件。
 */
public interface IModCompat {
	/** 对目标 mod 的配置执行兼容/修复;目标 mod 未安装时安全跳过。 */
	void apply(File configDir);
}
