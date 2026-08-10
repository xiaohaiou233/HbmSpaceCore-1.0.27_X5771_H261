# 性能问题分析：多人联机传送卡顿 + NeiFix 全屏 NPE（2026-08-08）

> 场景：22:00 后与 qiyuex_ 多人联机（SPAWN 维度），逃生舱降落 Duna 时出现严重卡顿，
> 用户描述"表现和全屏卡顿以及打开背包的 NEI 卡顿一致"。
> 数据源：整合包 `fml-client-latest.log`（21:45:22 → 22:22:46）+ 部署 jar 字节码 + 离线 ASM 复现。
> 分支：`space-travel-twopointfive`。

## 结论速览

本次会话定位到 **两个独立问题**：

| # | 问题 | 根因 | 影响 |
|---|------|------|------|
| 1 | 逃生舱降落 Duna 卡 15 秒 | 服务端 `transferPlayerToDimension` 同步做 Duna 维度初始化 + 落点视距区块生成（Duna 地形生成昂贵） | 多人全员冻结 |
| 2 | 全屏屏蔽从未生效 | `NeiFixMinecraftTransformer` 替换 `toggleFullscreen` 方法体后残留 `tryCatchBlocks`，引用了被 clear 的 label → `MethodWriter.visitMaxs` NPE | F11 全屏 GL 上下文重建风险依旧 |

对比用户感知的三个现象：

| 现象 | 根因 | 状态 |
|------|------|------|
| 打开背包 NEI 卡顿 | Tessellator 命令积压 → GPU 驱动挂起 | ✅ glFlush 注入成功（已修） |
| 全屏卡顿 | 独占全屏切换销毁/重建 GL 上下文 | ❌ **transformer NPE，全屏没屏蔽** |
| 昨晚传送卡顿 | 服务端 Duna 传送阻塞 15s | ⚠️ 新发现，未处理 |

> 注意：卡顿 #1（传送）是**服务端 tick 阻塞**，不是 Tessellator/渲染问题。
> 用户感觉"和全屏/NEI 一致"是因为现象都是"画面硬冻结十几秒"，但机制不同。

---

## 一、问题 1：逃生舱降落 Duna 服务端传送阻塞 15 秒

### 1.1 日志硬证据

正常传送（SPAWN 出生，虚空维度）：

```
[22:02:37] [Server thread] [perf] celestial teleport took 348ms, queueBacklog=0
[22:07:13] [Server thread] [perf] celestial teleport took 344ms, queueBacklog=0
```

卡顿传送（逃生舱降落到 Duna，真实地形维度）：

```
[22:14:58] [Server thread] [perf] celestial teleport took 15642ms, queueBacklog=0   ← 15.6 秒
[22:15:13] [Server thread] [perf] landed Duna at (-3996, 149, -4918), fall_ticks=14610
[22:15:44] [Server thread] [perf] celestial teleport took 14388ms, queueBacklog=0   ← 14.4 秒
[22:16:00] [Server thread] [perf] landed Duna at (3555, 149, -21997), fall_ticks=15280
```

### 1.2 数据流（`[perf]` 标记包住的是 `CelestialTeleporter.runTeleport()`，服务端线程）

```
逃生舱 LANDING 状态（EntityEscapePod.onUpdate → state=LANDING）
  → beginLandingSequence(from, to)                        EntityRideableRocket.java:194
  → CelestialTeleporter.teleport(rider, dunaDim, x, 800, z, false)  排队（worldTick END）
  → 下一 tick runTeleport()                                CelestialTeleporter.java:77
  → manager.transferPlayerToDimension(playerMP, duna, this)          ← 15 秒耗在这
      ├─ worldServerForDimension(duna)
      │    └─ 首次进 Duna → DimensionManager.initDimension → WorldServerDuna 创建
      │         └─ spawn chunk 生成（289 块昂贵的 Duna 地形）
      ├─ placeInPortal（CelestialTeleporter.java:47）
      │    └─ grounded=false → targetServer.getBlock(ix, clamp(iy,1,255), iz)  dummy load 落点 chunk
      └─ 玩家落点视距区块生成
           └─ ChunkProviderDuna.getChunkPrimer：每块跑
                caveGenV2（2,40,3.0）+ caveGenSmall（2,12,0.12）+ genPlateau + oil + WorldGeneratorDuna 挖矿
```

### 1.3 为什么 Duna 慢、SPAWN 快

- **SPAWN** = 虚空维度，`ChunkProviderOrbit` 几乎零成本 → 344ms。
- **Duna** = 有真实地形的行星，`ChunkProviderDuna` 每 chunk 跑多个昂贵生成器
  （洞穴 ×2 + 台地 + 油泡 + 基岩 + 矿石）+ `WorldGeneratorDuna`。
- 首次进 Duna 还要 `DimensionManager.initDimension` 建世界 + 生成 spawn chunk。

### 1.4 影响

服务端主线程阻塞 15 秒 → 所有玩家 tick 停摆 → 画面硬冻结。这是用户感受到的主卡顿。

### 1.5 修复方向（未实施）

