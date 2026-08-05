# 整合包出生点设计快照（2026-08-06 锁定）

> 基于 HBM 航天版 fork（`space-travel-twopointfive` 分支，本地 `E:\Forge\hbm-fork`，X5771）。
> 本文档固化已确认的设计，改动前先读这里。

## 核心设定

- **世界**：只保留主世界（Overworld，dim0）。创建世界后玩家不会停留在主世界，而是被送入**出生维度**。
- **删地球**：移除 Kerbin 作为旅行目标（驱动器/目标列表不出现），终局改为"去适宜生存的外星"（另行规划，可并行"改造火星"路线）。
- **出生维度**：仿近地轨道（虚空）新建，玩家在此出生，拥有一个**破烂空间站**，站之间隔开（网格或随机散布）。
  - 站唯一功能：提供一个**降落仓**，让玩家降落到火星（Duna）。
  - 站内方块空手破坏不掉落；降落仓不可破坏。
  - 飞船无法飞回出生维度，也无法从这里去其他星球 —— 封锁是**物理上的**。
- **降落**：**裸降，无任何建筑**。落点 = 随机（HBM 原生 `±范围`，无需磁盘默认）或玩家选点（UI 后置）。
  - 落地瞬间服务端把玩家重生点写到落地坐标（承重墙，防困在出生维度）。
  - 降落仓一次性（火箭概念），落地球后不保留庇护所。
- **磁盘系统（VOTV / hard_drive / full_drive / Stardar）**：**彻底删除**（合成 + NEI 全清）。目的地改存火箭 NBT。导航台 UI 设计后置（缺美工）。
- **氧气起步**：开局给太空服；死亡**盔甲不掉落**（保护供氧设备）；复活后获得 **15 分钟不耗氧 buff**。O2 产线方式（一次性道具 vs 小型产氧机+升级）待定，硬约束：**产线可达时间 ≤ buff 时长**。

## 代码删改原则

删除一律用**注释可恢复**的方式（`// ==== [modpack] 已删除: 原因 ====` 标记），不物理删除，便于恢复/借鉴。

## 当前里程碑（本次要做完的）

1. 简化世界类型，只留主世界（`martian` 等替代类型注释掉）。
2. 删除火星初始基地（`WorldGeneratorDuna` 的 `martian-base` 生成 + `WorldTypeTeleport` 首次传送）。
3. 创建出生维度（仿 `WorldProviderOrbit`）：
   - 玩家能正常出生在这个维度；
   - 维度内死亡重生回出生点；
   - 出生点脚下放一块石头（暂定）。

## 参考源码位置

| 功能 | 文件 |
|------|------|
| 世界类型 | `dim/WorldTypeTeleport.java` |
| 火星生成/基地 | `dim/duna/WorldGeneratorDuna.java`、`main/StructureManager.java:98` |
| 近地轨道维度 | `dim/orbit/WorldProviderOrbit.java`、`dim/orbit/ChunkProviderOrbit.java` |
| 首次加入传送 | `ModEventHandler.onPlayerJoin` |
| 维度 ID 配置 | `config/SpaceConfig.java`（orbit=413023, duna=413016） |
| 大气/氧气 | `handler/atmosphere/ChunkAtmosphereHandler.java`、`handler/EntityEffectHandler.java:403` |
| 火箭/磁盘 | `entity/missile/EntityRideableRocket.java`、`items/ItemVOTVdrive.java` |

## 待定项

- O2 产线方式（一次性道具 vs 产氧机+升级）
- 导航台 UI 设计（缺美工，后置）
- 是否保留一个"创造测试"世界类型
- 出生维度站的隔开方式（网格 vs 随机散布）
