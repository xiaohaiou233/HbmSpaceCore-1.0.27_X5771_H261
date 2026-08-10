# 逃生舱降 Duna 15s 卡顿 —— 完整研究记录（2026-08-08/09）

> 问题：逃生舱从 SPAWN 维度降落到 Duna，服务端传送阻塞 15s（`[perf] celestial teleport took 15642ms`）。
> 本文记录：根因调用链、vanilla 类结构、Duna 生成器类、方案 B+C 设计蓝图。
> 关联：`docs/perf-2026-08-08-multiplayer-teleport-lag.md`（现象+诊断）、`docs/research/vanilla-1.7.10-chunkload/*.javap.txt`（反编译存档）。
> 决策：用户选 **方案 B+C 根治**（预生成落点区块 + 降 Duna 单块生成成本），暂不实现。

---

## 一、根因结论

**传送 15s = 落点视距内所有区块一次性同步生成**，与 Duna 维度初始化关系很小。

- 数量 = (2R+1)² 个区块，R=服务端视距。玩家 `renderDistance:16` → **33×33 = 1089 块**。
- 单块成本 ~14ms（Duna 地形生成昂贵）→ 1089 × 14ms ≈ 15s。
- 第二次传送（Duna 已加载）仍 14.4s：**落点是新坐标，1089 块仍是全新生成**，与维度是否已初始化无关。

### 1.1 完整同步调用链（javap 反编译确认）

```
ServerConfigurationManager.transferPlayerToDimension(player, dunaDim)
  ├ mcServer.worldServerForDimension(duna)      # 首次则建 WorldServer（不生成 spawn chunks）
  ├ 发 S07PacketRespawn；oldWorld.removePlayerEntityDangerously(player)
  ├ transferEntityToWorld(player, oldDim, oldWorld, newWorld, teleporter)   # 玩家 worldObj 切到 newWorld
  └ func_72375_a(player, oldWorld)              # ← 卡顿在这
      ├ oldWorld.getPlayerManager().removePlayer(player)
      ├ getServerForPlayer()(=newWorld).thePlayerManager.addPlayer(player)
      │    └ 收集 [chunkX-R..chunkX+R]×[chunkZ-R..chunkZ+R] 全部坐标，按 ChunkCoordComparator（距离近优先）排序
      │    └ 逐个 getOrCreateChunkWatcher → new PlayerInstance
      │          └ PlayerInstance.<init> → ChunkProviderServer.loadChunk(x,z,loadedRunnable)
      │                └ 磁盘无数据 → originalLoadChunk
      │                      └ currentChunkProvider.provideChunk(x,z)   ← 同步完整地形生成
      │                      └ chunk.populateChunk(...)                 ← 同步人口填充
      └ newWorld.theChunkProviderServer.loadChunk(playerX>>4, playerZ>>4)  # 再同步加载玩家所在块
```

### 1.2 三个关键机制（反编译确认）

1. **Forge 只把"磁盘已存在"的区块异步化**（`ChunkProviderServer.loadChunk` 里 `AnvilChunkLoader.chunkExists==true` 且传了 Runnable → `ChunkIOExecutor.queueChunkLoad` 返回 null）。**全新维度恰好吃不到这个优化** → 100% 落同步生成分支。

2. **`PlayerManager.func_152622_a(int)` 是公开的运行时改视距方法**（clamp 3-20），改 `playerViewRadius` 会刷新所有 PlayerInstance → 方案 A 的钩子。

3. **IntegratedServer（单机/局域网）视距 = 客户端 `GameSettings.renderDistanceChunks`**（启动时读入），不是独立的 server.properties。

---

## 二、vanilla 类结构（反编译存档在 `docs/research/vanilla-1.7.10-chunkload/`）

### 2.1 `ServerConfigurationManager`（ServerConfigManager_javap.txt）

| 方法 | 作用 | 关键调用 |
|---|---|---|
| `transferPlayerToDimension(EntityPlayerMP, int, Teleporter)` | 跨维度传送入口 | `worldServerForDimension` → `transferEntityToWorld` → `func_72375_a` → `setPlayerLocation` |
| `func_72375_a(EntityPlayerMP, WorldServer)` | 把玩家加入新世界 PlayerManager | **newWorld.thePlayerManager.addPlayer** + `ChunkProviderServer.loadChunk(玩家所在块)` |
| `transferEntityToWorld(Entity, int, WorldServer, WorldServer, Teleporter)` | 实体转移，调 placeInPortal | `Teleporter.placeInPortal` |
| `getViewDistance()` | 读服务端视距 | 返回内部 playerViewRadius |

