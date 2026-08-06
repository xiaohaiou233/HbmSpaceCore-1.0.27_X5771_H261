package com.hbm.dim.spawn;

import com.hbm.config.SpaceConfig;
import com.hbm.dim.CelestialTeleporter;
import com.hbm.dim.orbit.OrbitalStation;

import com.hbm.tileentity.machine.TileEntityOrbitalStation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

// ==== [modpack] 出生维度首次加入逻辑：传送 + 设出生点 + 出生石头
// 多人网格出生：每个玩家一个网格（参考 HBM 原本近地轨道站网格 STATION_SIZE=1024）
// 玩家离开自己网格会掉进 Duna（见 ModEventHandler 出生维度掉出检查）
public class SpawnTeleporter {

	public static final int SPAWN_Y = 100;
	public static final String PENDING_SPAWN_TAG = "hbmcore_pendingSpawnSet";
	// 网格大小复用 HBM 原本近地轨道站数据
	public static final int GRID_SIZE = OrbitalStation.STATION_SIZE;

	private static final String GRID_X_TAG = "hbmcore_spawnGridX";
	private static final String GRID_Z_TAG = "hbmcore_spawnGridZ";

	public static void teleportToSpawn(EntityPlayer player) {
		WorldServer spawnWorld = MinecraftServer.getServer().worldServerForDimension(SpaceConfig.spawnDimension);

		// 分配网格（UUID 稳定映射 → 每个玩家固定在自家网格，多人分散）
		int[] grid = getGridFor(player);
		player.getEntityData().setInteger(GRID_X_TAG, grid[0]);
		player.getEntityData().setInteger(GRID_Z_TAG, grid[1]);

		int spawnX = grid[0] * GRID_SIZE + GRID_SIZE / 2;
		int spawnZ = grid[1] * GRID_SIZE + GRID_SIZE / 2;

		// 出生点脚下放一块石头
		spawnWorld.setBlock(spawnX, SPAWN_Y - 1, spawnZ, Blocks.stone);

		// 出生维度的世界出生点设为石头位置（无床时的兜底）
		spawnWorld.getWorldInfo().setSpawnX(spawnX);
		spawnWorld.getWorldInfo().setSpawnY(SPAWN_Y);
		spawnWorld.getWorldInfo().setSpawnZ(spawnZ);

		// 排队传送（CelestialTeleporter 下一 tick 执行），grounded=false 精确落位
		CelestialTeleporter.teleport(player, SpaceConfig.spawnDimension, spawnX, SPAWN_Y, spawnZ, false);

		// 标记：传送完成后设置床位置重生点（见 ModEventHandler），死亡重生回自家网格
		player.getEntityData().setBoolean(PENDING_SPAWN_TAG, true);
	}

	// 玩家 UUID → 网格坐标（稳定、分散，多人大概率不同网格）
	private static int[] getGridFor(EntityPlayer player) {
		int range = Math.max(1, SpaceConfig.maxStationDistance / GRID_SIZE);
		long hash = player.getUniqueID().getLeastSignificantBits();
		int dX = (int)((hash & Long.MAX_VALUE) % (range * 2)) - range;
		int dZ = (int)(((hash >>> 32) & Long.MAX_VALUE) % (range * 2)) - range;
		return new int[]{dX, dZ};
	}

	// 确保玩家有网格 tag（首次加入时 teleportToSpawn 设置；重生兜底前调用以防 tag 缺失导致回错网格）
	public static void ensureGrid(EntityPlayer player) {
		if(!player.getEntityData().hasKey(GRID_X_TAG)) {
			int[] grid = getGridFor(player);
			player.getEntityData().setInteger(GRID_X_TAG, grid[0]);
			player.getEntityData().setInteger(GRID_Z_TAG, grid[1]);
		}
	}

	// 重生点 = 玩家网格中心（供 ModEventHandler 设置床位置）
	public static int getSpawnX(EntityPlayer player) {
		return player.getEntityData().getInteger(GRID_X_TAG) * GRID_SIZE + GRID_SIZE / 2;
	}

	public static int getSpawnZ(EntityPlayer player) {
		return player.getEntityData().getInteger(GRID_Z_TAG) * GRID_SIZE + GRID_SIZE / 2;
	}

	// ==== [modpack] 玩家降落数据（每玩家独立）：乘坐降落仓落到火星时设置（降落仓未做，触发处留空）
	private static final String LANDED_TAG = "hbmcore_hasLanded";
	private static final String LAND_X_TAG = "hbmcore_landX";
	private static final String LAND_Y_TAG = "hbmcore_landY";
	private static final String LAND_Z_TAG = "hbmcore_landZ";

	public static boolean hasLanded(EntityPlayer player) {
		return player.getEntityData().getBoolean(LANDED_TAG);
	}

	// 设置降落点（降落仓落地时调用；当前由 /hbmtest 测试指令触发）
	public static void setLanded(EntityPlayer player, int x, int y, int z) {
		player.getEntityData().setBoolean(LANDED_TAG, true);
		player.getEntityData().setInteger(LAND_X_TAG, x);
		player.getEntityData().setInteger(LAND_Y_TAG, y);
		player.getEntityData().setInteger(LAND_Z_TAG, z);
	}

	public static int getLandX(EntityPlayer player) { return player.getEntityData().getInteger(LAND_X_TAG); }
	public static int getLandY(EntityPlayer player) { return player.getEntityData().getInteger(LAND_Y_TAG); }
	public static int getLandZ(EntityPlayer player) { return player.getEntityData().getInteger(LAND_Z_TAG); }

	// ==== [modpack] 预停泊：空间站结构放置后调用，扫描结构方块范围找接口，停靠一台逃生舱
	// 结构只能放方块不能放实体；逃生舱实体由这里补上
	public static void preDockEscapePod(World world, int centerX, int centerY, int centerZ) {
		// 站体一般几十格内，扫描 32×32×32 足够覆盖一个接口（多块结构，含 dummy）
		for(int x = centerX - 32; x <= centerX + 32; x++) {
			for(int z = centerZ - 32; z <= centerZ + 32; z++) {
				for(int y = centerY; y <= centerY + 32; y++) {
					TileEntity te = world.getTileEntity(x, y, z);
					if(te instanceof TileEntityOrbitalStation) {
						TileEntityOrbitalStation port = (TileEntityOrbitalStation) te;
						if(port.isCore()) {
							port.spawnEscapePod();
							return;
						}
					}
				}
			}
		}
	}

}
