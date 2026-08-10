package com.hbm.core;

import com.hbm.dim.CelestialBody;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;

// ==== [modpack] anti-fly 豁免回调（被 HbmAntiFlyTransformer 注入到 vanilla NetHandlerPlayServer.processPlayer）
// vanilla 悬浮检测：非单机 && 非创造 && 脚下无方块 && Y位移≥-0.03125 → floatingTickCount++，连续 80 tick（4 秒）踢出
//   "Flying is not enabled on this server"。检测只看 theItemInWorldManager.isCreative()，根本不读 capabilities.allowFlying。
// 0 重力环境（轨道/太空/无重力空间站）下玩家 4 个豁免条件全不满足 → 必被踢。
// 注入点：floatingTickCount > 80 的 if_icmple 之后，0 重力时直接跳过整个 warn+kick 分支。
// 注：dev 下 this 是 NetHandlerPlayServer，这里接收它再取 playerEntity，避免在字节码里 GETFIELD playerEntity（字段 SRG 名随 prod 混淆变化）。
public class HbmAntiFlyHook {

	// 被注入字节码调用：返回 true → 跳过 anti-fly 踢人
	// 0 重力（CelestialBody.getGravity == 0）→ 豁免，与 ModEventHandler 的 0 重力判断一致
	public static boolean shouldSkipAntiFly(NetHandlerPlayServer handler) {
		try {
			EntityPlayerMP player = handler.playerEntity;
			if(player == null) return false;
			return CelestialBody.getGravity(player) == 0;
		} catch(Throwable t) {
			// 任何异常都不豁免（宁可踢人也不放行异常状态），打日志便于排查
			System.err.println("[hbmcore] HbmAntiFlyHook.shouldSkipAntiFly failed: " + t);
			t.printStackTrace();
			return false;
		}
	}
}
