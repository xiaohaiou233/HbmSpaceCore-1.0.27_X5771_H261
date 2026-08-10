package com.hbm.core;

import com.hbm.config.SpaceConfig;
import com.hbm.dim.spawn.SpawnTeleporter;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChunkCoordinates;

// ==== [modpack] 重生回调（被 HbmRespawnTransformer 注入到 vanilla respawnPlayer）
// 注入点 A：resolveRespawnDimensionFor — 维度解析后强制覆盖为我们的维度（防止 attemptingSleep 泄露跳过我们的逻辑）
// 注入点 B：onVanillaRespawnFallback — 床验证块后、loadChunk 前，完全覆盖原版位置判定
public class HbmRespawnHook {

	// ═══ 注入点 A：维度覆盖 ═══
	// 调用时机：vanilla 做完 canRespawnHere/getRespawnDimension 之后、getBedLocation 之前
	// 返回值写入 respawnPlayer 的局部变量 p_72368_2_（最终维度）
	public static int resolveRespawnDimensionFor(EntityPlayerMP oldPlayer) {
		// 非本模组玩家 → 不干预
		if (!oldPlayer.getEntityData().hasKey(SpawnTeleporter.RESPAWN_DIM_TAG)) {
			return oldPlayer.dimension;
		}
		int ourDim = SpawnTeleporter.resolveRespawnDimension(oldPlayer);
		System.out.println("[hbmcore] dimension override: " + oldPlayer.dimension + " → " + ourDim);
		return ourDim;
	}

	// ═══ 注入点 B：位置覆盖 ═══
	// 调用时机：vanilla 已创建并 clonePlayer 新玩家、床 if/else 跑完、loadChunk 之前
	public static void onVanillaRespawnFallback(EntityPlayerMP newPlayer) {
		try {
			System.out.println("[hbmcore] position hook: dim=" + newPlayer.dimension
					+ ", pos=(" + (int)newPlayer.posX + "," + (int)newPlayer.posY + "," + (int)newPlayer.posZ
					+ "), hasRespawnDimTag=" + newPlayer.getEntityData().hasKey(SpawnTeleporter.RESPAWN_DIM_TAG));

			if (!newPlayer.getEntityData().hasKey(SpawnTeleporter.RESPAWN_DIM_TAG)) return;

			ChunkCoordinates target = SpawnTeleporter.resolveRespawnPosition(newPlayer);
			if (target == null) {
				System.out.println("[hbmcore] position hook: no target");
				return;
			}

			// ==== [modpack] 仅 SPAWN 重生面朝正东（yaw -90，正南为 0）；其他维度重生保持原样
			float yaw = newPlayer.dimension == SpaceConfig.spawnDimension ? -90.0F : 0.0F;
			newPlayer.setLocationAndAngles(target.posX + 0.5D, target.posY + 0.1D, target.posZ + 0.5D, yaw, 0.0F);

			// [debug] 诊断重生时速度/姿态（B4 上飘调查，看 setVelocity 前 motion 实际值）
			System.out.println("[hbmcore] position hook: before velocity clear, motion=("
					+ newPlayer.motionX + "," + newPlayer.motionY + "," + newPlayer.motionZ + ")"
					+ " isJumping=" + newPlayer.isJumping + " onGround=" + newPlayer.onGround);

			// ==== [modpack] 重生后清零速度（B4：太空维度重生后 y 轴速度不为 0 会飘起）
			// vanilla 重生流程本身不给新玩家 motion，但 0 重力维度下任何残留/碰撞产生的 motionY
			// 都不会自然衰减（0 重力逻辑 motionY *= 0.91 只衰减不归零；isJumping 时还 +0.01 持续上飘）。
			// setVelocity 服务端清零，客户端由位置包权威纠正。
			newPlayer.setVelocity(0, 0, 0);
			System.out.println("[hbmcore] position hook: set to (" + target.posX + "," + target.posY + "," + target.posZ + ")");
		} catch (Throwable t) {
			System.err.println("[hbmcore] position hook failed: " + t);
			t.printStackTrace();
		}
	}
}