1. 首次进 Duna 前提前异步初始化维度（如进游戏后预 init）。
2. 落点视距区块分帧生成（避免一次同步生成）。
3. 降低 Duna 区块生成成本（caveGen 参数等）。
4. 至少给传送加"进度提示/防误触"，把 15 秒变成可预期。

---

## 二、问题 2：NeiFix 全屏屏蔽从未生效（NPE）

### 2.1 日志硬证据

```
[21:45:47] [main/ERROR] [HBM/]: [hbmcore] fullscreen transformer FAILED: java.lang.NullPointerException
  at org.objectweb.asm.MethodWriter.visitMaxs(Unknown Source)
  at com.hbm.compat.NeiFixMinecraftTransformer.patchToggleFullscreen(NeiFixMinecraftTransformer.java:105)
```

对照 glFlush（成功）：

```
[21:46:28] [Client thread] [hbmcore] injected glFlush into Tessellator.func_78381_a
```

### 2.2 根因（已用离线 ASM 复现验证）

`NeiFixMinecraftTransformer.patchToggleFullscreen`（NeiFixMinecraftTransformer.java:85）：

```java
m.instructions.clear();        // 只清指令
m.instructions.add(replacement);
m.maxStack = 1;
ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);   // ← NPE 在这
cn.accept(cw);                  // line 105
```

**没清 `m.tryCatchBlocks`**。而 `Minecraft.toggleFullscreen`（notch `bao.o()`）自带 try-catch：

```
Exception table:
   from    to   target  type
     0    179    182    Class java/lang/Exception
```

残留的 tryCatchBlock 引用了已被 `clear()` 掉的 LabelNode → `ClassWriter(COMPUTE_MAXS)`
在 `MethodWriter.visitMaxs` 遍历异常表时读到 null label → **NPE**。

而 `Tessellator.draw`（`bmh.a()`）**没有** try-catch，所以 glFlush 正常注入——这解释了
为什么两个 transformer 一个成功一个失败。

### 2.3 离线复现结果（ASM 5.0.3，整合包实际 notch 字节）

```
CURRENT CODE REPRODUCED: NullPointerException at MethodWriter.visitMaxs   ← 与游戏日志逐帧吻合
FIXED CODE (清 tryCatchBlocks): SUCCESS, wrote 50839 bytes                ← 修复方案验证通过
```

### 2.4 影响

F11 独占全屏切换**仍然会**销毁/重建 GL 上下文 → 用户之前遇到的"全屏卡顿"**根本没被修复**。

### 2.5 修复（1 行）

`patchToggleFullscreen` 里 `m.instructions.clear()` 后加：

```java
m.tryCatchBlocks.clear();
m.localVariables.clear();
```

改完重新 `gradlew build` + 部署。

---

## 三、排查过程记录（可复现）

### 3.1 环境定位

- 实际整合包：`E:\pcl\PCL 正式版 2.10.3\.minecraft\versions\1.7.10-hbm航天改\`
- 日志：`logs/fml-client-latest.log`（21:45:22 → 22:22:46）
- 部署 jar：`mods\HbmSpaceCore[1.0.27_X5771_H261].jar`（8月8 12:08，含 NeiFix 但未修 tryCatchBlocks）
- backups/ 有 4 个历史 jar 可对比

### 3.2 日志关键 grep

```bash
# NeiFix 注入状态
grep -nE "NeiFix|glFlush|fullscreen|tessellator transformer" fml-client-latest.log
# → 905: fullscreen transformer FAILED: NPE
# → 2482: injected glFlush into Tessellator.func_78381_a
# → 3295: NeiFix: NotEnoughItems detected, ASM fixes enabled

# 传送/落地/性能
grep -nE "\[perf\]|fall to Duna|landed Duna|escapePod" fml-client-latest.log
```

### 3.3 映射表核对

```
grep toggleFullscreen mcp-srg.srg → func_71352_k
grep "bao " notch-srg.srg          → CL: bao net/minecraft/client/Minecraft
grep toggleFullscreen mcp-notch.srg → bao/o ()V
```

### 3.4 javap 验证方法体结构

```bash
unzip 1.7.10-hbm航天改.jar bao.class bmh.class
javap -p -c bao.class   # Minecraft，看 o() 的 Exception table
javap -p -c bmh.class   # Tessellator，a() 无 Exception table
```

### 3.5 ASM 复现脚本要点

- 输入：整合包 `bao.class`（真实 notch 字节）+ 部署 jar 里的 transformer。
- 依赖：asm-all-5.0.3.jar + launchwrapper + forge + NEI + mc jar（deobf 需访问 Minecraft 类）。
- 复现：`instructions.clear()` 后 `ClassWriter(COMPUTE_MAXS)` → NPE at visitMaxs。
- 修复验证：补 `tryCatchBlocks.clear()` → SUCCESS。

---

## 四、待办

- [ ] 修 `NeiFixMinecraftTransformer` tryCatchBlocks NPE（1 行，已验证）
- [ ] 决策：传送 15s 的缓解方案（预初始化 / 分帧生成 / 降成本）
- [ ] 补 spark 采样：卡顿时 `/spark profiler start --timeout 60 --thread "*"` 确认客户端是否也有渲染热点

相关：[[neifix-nei-render-fix]] [[spark-usage-reference]] [[hbm-modpack-dev-notes]] [[todo-list]]