### 2.2 `PlayerManager`（PlayerManager_javap.txt）

| 成员 | 作用 |
|---|---|
| 字段 `playerViewRadius` | 服务端视距 R（clamp 3-20），决定 addPlayer 区块范围 |
| `addPlayer(EntityPlayerMP)` | **一次性为 (2R+1)² 个区块建 PlayerInstance**（距离近优先） |
| `getOrCreateChunkWatcher(int,int,boolean)` | 取/建 PlayerInstance |
| `PlayerInstance.<init>` | **同步 `ChunkProviderServer.loadChunk`** |
| `func_152622_a(int)` | **运行时改视距**，clamp 3-20，变更后刷新全部 PlayerInstance |
| `updatePlayerInstances()` | 每 tick 对已存在 PlayerInstance 增量发包（不负责初始生成） |
| 内部类 `ChunkCoordComparator` | 按玩家距离对区块排序（近的先加载） |

### 2.3 `ChunkProviderServer`（ChunkProviderServer_javap.txt）

`loadChunk(int,int,Runnable)` 三分支：

| 条件 | 分支 | 阻塞 |
|---|---|---|
| 已在 `loadedChunkHashMap` | 快路径直接返回 | 否 |
| `AnvilChunkLoader.chunkExists==true` 且传了 Runnable | `ChunkIOExecutor.queueChunkLoad` 返回 null | 否（异步 IO 线程） |
| 磁盘无数据 | → `originalLoadChunk(x,z)` → `provideChunk + populateChunk` | **是（主线程同步）** |

### 2.4 `WorldServer` / `MinecraftServer` / `DimensionManager`

- **1.7.10 没有 `WorldServer.initialize(IProgressUpdate)`**。`WorldServer.initialize(WorldSettings)` 只建 entityIdMap/pendingTickList 等 + `createSpawnPosition`。
- **spawn chunks 在 `MinecraftServer.initialWorldChunkLoad()`**（服务器启动，仅主世界 worldServers[0]，25×25=625 块）。**维度世界创建不走它** → 传送到 Duna 不会生成 625 个 spawn chunks。
- `MinecraftServer.worldServerForDimension(dim)`（Forge patch）：`DimensionManager.getWorld(dim)`，null 则 `initDimension(dim)` → `createWorldForDimension` → `new WorldServer`。
- `WorldServer.tick()` → `thePlayerManager.updatePlayerInstances()`（每 tick）。

---

## 三、Duna 生成链类结构（C 方案优化点）

### 3.1 `ChunkProviderDuna extends ChunkProviderCelestial`（`src/.../dim/duna/ChunkProviderDuna.java`）

构造时装配生成器：

```java
caveGenV2    = new ExperimentalCaveGenerator(2, 40, 3.0F);   // carvingScale=2, depthThreshold=40, largeCaveSize=3.0
caveGenSmall = new ExperimentalCaveGenerator(2, 12, 0.12F);
genPlateau   = new MapGenPlateau(worldObj);
oil          = new MapGenBubble(WorldConfig.dunaOilSpawn);   // setSize(8,16)
```

`getChunkPrimer(int x, int z)` 顺序：

```
generateBlocks(x,z,blocks)                        # 基岩噪声地形（ChunkProviderCelestial）
biomesForGeneration = loadBlockGeneratorData(...) # biome layers（17 层 genlayer 链）
hasPlateau   → genPlateau.func_151539_a(...)      # 台地（仅 dunaHills）
replaceBlocksForBiome(...)                        # 地表替换
hasLowlands  → caveGenV2.func_151539_a(...)       # 大洞穴（仅 dunaLowlands）
hasNotLowlands → caveGenSmall.func_151539_a(...)  # 小洞穴
oil.setMetas; oil.func_151539_a(...)              # 油泡
```

### 3.2 `ExperimentalCaveGenerator extends MapGenBase`（`src/.../dim/mapgen/ExperimentalCaveGenerator.java`）—— **最大性能热点**

