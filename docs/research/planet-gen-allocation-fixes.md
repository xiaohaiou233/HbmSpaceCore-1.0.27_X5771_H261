# 星球生成「循环内重建对象」修复记录（2026-08-10）

> 依据：`docs/research/planet-gen-loop-allocation-audit.md`（审计）。本次实施 P0-P3 共 4 项 + 顺手修 1 处 B8 遗留编译错误。
> 全部为**纯性能优化**：不改变任何地形/生成输出，只消除循环内重复对象分配。

---

## 修复总览

| # | 文件 | 改动 | 收益星球 | 每 chunk 分配变化 |
|---|---|---|---|---|
| F1 | `mapgen/ExperimentalCaveGenerator.java` | `new PerlinFestival(seed)` 从 step 循环内提到循环前（局部变量） | Duna + Moho | 200~2000 次 → ~10-30 次 |
| F2 | `noise/MapGenVNoise.java` | `getCellCenter` 字段级 Random setSeed + 复用 double[2] 缓冲 | Eve + Moho | 6400 次分配 → 0 |
| P2 | `mapgen/MapGenPlateau.java` | `int[16][16]`×2 提为实例字段 | Duna + Moho | 34 数组 → 0 |
| P3 | `mapgen/MapGenGreg.java` | `BiomeGenBase[100]` 提为实例字段 | Moon/Laythe/Tekto/Thatmo | 1 数组 → 0 |
| 附带 | `core/HbmDeathDropTransformer.java` | 错误消息 `METHOD_ON_DEATH` → `METHOD_ON_DEATH_SRG`（B8 遗留编译错误） | - | 编译修复 |

---

## F1：ExperimentalCaveGenerator — PerlinFestival 提出 step 循环

**问题**：`generateCaveNode` 的 `for(...; currentStep < totalSteps; ++currentStep)` 循环内第 80 行 `this.perlinNoise = new PerlinFestival(seed)`，每 step 重建 512 元素 permutation（Fisher-Yates 打乱）。`seed` 在整个循环内恒定 → 每次结果相同，纯浪费。

**修复**：
```java
// 循环前（generateCaveNode 内，每次调用构造一次）：
PerlinFestival perlinNoise = new PerlinFestival(seed);
```
删掉循环内的重建行。局部变量 shadow 原字段；移除未用字段 `perlinNoise`。

**正确性**：`seed` 恒定 → 提出循环输出完全一致。递归子调用（第112-113行 `generateCaveNode(random.nextLong(), ...)`）用新 seed 各自进方法构造一次，正确。

---

## F2：MapGenVNoise — getCellCenter 消除每格分配

**问题**：`getCellCenter` 每格 `new Random(hash)` + `new double[2]`，每 chunk 16×16×5×5 = 6400 次调用 → ~12800 次对象分配。

**修复**：
```java
private final Random cellRandom = new Random(0);
private final double[] cellCenterBuffer = new double[2];

private double[] getCellCenter(int cellX, int cellZ, long worldSeed) {
    long hash = (long) cellX * 341873128712L + (long) cellZ * 132897987541L + worldSeed;
    cellRandom.setSeed(hash); // == new Random(hash)，序列完全一致（已验证）
    double offsetX = (cellRandom.nextDouble() - 0.5) * cellSize;
    double offsetZ = (cellRandom.nextDouble() - 0.5) * cellSize;
    cellCenterBuffer[0] = cellX * (double) cellSize + cellSize / 2.0 + offsetX;
    cellCenterBuffer[1] = cellZ * (double) cellSize + cellSize / 2.0 + offsetZ;
    return cellCenterBuffer;
}
```

**正确性**：`Random.setSeed(h)` 与 `new Random(h)` 序列完全一致（构造器内部就是 setSeed，已用 8 种边界 hash × 20 次 double 实测验证）。缓冲区无重入（单线程串行，返回值当迭代即消费，无跨调用保留）。

---

## P2：MapGenPlateau — int[16][16] 提字段

**问题**：`func_151539_a` 每 chunk `int[][] plateauTops/baseHeights = new int[16][16]` = 34 个小数组对象。

**修复**：提为字段，16×16 全量覆盖写，无需清空。
```java
private final int[][] plateauTops = new int[16][16];
private final int[][] baseHeights = new int[16][16];
```

**正确性**：两趟循环都写满所有 16×16 格（第一趟算 baseHeight + plateauAddition，第二趟读），无残留数据污染。

---

## P3：MapGenGreg — biome 数组提字段

**问题**：`generateNoiseCavesNoise` 每 chunk `BiomeGenBase[] biomes = null; ...getBiomesForGeneration(biomes, ...)` 传 null → WorldChunkManager 每 chunk 新建 100 元素数组。

**修复**：
```java
private BiomeGenBase[] biomesForGeneration;

// generateNoiseCavesNoise 内：
if (this.biomesForGeneration == null) {
    this.biomesForGeneration = new BiomeGenBase[100]; // 固定 10×10=100 格
}
this.worldObj.getWorldChunkManager().getBiomesForGeneration(this.biomesForGeneration, cx - 2, cz - 2, 10, 10);
```
引用点改为 `this.biomesForGeneration[...]`。

**正确性**：`getBiomesForGeneration` 固定填充 10×10=100 格，全量覆盖。MapGenGreg 每 ChunkProvider 一个实例、单线程串行（`worldObj != w` 守卫即设计单 world 串行），无并发。lazy-init 在首次使用前建立。

---

## 附带：B8 遗留编译错误

`core/HbmDeathDropTransformer.java:89,113` 引用 `METHOD_ON_DEATH`（不存在），应为 `METHOD_ON_DEATH_SRG`。这是之前会话 B8（盔甲死亡保留）未提交的编译错误，阻塞所有编译。已改错误消息字符串里的常量名，逻辑不变。

---

## 验证

- `./gradlew compileJava --offline` → **BUILD SUCCESSFUL**（2 次：F1+F2 后、P2+P3+B8 后）
- 无新增编译错误/警告
- F2 确定性：`new Random(h)==setSeed(h)` 实测 PASS

## 后续

- 待进整合包验证：`gradlew build`（reobf SRG）+ 部署，观察 Duna/Moho/Eve 星球生成是否提速、卡顿是否缓解。
- 建议 spark 采样确认 F1/F2 在 15s 卡顿中的实际占比（`/spark profiler start --timeout 60 --thread "*"`）。
- 方案 B（逃生舱预生成落点区块）未实现，见 `docs/research/teleport-duna-15s-chunkload.md`。

相关：[[planet-gen-allocation-audit]] [[teleport-duna-chunkload-research]] [[todo-list]]
