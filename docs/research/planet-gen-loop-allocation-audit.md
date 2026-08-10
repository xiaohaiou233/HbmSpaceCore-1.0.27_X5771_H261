# 星球地形生成「循环内重建对象」审计（2026-08-10）

> 背景：方案 C（降 Duna 单块生成成本）已定方向。Duna 的 `ExperimentalCaveGenerator.generateCaveNode`
> for 循环内每步 `new PerlinFestival(seed)`（重建 512 元素 permutation）是已知最大性能 bug。
> 用户问：**其他星球有没有类似问题？** 派 3 个 agent 并行审计：
> ① 各星球 ChunkProvider + MapGen 类；② noise 采样器类；③ 各星球 biome GenLayer 链。
> 结论：发现 2 处 🔴 通用问题（不只 Duna），修复能惠及多个星球。

---

## 一、结论速览

| 严重度 | 问题 | 文件 | 受影响星球 | 类型 |
|---|---|---|---|---|
| 🔴 | F1 `generateCaveNode` 每 step `new PerlinFestival` | `mapgen/ExperimentalCaveGenerator.java:80` | **Moho + Duna**（3 实例） | 通用 |
| 🔴 | F2 `MapGenVNoise.getCellCenter` 每格 `new Random` + `new double[2]` | `noise/MapGenVNoise.java:86,91` | **Eve + Moho** | 通用 |
| 🟡 | F3 `MapGenPlateau` 每 chunk 2× `int[16][16]` | `mapgen/MapGenPlateau.java:31-32` | Moho + Duna | 通用 |
| 🟢 | F4 `MapGenGreg` 每 chunk `new BiomeGenBase[100]` | `mapgen/MapGenGreg.java:132-133` | Moon/Laythe/Tekto/Thatmo | 通用 |
| 🟢⚠️ | F5 `GenLayerEveSeismicPlains` 每 getInts `new Random()`（未播种） | `eve/genlayer/GenLayerEveSeismicPlains.java:21` | Eve（且未接入链=死代码） | Eve 独有 |

**关键洞察**：
- 两个 🔴 都在**通用生成器**里，改 1 个类惠及 2 个星球。
- `MapGenGreg` 的 lazy-init（`if(worldObj != w)` 每 world 建一次）是**正确范式**，可作范本。
- **无任何噪声类被客户端渲染/每帧调用**（全在 `dim/` 下，SkyProvider 的 "noise" 是 shader 贴图）→ 没有"渲染里循环 new"问题。

---

## 二、🔴 严重问题详情

### F1. `ExperimentalCaveGenerator.generateCaveNode` — 每洞穴 step 重建 PerlinFestival

```
generateCaveNode(...)                                    ExperimentalCaveGenerator.java
  for(currentStep; currentStep < totalSteps; ++currentStep)   # :69 step 级循环
    this.perlinNoise = new PerlinFestival(seed)               # :80 ← 每步重建！
```
- **分配**：`new int[512]` + `new Random` + 256 次 Fisher-Yates 打乱（PerlinFestival 构造器 768 次循环）。
- **频率**：每节点 ~98 step；`func_151538_a` 平均 ~3.5 节点/chunk + 递归分叉 ×2 → **每 chunk 约 200~2000 次构造**（探索时 4~10ms + 数 MB 垃圾）。
- **`seed` 在整个循环恒定不变** → 每次重建的 permutation **完全相同**，纯属浪费。提出循环后输出零变化。
- **影响**：Moho（`ChunkProviderMoho.java:19`）+ Duna（`ChunkProviderDuna.java:18-19` 两个实例 caveGenV2/caveGenSmall）。**Duna 受害最重**（低地/非低地 chunk 基本每 chunk 必跑）。
- **修复**：把 `new PerlinFestival(seed)` 提到 `generateCaveNode` 的循环外（每节点 1 次）；可选进一步 static `Map<Long,int[]>` 按 seed 缓存 permutation。

### F2. `MapGenVNoise.getCellCenter` — 每格 new Random + double[2]

```
func_151539_a                                            noise/MapGenVNoise.java
  for 16×16 方块 + 5×5 邻域 (48 行)                        # :32-33, :46-47 方块级循环
    getCellCenter(cellX+dx, cellZ+dz, seed)               # :86 ← 每格调用
      new Random(hash)                                    # :86
      return new double[]{centerX, centerZ}               # :91
```
- **频率**：**每 chunk 16×16×5×5 = 6400 次 getCellCenter**（Eve/Moho 大洋、熔岩海类群系几乎全量触发）→ ~12800 次对象分配/chunk。
- **修复**：字段级 Random + `setSeed(hash)`（种子一致序列不变）；double[2] 改原始局部变量；或 memo 化格中心（每 chunk 去重后仅几十个）。
- **影响**：Eve（`ChunkProviderEve.java:19`）+ Moho（`ChunkProviderMoho.java:22`）。

---

## 三、🟡/🟢 问题

### F3. `MapGenPlateau` — 每 chunk 2× int[16][16]
- `:31-32` `int[][] plateauTops/baseHeights = new int[16][16]` = 34 个小数组/chunk。
- 修复：提为实例字段复用（16×16 全量覆盖写，无需清空）。Moho + Duna。

