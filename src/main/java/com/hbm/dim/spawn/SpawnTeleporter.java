package com.hbm.dim.spawn;

import java.util.ArrayList;
import java.util.List;

import com.hbm.blocks.machine.Spotlight;
import com.hbm.config.SpaceConfig;
import com.hbm.dim.CelestialTeleporter;
import com.hbm.dim.orbit.OrbitalStation;
import com.hbm.main.MainRegistry;
import com.hbm.main.StructureManager;

import com.hbm.tileentity.machine.TileEntityOrbitalStation;
import com.hbm.tileentity.network.TileEntityPylonBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

// ==== [modpack] 出生维度首次加入逻辑：传送 + 设出生点 + 搭建空间站
// 多人网格出生：每个玩家一个网格（参考 HBM 原本近地轨道站网格 STATION_SIZE=1024）
// 玩家离开自己网格会掉进 Duna（见 ModEventHandler 出生维度掉出检查）
public class SpawnTeleporter {

	public static final int SPAWN_Y = 100;
	public static final String PENDING_SPAWN_TAG = "hbmcore_pendingSpawnSet";
	// 网格大小复用 HBM 原本近地轨道站数据
	public static final int GRID_SIZE = OrbitalStation.STATION_SIZE;

	// ==== [modpack] 空间站结构对齐：出生点(网格中心)落在站体局部 (STATION_SPAWN_TILE_X, STATION_SPAWN_TILE_Z) 格子
	// 甲板在站体局部 y=1；STATION_BUILD_Y 让甲板(world y=STATION_BUILD_Y+1)正好在玩家脚下一格(world y=SPAWN_Y-1)
	// 局部 (5,4) 是用户放金块标记的出生格（金块已从结构 nbt 移除，但出生格按它对齐）；改这三个常量可整体平移/升降站体
	private static final int STATION_SPAWN_TILE_X = 5;
	private static final int STATION_SPAWN_TILE_Z = 4;
	private static final int STATION_BUILD_Y = SPAWN_Y - 2;

	private static final String GRID_X_TAG = "hbmcore_spawnGridX";
	private static final String GRID_Z_TAG = "hbmcore_spawnGridZ";

	// ==== [modpack] 当前复活维度（模组维护）：首次加入=SPAWN，降落=DUNA，睡床=床所在维度
	public static final String RESPAWN_DIM_TAG = "hbmcore_respawnDim";

	// ==== [modpack] 是否首次加入：还没被打过 respawnDim 标记 = 没经历过 teleportToSpawn
	public static boolean isFirstJoin(EntityPlayer player) {
		return !player.getEntityData().hasKey(RESPAWN_DIM_TAG);
	}

	public static void setRespawnDimension(EntityPlayer player, int dim) {
		player.getEntityData().setInteger(RESPAWN_DIM_TAG, dim);
	}

	public static int getRespawnDim(EntityPlayer player) {
		return player.getEntityData().getInteger(RESPAWN_DIM_TAG);
	}

	// ==== [modpack] 重生维度解析（三个 WorldProvider 共用）：
	// 1. 存储复活维度 = SPAWN → 空间站（网格假床，不验方块）
	// 2. 存储维度非 SPAWN → 预加载该维度验证床方块 → 有效回床维度（异地复活）
	// 3. 没床 → hasLanded ? Duna : SPAWN
	public static int resolveRespawnDimension(EntityPlayerMP player) {
		int respawnDim = getRespawnDim(player);
		int result;

		if(respawnDim == SpaceConfig.spawnDimension) {
			result = SpaceConfig.spawnDimension;
		} else {
			ChunkCoordinates bed = player.getBedLocation(respawnDim);
			boolean bedValid = false;
			if(bed != null) {
				// 预加载存储维度（正是目的地，非无用加载）→ 验证床方块是否还在
				WorldServer ws = MinecraftServer.getServer().worldServerForDimension(respawnDim);
				bedValid = EntityPlayer.verifyRespawnCoordinates(ws, bed, false) != null;
			}
			if(bedValid) {
				result = respawnDim;
			} else if(hasLanded(player)) {
				result = SpaceConfig.dunaDimension;
			} else {
				result = SpaceConfig.spawnDimension;
			}
		}

		// [perf] 诊断：维度解析输入/输出（重生 bug 排查）
		MainRegistry.logger.info("[hbmcore] resolveRespawnDimension: respawnDim=" + respawnDim + ", bed=" + player.getBedLocation(respawnDim)
				+ ", hasLanded=" + hasLanded(player) + " → " + result);
		return result;
	}

