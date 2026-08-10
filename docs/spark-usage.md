# Spark 命令参考（1.7.10 Forge 专用版）

> 适用于整合包 `1.7.10-hbm航天改` 中已安装的 `[火花] spark-1.10.19-forge1710.jar`（内部版本 1.10.35）。
> 命令均在**游戏聊天栏**输入，单机世界玩家即 OP，直接生效。
> 本参考的命令/别名/参数均从该 jar 的字节码反编译核实。

## 0. 总览

- **根命令**：`/spark`
- 单机联机都有：服务端插件（tick/TPS）+ 客户端插件（渲染）都已加载。
- **`/spark help`** 任何时候可看内置帮助。
- 绝大多数命令输出的报告会上传到 `spark.lucko.me` 并给你一个链接，浏览器打开看可视化。

| 命令 | 用途 |
|---|---|
| `/spark profiler` (alias `sampler`) | **CPU 采样/火焰图**（核心） |
| `/spark tps` | TPS 统计 |
| `/spark health` | CPU/内存/网络/磁盘健康报告 |
| `/spark ping` | 玩家 ping |
| `/spark tickmonitor` (alias `tickmonitoring`) | tick 耗时监控 |
| `/spark heapdump` (alias `heapsummary`) | 堆转储/堆分析 |
| `/spark gc` (alias `gcmonitor`) | GC 监控 |
| `/spark activity` (alias `activitylog`) | 上传活动日志 |

---

## 1. `/spark profiler` — CPU 采样（火焰图）

别名：`sampler`。这是诊断性能问题的**主工具**。

### 子命令

| 子命令 | 说明 |
|---|---|
| `start` | 开始采样（可跟参数，见下） |
| `stop` | 手动停止并上传报告 |
| `cancel` | 取消当前采样（不上传） |
| `info` | 查看当前采样状态 |
| `upload` | 上传已有报告 |

### 参数（start 时使用）

| 参数 | 说明 |
|---|---|
| `--timeout <秒>` | 自动停止时间（**推荐 ≥ 30，建议 60**；<10 会警告不准） |
| `--thread *` | 采样**所有线程**（含 Client thread / Server thread / 其他）。**不带引号！** |
| `--thread <线程名>` | 只采样指定线程（如 `--thread "Server thread"`）。**不带引号！** |
| `--only-ticks-over <毫秒>` | 只记录超过该毫秒数的 tick（抓尖峰用） |
| `--interval <毫秒>` | 采样间隔（默认 4ms） |
| `--combine-all` / `--not-combined` | 是否合并相似栈帧 |
| `--comment <文本>` | 给报告加注释 |
| `--regex` | `--thread` 按正则匹配线程名 |
| `--force-java-sampler` | 强制用 Java 采样器 |
| `--save-to-file` | 保存到本地文件而不上传 |

### 常用组合

```text
# 60 秒采样所有线程（诊断渲染/综合性能，推荐 B6 用这个）——* 不带引号！
/spark profiler start --timeout 60 --thread *

# 30 秒只采服务端 tick 线程
/spark profiler start --timeout 30 --thread Server thread

# 只抓超过 100ms 的尖峰 tick
/spark profiler start --timeout 60 --only-ticks-over 100

# 手动停止并上传
/spark profiler stop
```

> ⚠️ **默认只采"游戏线程"**（服务端插件 = Server thread，客户端插件 = Client thread）。
> 想同时看渲染线程 + 服务端，务必加 `--thread *`。
> ⚠️ **⚠️ 引号陷阱：MC 1.7.10 聊天框命令解析不处理引号。`--thread "*"` 里的引号是字面字符传给 spark，导致通配符失效、回落到默认单线程。必须写 `--thread *` 不带引号。**（中文输入法的全角引号 `“”` 也会失效）
> 不加参数直接 `/spark profiler` 也会开始采样（默认游戏线程）。
>
> **怎么确认 `--thread *` 生效了**：采样期间敲 `/spark profiler info` 看线程数；采样完报告火焰图顶端应有**多个线程节点**（Client thread + Server thread + 其他），而不只是 Server thread。报告 metadata 的 `threadDumper.type`：0=ALL（成功）vs 1=SPECIFIC 单线程（失败回落）。

---

## 2. `/spark tps` — TPS 统计

无参数。输出各维度 TPS（last 1m/5m/15m）与 MSPT（平均/最大/中位/95 分位）。
判断"是不是服务器 tick 卡"第一招：**TPS < 20 就是 tick 卡，= 20 就是渲染/内存/IO 问题**。