**构造参数**：`(carvingScale, depthThreshold, largeCaveSize)`。Duna 用 `(2,40,3.0)`（大洞，dunaLowlands only）+ `(2,12,0.12)`（小洞）。

**每 chunk 逻辑**（`func_151538_a`）：
- 生成 `i1` 个洞穴起点（`rand.nextInt(rand.nextInt(rand.nextInt(15)+1)+1)`，7/8 概率为 0）
- 每个起点 `generateCaveNode(...)`：沿曲线挖掘，含 `currentStep==k1` 时递归分叉 ×2

**⚠️ 性能 bug：`generateCaveNode` 的 for 循环内（第80行）每次迭代 `this.perlinNoise = new PerlinFestival(seed)`**——每个洞穴步长都重建一个 512 元素 permutation 数组（Fisher-Yates 打乱）！这是巨大的重复分配。

**C 方案优化点（排序按收益）**：
1. **把 `new PerlinFestival(seed)` 提出循环**，每个 chunk 只建一次（或懒建复用）。洞穴步长内多次 noise 用同一实例。
2. **PerlinFestival 用固定 seed 但每次 `new Random(seed)` 会得到相同排列**——若多个洞穴想要不同噪声，可注入递增 seed 或随机 seed 一次。
3. `isTopBlock`/`digBlock` 里多次 `worldObj.getBiomeGenForCoords`（每格）→ 可缓存 biome 查找。
4. 降低 `caveGenV2` 的 `depthThreshold=40` 或 `largeCaveSize=3.0` → 洞穴更少/更小（观感变化）。

### 3.3 `PerlinFestival`（`src/.../dim/noise/PerlinFestival.java`）

- 构造：`permutation[512]`，`new Random(seed)` + Fisher-Yates 打乱 256 + 复制 256。
- `noise(x,y,z)`：标准 3D Perlin（8 个 grad 插值）。
- **无缓存/池**：每次 new 都重建数组 → 高频调用时是 GC 压力源。

### 3.4 `MapGenPlateau extends MapGenBase`（`src/.../dim/mapgen/MapGenPlateau.java`）

- 构造：`new NoiseGeneratorPerlin(world.rand, 4)`。
- `func_151539_a`：两趟 16×16。第一趟算 baseHeight（`getSurfaceHeight` 从 y=255 下扫找首个非空气）+ plateauAddition（噪声）。第二趟按 plateauTop 填充 stoneBlock/surfrock/fillblock。
- `applyToBiome` = dunaHills 时才生效（其他 biome continue）。
- 成本中等（2×256 次 getSurfaceHeight 扫描 + 填充循环）。

### 3.5 `WorldProviderDuna`（`src/.../dim/duna/WorldProviderDuna.java`）

- `registerWorldChunkManager`：`new WorldChunkManagerCelestial(createBiomeGenerators(seed))`。
- **biome genlayer 链 17 层**（createBiomeGenerators）：GenLayerDunaBiomes → FuzzyZoom → Zoom → DiversifyDuna → Zoom → DiversifyDuna → Zoom → DunaLowlands → Zoom → Smooth → Zoom → Smooth → FuzzyZoom → Smooth → FuzzyZoom → Zoom → VoronoiZoom（+ river 链）。每 chunk `loadBlockGeneratorData` 都过这条链。
- **C 方案优化点**：精简 genlayer 链（去掉重复 Smooth/Zoom/FuzzyZoom）→ biome 分辨率变化，可显著降 `loadBlockGeneratorData` 成本。

### 3.6 `WorldGeneratorDuna implements IWorldGenerator`（`src/.../dim/duna/WorldGeneratorDuna.java`）—— populate 阶段

- `generateDuna`：每种矿石 `DungeonToolbox.generateOre`（iron/zinc/basalt×5）+ 稀有 lantern 结构。
- 在 `populateChunk` 里同步执行。每 chunk 成本中等。

### 3.7 `ChunkProviderCelestial`（`src/.../dim/ChunkProviderCelestial.java`）