	// ==== [modpack] 重生位置：由 coremod 注入点在 vanilla loadChunk 之前调用，完全覆盖原版位置判定。
	// 返回 null = 没有可用目标（真正无法处理时，走 vanilla 世界出生点兜底）。
	// 不再区分"原版已放床/未放"——这个函数就是我们唯一的位置决策点。
	public static ChunkCoordinates resolveRespawnPosition(EntityPlayerMP player) {
		int finalDim = player.dimension; // vanilla 已解析出的最终维度

		// 只处理本模组玩家（首次加入 teleportToSpawn 会打 respawnDim 标记）
		if (!player.getEntityData().hasKey(RESPAWN_DIM_TAG)) return null;

		// 1) 在最终维度有有效床 → 回床（床坐标就是我们算的，不依赖原版放）
		ChunkCoordinates bed = player.getBedLocation(finalDim);
		if (bed != null && EntityPlayer.verifyRespawnCoordinates(player.worldObj, bed, false) != null) {
			MainRegistry.logger.info("[hbmcore] resolveRespawnPosition: finalDim=" + finalDim + ", bed=" + bed + " → use bed");
			return bed;
		}

		// 2) 没床 → 模组规则
		ChunkCoordinates result = null;

		if (finalDim == SpaceConfig.spawnDimension) {
			ensureGrid(player);
			int sx = getSpawnX(player);
			int sz = getSpawnZ(player);
			int sy = findSafeSpawnY(player.worldObj, sx, sz);
			result = new ChunkCoordinates(sx, sy, sz);
		} else if (finalDim == SpaceConfig.dunaDimension && hasLanded(player)) {
			int lx = getLandX(player);
			int ly = getLandY(player);
			int lz = getLandZ(player);
			if (lx != 0 || ly != 0 || lz != 0) {
					// 床遗失警告：玩家睡过床（有床记录）但床方块已不存在 → 回降落点
					boolean hadBed = (bed != null) || (player.getBedLocation(getRespawnDim(player)) != null);
					if (hadBed) {
						player.addChatMessage(new net.minecraft.util.ChatComponentTranslation("tile.bed.notValid"));
					}
				result = new ChunkCoordinates(lx, ly, lz);
			}
		}

		MainRegistry.logger.info("[hbmcore] resolveRespawnPosition: finalDim=" + finalDim
				+ ", bed=" + bed + ", hasLanded=" + hasLanded(player)
				+ " → " + (result == null ? "null" : result.posX + "," + result.posY + "," + result.posZ));
		return result;
	}