```text
/spark tps
```

---

## 3. `/spark health` — 健康报告

输出 CPU 使用率、内存、网络、磁盘等。子命令：

| 参数 | 说明 |
|---|---|
| `--memory` | 详细内存/各池占用 |
| `--network` | 网络统计 |
| `--player` | 玩家信息 |

```text
/spark health
/spark health --memory
```

---

## 4. `/spark tickmonitor` — tick 监控

别名：`tickmonitoring`。连续监控 tick 耗时分布。

| 参数 | 说明 |
|---|---|
| `--threshold <毫秒>` | 只报告超过阈值的 tick |
| `--threshold-tick <毫秒>` | 同上（别名） |
| `--without-gc` | 忽略 GC 停顿 |

```text
/spark tickmonitor
```

---

## 5. `/spark heapdump` — 堆转储

别名：`heapsummary`。分析 Java 堆内存（查泄漏/GC 压力）。

| 参数 | 说明 |
|---|---|
| `--compress` | 压缩输出 |
| `--include-non-live` | 包含非存活对象 |
| `--run-gc-before` | 转储前先跑一次 GC |
| `--save-to-file` | 存本地不上传 |

```text
/spark heapdump
```

---

## 6. `/spark gc` — GC 监控

别名：`gcmonitor` / `gcmonitoring`。监控垃圾回收频率与停顿。

```text
/spark gc
```

---

## 7. `/spark activity` — 活动日志

别名：`activitylog`。

| 参数 | 说明 |
|---|---|
| `--page <页>` | 分页查看 |
| `--url` | 只输出上传 URL |

```text
/spark activity
```

---

## 8. 排障决策流程（针对本项目）

```
现象：整合包卡
  │
  ├─ /spark tps
  │     ├─ TPS < 20  → 服务端 tick 卡 → /spark profiler --timeout 60
  │     │                              （看 Server thread 火焰图热点）
  │     └─ TPS = 20  → 不是 tick 卡
  │                       ├─ FPS 低/渲染卡 → /spark profiler --timeout 60 --thread "*"
  │                       │                 （看 Client thread 火焰图）
  │                       ├─ 周期性卡（每 30-60s）→ 自动存档 /spark tickmonitor
  │                       └─ 内存涨/GC 频繁 → /spark health --memory + /spark heapdump
  │
  └─ 采样期间要复现卡顿：走到卡的地方/做卡的操作，让采样覆盖卡顿窗口
```

## 9. 已知陷阱

1. **默认只采一个线程** — 想抓客户端渲染必须 `--thread *`。
2. **引号陷阱** — MC 聊天框不解析引号，`--thread "*"` 会失效（见 §1）。
3. **空闲时采样没用** — 报告只会显示 95% 在 `Thread.sleep`（B6 第一份报告即如此）。必须在卡的时候采。
4. **`--timeout` 太短不准** — 建议 60s；卡顿是偶发的要更长。
5. **单机 `--thread *` 足够** — 单机服务端和客户端在同一 JVM，`*` 能同时抓到两个线程。
6. **Xmx 10G 偏高** — 本整合包启动参数 `-Xmx10240m`，1.7.10 用不上那么大，反而 GC 停顿久。建议 6G。实测 10G 堆 G1 Old 停顿 ~684ms/次、每 3m44s 一次 → 周期性画面硬冻结（2026-08-10）。
7. **⚠️ Windows 上 spark 的 swap 标红是误报警** — `MemoryInfo.getUsedSwap` = `getTotalSwapSpaceSize() - getFreeSwapSpaceSize()`，Windows HotSpot 映射到 `GlobalMemoryStatusEx().ullTotalPageFile` = **commit limit（物理RAM + pagefile 容量）**，不是换出量。`-Xmx10G` 启动即提交 10G 虚拟地址空间，整机 commit 逼近上限就常驻红。物理内存只显示 24G/32G 而 swap 红 **不矛盾**，该指标可忽略；真实看 GC 停顿。详见 `memory/xaero-minimap-architecture.md`。

---

## 10. 报告解析与分析方法

浏览器打开 `spark.lucko.me/<code>` 能看可视化火焰图，但**数据量大时脚本解析更准**。spark 报告数据分两处，可用命令行拉取后本地解析：

### 10.1 数据源（两个独立接口）

