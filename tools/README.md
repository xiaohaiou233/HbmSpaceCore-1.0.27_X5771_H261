# tools/ — HBM 整合包开发维护工具

> 一次性/可复用脚本，从项目根目录 `E:\Forge\hbm-fork` 运行（多数脚本硬编码了输入路径，用前按需改）。
> 统一用 Python 3，除 `verify_structure_nbt.py` 需 `nbtlib` 外，其余仅标准库。

## NBT 解析类（进服护甲模板 / 玩家存档）

这三个脚本共用同一套最小 NBT 解析器（gzip 解压 + 手工解 Big-endian NBT），无需任何依赖。

| 脚本 | 功能 | 输入 | 输出 |
|---|---|---|---|
| `parse_spawnarmor_dump.py` | **护甲模板全量 dump**：逐槽打印完整 NBT（含电量/插件等子 tag） | `eclipse/config/hbmcore_spawnarmor.dat` | 每槽完整树 |
| `parse_spawnarmor_summary.py` | **护甲模板摘要**：root keys、version、槽位数、每槽 id/Damage/Count | 同上 | 一屏摘要 |
| `parse_playerdata.py` | **玩家存档检查**：`HbmPlayerProps.hasWarped` + 盔甲槽(100-103) + 主背包非空格 | `eclipse/saves/<世界>/playerdata/*.dat` | 各槽信息 |

用途：验证 `SpawnArmorTemplate`（进服护甲模板）存出的 `config/hbmcore_spawnarmor.dat` 结构是否符合预期。
注意 `parse_spawnarmor_dump.py` 与 `parse_spawnarmor_summary.py` 解析的是同一个 dat，dump 看细节、summary 看概览，二选一即可。

## 模型分析

| 脚本 | 功能 | 输入 | 输出 |
|---|---|---|---|
| `parse_landing_capsule_obj.py` | 解析 `rp_landing_capsule.obj` 的 `o Base`/`o Capsule` 两组顶点包围盒 + 面数，判断模型分组是否独立（Base 在下部的推断依据） | `src/main/resources/assets/hbm/models/missile_parts/rp_landing_capsule.obj` | 每组顶点范围 + 每对象面数 |

用途：逃生舱/登陆舱 hitbox 对齐、模型分组分析。

## 结构 NBT 对比（依赖 nbtlib）

| 脚本 | 功能 | 输入 | 输出 |
|---|---|---|---|
| `verify_structure_nbt.py` | **结构文件对比**：按 `方块类型+meta` 对比两份 `spawn_station.nbt` 的真实差异（改变/新增/移除），并检查 `orbital_station_port` 的 meta、金块是否残留 | 硬编码 `eclipse/structures/spawn_station.nbt`（新）vs `src/main/resources/assets/hbm/structures/spawn_station.nbt`（当前） | 差异清单 + 端口 meta 集合 + 金块检查 |

用途：空间站结构导出后验证 —— 确认新导出与已部署版本差异、端口 meta 正确、金块已删除（对齐标记）。

## 其他

| 脚本 | 功能 | 备注 |
|---|---|---|
| `fix_dir_entries.py` | reobf 产物 ZIP 目录条目补回 | 已被 build.gradle `reobf.doLast` 自动化替代，保留备用 |
| `spark_parse.py` | Spark 报告解析 | 见 `docs/spark-usage.md` |
| `check_hbmtweaker_mapping.sh` | HbmTweaker 反射映射校验 | 见 `docs/` B14 相关 |
| `export-json-animation-*.py` | JSON 动画导出（原 HBM 自带） | 原版工具 |