### F4. `MapGenGreg` — 每 chunk new BiomeGenBase[100]
- `:132-133` `getBiomesForGeneration(null,...)` 传 null 让 WorldChunkManager 每 chunk 新建 100 数组。
- 修复：提为字段。4 个星球（Moon/Laythe/Tekto/Thatmo）。**MapGenGreg 其余部分是最佳范式**（:33-46 每 world 建一次 + buffer 复用）。

### F5. `GenLayerEveSeismicPlains` — 未播种 new Random()
- `:21` `new Random()` 无种子 → 分布**非确定性**（比性能更糟的正确性问题）。但**未接入 Eve 链=死代码**，可删。
- 修复：用 `this.nextInt(10)` 或播种字段 Random。

---

## 四、验证为「良性」的生成器（无需改）

| 生成器 | 说明 |
|---|---|
| `MapGenGreg` 噪声采样器 | 每 world lazy-init 一次 + buffer 复用（最佳范式） |
| `MapGenTiltedSpires` | `DoublePerlinNoiseSampler` 每 world 建一次 |
| `MapgenRavineButBased` | `randoms[1024]` 字段分配一次；每峡谷 1 个 Random（vanilla 同款） |
| `MapGenRoots` | 每节点 1 Random（vanilla 同款，每 chunk <1 次） |
| `MapGenCrater`/`MapGenVolcano`/`MapGenBubble` | 纯循环，零对象分配 |
| `MapGenVanillaCaves` | 继承 vanilla，每节点 1 Random |
| `ChunkProviderCelestial` 基类 | 噪声器 + buffer 构造时一次创建，全程复用字段数组 |
| `ChunkProviderEve.generateCracks` | `crackNoise` 字段复用 |
| 所有接入链的 GenLayer | 均用 `IntCache` 池化数组，无每格分配 |

---

## 五、Biome GenLayer 链对比（第三组审计）

| 星球 | 主链层数 | river | 自定义实例 | 最重层 | 精简可行性 |
|---|---|---|---|---|---|
| **Duna** | **17** | 3+RiverMix | 4 | GenLayerDunaLowlands | 可删 1-2 Smooth + 1 FuzzyZoom → 17→13（~25-30% 链成本），Zoom 别动（定尺度） |
| **Laythe** | **17** | 无 | **8**（Oceans×3） | GenLayerLaytheOceans×3 | 难精简（Polar/Oceans/Islands 都是功能性） |
| Dres | 13 | 无 | 5 | GenLayerDresBasins | 中档 |
| Minmus | 13 | 无 | 3 | GenLayerMinmusBasins | 中档 |
| Moho | 10 | 3+RiverMix | 2 | MohoPlateauExtend | 已精简 |
| Eve | 10 | 3 | 2 | EveRiverMix | 已精简 |
| Tekto | 10 | 3 | 2 | TektoRiverMix | 已精简 |
| Ike/Moon/Thatmo/Orbit/Spawn | 0（单 biome） | - | 0 | - | - |

**关键判断**：
- **只有 Duna + Laythe 是 17 层**。"精简 biome 链"是 **Duna+Laythe 局部优化**，非全局通用。
- **biome 链每 chunk 成本占比很小**（agent 估算 1-3%），相对 `generateNoiseField`（825 采样×16 八度×5 生成器）+ 65536 块填充。**不建议优先投入**。
- 带 river 星球（Duna/Moho/Eve/Tekto）有结构性"riverMix 双分支重复遍历 biome 主链"成本，Duna 主链最长惩罚最大。
- `GenLayerEveSeismicPlains` 的未播种 Random 是死代码。

---

## 六、对方案 C 的修正与优先级建议

**原方案 C 只盯 Duna 的 PerlinFestival，现在修正为「通用生成器优化」**：

| 优先级 | 改动 | 收益 | 风险 |
|---|---|---|---|
| P0 | F1：`ExperimentalCaveGenerator` 提 PerlinFestival 出循环 | Duna+Moho 单块成本大降（每 chunk 2000→~30 次构造） | 零（seed 恒定，输出不变） |
| P1 | F2：`MapGenVNoise` 缓存 Random + 去 double[] | Eve+Moho 每 chunk 6400 次分配→0 | 零（种子一致） |
| P2 | F3：`MapGenPlateau` 提 int[16][16] 为字段 | Moho+Duna | 零 |
| P3 | F4：`MapGenGreg` 缓存 biome 数组 | 4 星球 | 零 |
| - | F5：删 `GenLayerEveSeismicPlains` 死代码 | Eve 确定性 | 死代码 |

**不建议**：精简 biome genlayer 链（Duna+Laythe 局部、占比小、动观感）。

**待验证**：建议先 `/spark profiler --thread "*"` 确认 15s 卡顿里 PerlinFestival / MapGenVNoise 的实际占比，再按 P0-P3 实施。

相关：[[teleport-duna-chunkload-research]] [[hbm-modpack-dev-notes]] [[todo-list]]