	public static void teleportToSpawn(EntityPlayer player) {
		WorldServer spawnWorld = MinecraftServer.getServer().worldServerForDimension(SpaceConfig.spawnDimension);

		// 分配网格（UUID 稳定映射 → 每个玩家固定在自家网格，多人分散）
		int[] grid = getGridFor(player);
		player.getEntityData().setInteger(GRID_X_TAG, grid[0]);
		player.getEntityData().setInteger(GRID_Z_TAG, grid[1]);
		// 首次加入 → 复活维度锚定空间站
		setRespawnDimension(player, SpaceConfig.spawnDimension);

		int spawnX = grid[0] * GRID_SIZE + GRID_SIZE / 2;
		int spawnZ = grid[1] * GRID_SIZE + GRID_SIZE / 2;

		// ==== [modpack] 在出生网格中心搭建空间站结构（含逃生舱停靠）
		// 出生石头不再需要：站体甲板就在 (spawnX, SPAWN_Y-1, spawnZ)，玩家正好落在甲板上
		buildSpawnStation(spawnWorld, spawnX, spawnZ);

		// 出生维度的世界出生点设为网格中心站体甲板（无床时的兜底）
		spawnWorld.getWorldInfo().setSpawnX(spawnX);
		spawnWorld.getWorldInfo().setSpawnY(SPAWN_Y);
		spawnWorld.getWorldInfo().setSpawnZ(spawnZ);

		// ==== [modpack] 首次加入已由 onPlayerLoading 把 dimension 预置成 SPAWN（登录直达，不闪主世界）。
		// 此时玩家已在 SPAWN 维度 → CelestialTeleporter.teleport 会因同维度早退（if(entity.dimension == dim) return），
		// 不能再用跨维度传送落位，改由这里直接设置位置。
		// +0.5 让玩家落在出生格中心（整数坐标会落在 4 格交界处，卡在 4 个方块中间）
		if(player.dimension == SpaceConfig.spawnDimension) {
			// 对齐 CelestialTeleporter.placeInPortal 的 SPAWN 特判：面朝正东（yaw -90，正南为 0）
			// 先设 yaw 再 setPositionAndUpdate（setPlayerLocation 用当前 rotationYaw 下发 S08）
			player.rotationYaw = -90.0F;
			if(player instanceof EntityPlayerMP) {
				((EntityPlayerMP) player).setPositionAndUpdate(spawnX + 0.5D, SPAWN_Y, spawnZ + 0.5D);
			} else {
				player.setPosition(spawnX + 0.5D, SPAWN_Y, spawnZ + 0.5D);
			}
			// 清掉"脱离卡死"上抛速度，防止太空 0 重力下出生后飞起来
			player.motionX = 0;
			player.motionY = 0;
			player.motionZ = 0;
		} else {
			// 兜底：非 SPAWN（异常路径）→ 走跨维度传送
			CelestialTeleporter.teleport(player, SpaceConfig.spawnDimension, spawnX + 0.5D, SPAWN_Y, spawnZ + 0.5D, false);
		}

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

	// ==== [modpack] 找网格中心的安全出生 Y：从出生点坐标出发**向上**找第一个不被占用的空间
	// （x/z 不变保持在网格内，只抬 Y 避开被方块占用的出生点）
	// 防止出生点被方块占用 → 玩家被挤出网格 → 坠落 Duna（复活立即坠火星）
	public static int findSafeSpawnY(World world, int x, int z) {
		int y = SPAWN_Y;
		// 玩家占 2 格（脚 + 头），向上找到第一个 2 格都是空气的 Y
		while(y < 254 && (!world.isAirBlock(x, y, z) || !world.isAirBlock(x, y + 1, z))) {
			y++;
		}
		return y;
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
		// 降落 → 复活维度锚定火星
		setRespawnDimension(player, com.hbm.config.SpaceConfig.dunaDimension);
	}

	public static int getLandX(EntityPlayer player) { return player.getEntityData().getInteger(LAND_X_TAG); }
	public static int getLandY(EntityPlayer player) { return player.getEntityData().getInteger(LAND_Y_TAG); }
	public static int getLandZ(EntityPlayer player) { return player.getEntityData().getInteger(LAND_Z_TAG); }

	// ==== [modpack] 在出生网格中心搭建空间站结构 + 停靠逃生舱
	// build() 的 (x,y,z)：x/z 为结构中心（center=true 自动居中），y 为结构底部
	// 站体 23×7×19，接口核心局部 (14,0,4)；出生格局部 (STATION_SPAWN_TILE_X, STATION_SPAWN_TILE_Z) 正好落在网格中心
	public static void buildSpawnStation(World world, int centerX, int centerZ) {
		int sizeX = StructureManager.spawn_station.getSizeX();
		int sizeZ = StructureManager.spawn_station.getSizeZ();

		int buildX = centerX - STATION_SPAWN_TILE_X + sizeX / 2;
		int buildZ = centerZ - STATION_SPAWN_TILE_Z + sizeZ / 2;
		int buildY = STATION_BUILD_Y;

		// 灯亮着放进结构（默认 disableOnGeneration=true 会放成灭灯；这里打开让玩家能看清站体，要"破烂熄灯"改成 true）
		Spotlight.disableOnGeneration = false;
		StructureManager.spawn_station.build(world, buildX, buildY, buildZ);
		Spotlight.disableOnGeneration = true;

		// ==== [modpack] 接线器连线：
		// v2 结构（导出时连接已存相对坐标，build 里平移回正确位置）→ 作者在游戏里怎么连的线原样保留；
		// v1 旧结构（连接存绝对坐标，迁到新网格作废）→ 兜底：清掉过期连接按最近邻重连
		if(StructureManager.spawn_station.getVersion() < 2) {
			wireSpawnConnectors(world, buildX, buildY, buildZ);
		}

		// 结构只能放方块，逃生舱实体由这里补上（扫描找接口核心 → 停靠）
		preDockEscapePod(world, centerX, buildY, centerZ);
	}

	// ==== [modpack] 结构生成后动态接线：把站体结构里所有接线器清掉过期连接，再按最近邻两两配对连上线
	// 结构 nbt 里接线器存的 con0 是导出时的绝对坐标，迁到新网格后全部作废 → 一律清掉重建。
	// 配对规则：反复取距离最近的一对（距离 ≤ 双方 maxWireLength），互连后移出；奇数/太远的留着不连。
	// 这样未来任何带接线器的结构（重新导出的 nbt）生成后都会自动连好线，不依赖硬编码坐标。
	private static void wireSpawnConnectors(World world, int buildX, int buildY, int buildZ) {
		int sizeX = StructureManager.spawn_station.getSizeX();
		int sizeY = StructureManager.spawn_station.getSizeY();
		int sizeZ = StructureManager.spawn_station.getSizeZ();

		List<TileEntityPylonBase> pylons = new ArrayList<>();

		for(int bx = 0; bx < sizeX; bx++) {
			for(int by = 0; by < sizeY; by++) {
				for(int bz = 0; bz < sizeZ; bz++) {
					TileEntity te = world.getTileEntity(buildX - sizeX / 2 + bx, buildY + by, buildZ - sizeZ / 2 + bz);
					if(te instanceof TileEntityPylonBase) {
						pylons.add((TileEntityPylonBase) te);
					}
				}
			}
		}

		// 清掉 nbt 里残留的过期连接
		for(TileEntityPylonBase p : pylons) p.getConnected().clear();

		// 最近邻两两配对（不调 addConnection：节点首 tick 才创建，此刻 getNode 会返回 null 导致 NPE）
		while(pylons.size() >= 2) {
			TileEntityPylonBase bestA = null;
			TileEntityPylonBase bestB = null;
			double bestDist = Double.MAX_VALUE;

			for(int i = 0; i < pylons.size(); i++) {
				for(int j = i + 1; j < pylons.size(); j++) {
					TileEntityPylonBase a = pylons.get(i);
					TileEntityPylonBase b = pylons.get(j);
					double maxLen = Math.min(a.getMaxWireLength(), b.getMaxWireLength());
					double dist = pylonDist(a, b);
					if(dist <= maxLen && dist < bestDist) {
						bestDist = dist;
						bestA = a;
						bestB = b;
					}
				}
			}

			if(bestA == null) break; // 剩下的距离都超过线长

			wirePair(bestA, bestB);
			pylons.remove(bestA);
			pylons.remove(bestB);
		}
	}

	private static double pylonDist(TileEntityPylonBase a, TileEntityPylonBase b) {
		Vec3 pa = a.getConnectionPoint();
		Vec3 pb = b.getConnectionPoint();
		double dx = pb.xCoord - pa.xCoord;
		double dy = pb.yCoord - pa.yCoord;
		double dz = pb.zCoord - pa.zCoord;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// 互连两个接线器（复制 ItemWiring.onItemUse case 0 的动作），标记存档 + 下发客户端
	private static void wirePair(TileEntityPylonBase a, TileEntityPylonBase b) {
		a.getConnected().add(new int[]{b.xCoord, b.yCoord, b.zCoord});
		b.getConnected().add(new int[]{a.xCoord, a.yCoord, a.zCoord});

		a.markDirty();
		b.markDirty();

		if(a.getWorldObj() instanceof WorldServer) {
			WorldServer ws = (WorldServer) a.getWorldObj();
			ws.getPlayerManager().markBlockForUpdate(a.xCoord, a.yCoord, a.zCoord);
			ws.getPlayerManager().markBlockForUpdate(b.xCoord, b.yCoord, b.zCoord);
		}
	}

	// ==== [modpack] 预停泊：空间站结构放置后调用，扫描结构方块范围找接口，停靠一台逃生舱
	// 结构只能放方块不能放实体；逃生舱实体由这里补上
	public static void preDockEscapePod(World world, int centerX, int centerY, int centerZ) {
		// 站体一般几十格内，扫描 32×32×32 足够覆盖一个接口（多块结构，含 dummy）
		// [perf] 临时监测：扫描耗时（getTileEntity 可能触发 chunk 加载）
		long t0 = System.nanoTime();
		int scanned = 0;
		for(int x = centerX - 32; x <= centerX + 32; x++) {
			for(int z = centerZ - 32; z <= centerZ + 32; z++) {
				for(int y = centerY; y <= centerY + 32; y++) {
					scanned++;
					TileEntity te = world.getTileEntity(x, y, z);
					if(te instanceof TileEntityOrbitalStation) {
						TileEntityOrbitalStation port = (TileEntityOrbitalStation) te;
						// ==== [modpack] 结构里接口核心是 orbital_station_port meta=12：isCore() 只认 orbital_station 方块会漏掉
						// SPAWN 维度只有这一个 TileEntityOrbitalStation（meta 12 才会创建它），直接停靠逃生舱
						port.spawnEscapePod();
						MainRegistry.logger.info("[perf] preDockEscapePod found core at (" + x + "," + y + "," + z + ") in " + ((System.nanoTime() - t0) / 1000000) + "ms, scanned=" + scanned);
						return;
					}
				}
			}
		}
		com.hbm.main.MainRegistry.logger.info("[perf] preDockEscapePod no core found, scanned=" + scanned + " took " + ((System.nanoTime() - t0) / 1000000) + "ms");
	}

}
