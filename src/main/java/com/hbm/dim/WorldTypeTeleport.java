package com.hbm.dim;

import com.hbm.config.SpaceConfig;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldType;

public class WorldTypeTeleport extends WorldType {

	public static WorldType martian;

	public WorldTypeTeleport(String name) {
		super(name);
	}

	public static void init() {
		// ==== [modpack] 世界类型只保留主世界：martian 世界类型不再注册（出生维度方案替代火星开局）
		// 注释掉后：世界创建界面只剩默认主世界；WorldTypeTeleport.martian 为 null，
		// 所有 `== WorldTypeTeleport.martian` 判断恒为 false（无 NPE，见 ModEventHandler 的 instanceof 守卫）
		// 恢复时取消注释下面这行即可
		// martian = new WorldTypeTeleport("martian");
	}

	public static void simplifyWorldTypes() {
		// ==== [modpack] 世界创建界面只保留主世界（Default）：原版超平坦/放大化等也不再显示
		// null 掉 WorldType.worldTypes 里非 DEFAULT 的槽位（创建界面会跳过 null）
		// 注意：会连带把 WorldType.FLAT 等静态引用变 null，但 HBM 里对它们的引用全是比较运算，
		// null 后恒为非平坦判断；整合包不用平坦世界，行为不变、无 NPE（已评估 ChunkProviderCelestial/CBT_Invasion/NBTStructure）
		WorldType[] types = WorldType.worldTypes;
		for(int i = 0; i < types.length; i++) {
			if(types[i] != null && types[i] != WorldType.DEFAULT) {
				types[i] = null;
			}
		}
	}

	public void onPlayerJoin(EntityPlayer player) {
		if(this == martian)
			CelestialTeleporter.teleport(player, SpaceConfig.dunaDimension, 0, 0, 0, true);
	}

}
