# HBMcore — HBM 航天版整合包核心模组

> 私人整合包开发 fork：基于 [NTM: Space (1.7.10)](https://github.com/JameH2/Hbm-s-Nuclear-Tech-GIT) 的深度改造，把原版"行星旅行"改造成**太空生存整合包**。开发分支 `space-travel-twopointfive`。

## 玩法

玩家在近地轨道**空间站出生维度（SPAWN）**诞生 → 乘坐**逃生舱**裸降到火星（Duna）→ 落地开始生存。出生点网格、逃生舱降落、死亡重生、进服护甲均由本 fork 深度定制（代码内 `// ==== [modpack]` 标记，详见 `docs/`）。

## 上游

- [NTM: Space on Modrinth](https://modrinth.com/mod/ntmspace)
- [NTM: Space on CurseForge](https://www.curseforge.com/minecraft/mc-mods/ntm-space)
- [Official NTM Wiki](https://nucleartech.wiki/wiki/Main_Page)
- [NTM: Space Discord](https://discord.gg/u6bKmGNYCq)
- 上游仓库：https://github.com/JameH2/Hbm-s-Nuclear-Tech-GIT

**本 fork 仅用于 1.7.10。**

## 构建

前置：JDK8 + Git。构建脚本相关配置（JDK 路径、代理、JVM 参数）见 `gradle.properties`。

```bash
./gradlew build
```

- 产物：`build/libs/HBMcore-<version>.jar`（**已 reobf 为 SRG，可直接进整合包**）
- ⚠️ 勿用 `./gradlew jar`——会退回 MCP 名，整合包加载失败
- reobf 后自动补 ZIP 目录条目（`build.gradle` 的 `reobf.doLast`），产物无需再手动修复

开发快速验证：`./gradlew runClient`。运行时依赖 GTNH 版 codechicken 全家桶 + WAILA（deobf jar 放 `libs/gtnh-dev/`，**环境约定，不入库**）。

## 文档索引

| 文档 | 内容 |
|---|---|
| `docs/coremod-obfuscation-fix.md` | coremod 在 prod 混淆下的 deobf 方案 |
| `docs/neifix-integration.md` | NeiFix（NEI 全屏屏蔽修复）集成 |
| `docs/perf-2026-08-08-multiplayer-teleport-lag.md` | 多人跨维度传送卡顿定位与修复 |
| `docs/spark-usage.md` | Spark 性能分析用法（定位掉帧/卡顿） |
| `docs/research/` | 研究存档：传送卡顿 chunkload、生成器分配、vanilla 反编译 |
| `tools/README.md` | 维护脚本：NBT 解析 / 结构对比 / 模型分析 |

> `docs/modpack-dev-summary.md`、`docs/superpowers/` 是本地开发过程记录，不入库（gitignore）。

## 开发约定

- **改动标记**：修改原版代码时保留原文注释，新增/改动用 `// ==== [modpack]`；性能日志用 `[perf]`
- **NBT 前缀**：自定义 tag 一律 `hbmcore_`（`hbmtw` 是另一个模组的前缀，勿混用）
- **coremod**：`com.hbm.core`（HbmLoadingPlugin + 各 Transformer），jar manifest 声明 `FMLCorePlugin`；prod 下 transformer 先收到 notch 字节，须先 `FMLRemappingAdapter` 转 SRG 再匹配
- **兼容修复**：`com.hbm.compat`（AeFix / WCTFix / NeiFix），由 MainRegistry.PreLoad 启动注入配置
- **配置**：`SpaceConfig`（键前缀如 `1.95_`、`1.96_`、`1.97_`）
- **不入库**（gitignore）：`libs/`（环境 jar）、`_tmp*`、`.claude/`、结构文件 `*.bak*`

## 部署

把 `gradlew build` 产出的 `HBMcore-<version>.jar` 放进整合包实例的 `mods/` 目录即可。NEI/WAILA 是运行时辅助（GTNH 版），**不是编译依赖**（build.gradle 仅 compileOnly 原版 API）。

## License

GNU Lesser General Public License version 3。完整文本见 `LICENSE` 与 `LICENSE.LESSER`。