| 数据 | 地址 | 格式 |
|---|---|---|
| **元数据**（TPS/MSPT/线程信息/系统统计） | `https://spark-json-service.lucko.me/<code>` | JSON |
| **采样数据**（火焰图树） | `https://spark-usercontent.lucko.me/<code>` + `Accept: application/x-spark-sampler` | protobuf 二进制 |

```bash
# 元数据
curl -s "https://spark-json-service.lucko.me/<code>" -o report.json
# 采样数据（必须带 Accept 头）
curl -s -H "Accept: application/x-spark-sampler" \
  "https://spark-usercontent.lucko.me/<code>" -o sampler.bin
```

### 10.2 protobuf 结构（spark_sampler.proto 要点）

火焰图是**扁平池 + children_refs 索引**结构，不是嵌套 children：

```
SamplerData
  metadata(1)   ← 元数据
  threads(2)    ← ThreadNode 列表
    ThreadNode:
      name(1)          线程名
      children(3)      池：全部 StackTraceNode 扁平数组
      times(4)         该线程总样本数（各窗口）
      children_refs(5) 根节点索引
    StackTraceNode:
      class_name(3) / method_name(4)  方法名
      times(8)         该节点 inclusive 时间（各窗口，double）
      children_refs(9) 子节点索引（packed varint）
```

**关键点**：
- `node.times` 直接就是 **inclusive 时间**（火焰图宽度），不需要再累加子树。
- `children_refs` 是 **packed varint**（不是单字节），指向池内索引。
- 总样本数 = ThreadNode.times 各窗口之和（作为百分比分母）。
- 解析工具：`tools/spark_parse.py`（本仓库，下载+解析+输出 Top Inclusive 方法表）。

### 10.3 火焰图解读方法（数据流）

1. **先看 `threadDumper.type`**：0=ALL 多线程（成功）；1=SPECIFIC 单线程（默认/失败回落）。判断这次采没采到想要的线程。
2. **先看 TPS/MSPT**（metadata）：
   - TPS=20 满 → 不是 tick 卡 → 看渲染/内存/IO
   - TPS<20 → tick 卡 → 看 Server thread 火焰图热点
   - MSPT **max 远大于 mean** → 偶发尖峰（自动存档/GC/读盘）
3. **看 Top Inclusive 方法表**：
   - 若 `Thread.sleep` 占 80%+ → 空闲态，采样没踩到卡顿，重新采
   - 找第三方 mod 的方法（appeng./net.p455w0rd. 等）和 HBM 方法（com.hbm.）
   - 每个热点的**完整调用链**用 parent 回溯重建（工具支持 `--path <子串>` 打印根→目标路径）
4. **对照系统统计**（metadata.systemStatistics）：CPU 占用、物理内存/swap、JVM 参数。

### 10.4 本项目 B6 实战结论（2026-08-08）

**报告 s9lroqvacm（空闲）**：TPS 满、Server thread 95% sleep、HBM 无热点 → 服务端 tick 健康，问题不在此。

**报告 fYIlCDtXRl（刚进世界）**：TPS 19.8、MSPT max **3332ms**、p95 53ms。火焰图定位两个服务端热点：

| 热点 | 调用链 | 占比 | 根因 |
|---|---|---|---|
| **AE2 陨石文件IO** | `MeteoriteWorldGen$ExistingMeteoriteSpawn.call` → `getNearByMeteorites` → `SpawnData.loadSpawnData` → `FileInputStream.open0` | ~10% tick | 每 tick 从磁盘读陨石 spawn 数据 |
| **WCT 版本检查 HTTP** | `VersionCheckHandler.tickStart` → `fetchVersion` → `URL.openStream` | ~1.2% | 每玩家 tick 发 HTTP 查更新 |

系统级：物理内存 25/34GB 用满、swap 50/52GB（整机内存压力）。

**修复**（已实现于 `com.hbm.compat` 包）：AE2 `MeteoriteWorldGen=false` + WCT `doVersionCheck=false`，启动时自动改配置。详见 `docs/superpowers/specs/2026-08-08-mod-compat-config-design.md`。

---

## 11. 相关资料

- 官方文档：https://spark.lucko.me/docs/
- 报告查看器：https://spark.lucko.me/<code>
- 解析工具：`tools/spark_parse.py`（本仓库）
- 设计文档：`docs/superpowers/specs/2026-08-08-mod-compat-config-design.md`