- `generateNoiseField`：4 个噪声器（firstOrder/secondOrder/thirdOrder octaves + heightOrder + perlin）+ 5×33×5 三层循环插值 → **基岩地形大头**（vanilla 同款算法）。
- `provideChunk`：`getChunkPrimer` + biome 数组 + `generateSkylightMap`。
- `populate`：`biomegenbase.decorate` + `performWorldGenSpawning`。

---

## 四、方案 B+C 设计蓝图（暂不实现）

### 方案 B：预生成落点区块

**目标**：把 1089 块同步生成从"传送当 tick"挪到"逃生舱飞行途中分帧完成"，落地时已就绪。

**关键前提**：`EntityEscapePod` 构造时 `initDest()` 就定了 `destX/destZ`（`getDestination()` 返回），落点坐标在发射前已知。

**设计**：
1. 钩子点：`EntityEscapePod.beginLandingSequence`（或 `attemptLaunch` 时）检测目标维度是 Duna。
2. 若 Duna 世界未创建 → `DimensionManager.initDimension(duna)` 预建（不含 spawn chunks，成本低）。
3. 目标落点周围 (2R+1)² 区块按"离落点近优先"分帧生成：每 tick 在 `WorldTickEvent` 里用 `worldServer.getChunkFromChunkCoords(x,z)` 生成 N 块（如 10-20 块/tick）。
4. 逃生舱升空动画 ~2-3s（40-60 tick）× 20 块 = 800-1200 块，够覆盖 1089。
5. 传送到达时落点区块已在 `loadedChunkHashMap` → `loadChunk` 走快路径，不再同步生成。

**风险**：
- 区块生成必须主线程（ChunkProviderServer 非线程安全）→ "分帧"不是真异步，是插进 tick 队列。
- 飞行时间可能不够（取决于火箭上升速度）。需实测。
- 预生成的区块若玩家改道/取消 → 浪费（可接受，区块会被保留）。

### 方案 C：降低 Duna 单块生成成本

**目标**：1089 块 × 14ms → × 2-4ms → 总耗时 3-5s（配合 B 后落到 <1s）。

**优化点排序**：
1. **`ExperimentalCaveGenerator.generateCaveNode` 把 `new PerlinFestival(seed)` 提出循环**（最大收益，纯性能无观感影响）。
2. **PerlinFestival 实例复用/懒建**（进 chunk 时建一次）。
3. **精简 WorldProviderDuna biome genlayer 链**（17 → ~8 层）→ 降 `loadBlockGeneratorData` 成本（观感微变）。
4. `MapGenPlateau.getSurfaceHeight` 扫描优化（只扫到已知地形高度）。
5. `caveGenV2` 参数调低（depthThreshold/largeCaveSize）→ 洞穴更少更小（观感明显变化，需权衡）。
6. `digBlock`/`isTopBlock` 缓存 biome（`worldObj.getBiomeGenForCoords` 每格调用）。

**注意**：1-2 是纯性能收益（不动地形观感），3-6 会改变 Duna 外观。

### 方案 A（备选，不选）：传送期间临时降视距

`runTeleport` 里 `transferPlayerToDimension` 前 `PlayerManager.func_152622_a(4)`，之后恢复。1089→81 块，15s→~1s，代价是传送瞬间视距渐显。作为 B+C 完成前的过渡可选。

---

## 五、性能预算（估算）

| 项 | 现成本 | 优化后 |
|---|---|---|
| PerlinFestival 重建 | 每洞穴步长 ×1（~512 数组） | 每 chunk ×1 |
| biome genlayer 17 层 | 每 chunk 一次 | 每 chunk 一次（层数减半） |
| 洞穴生成（2 生成器） | 每 chunk 数十次 noise | 每 chunk 一次 noise 对象 |
| 单块总成本 | ~14ms | 目标 2-4ms |
| 传送同步生成 1089 块 | ~15s | 方案B后 ~0（已预生成） |

---

## 六、复现与验证工具

- 复现：进整合包 → 逃生舱降 Duna → 观察 `[perf] celestial teleport took` 毫秒数。
- spark 采样：`/spark profiler start --timeout 60 --thread "*"` 卡顿时采。
- 存档：`docs/research/vanilla-1.7.10-chunkload/*.javap.txt`（反编译证据）。

相关：[[perf-2026-08-08-teleport-lag]] [[hbm-modpack-dev-notes]] [[gtnhlib-render-investigation]]
