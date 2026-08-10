# NeiFix — NEI 渲染超时/全屏挂起修复（并入 HBM coremod）

> 日期：2026-08-08　分支：`space-travel-twopointfive`
> 状态：**已实现 + 构建 + 部署，待整合包实机验证**
> 来源：原独立 `FullscreenFix-1.0.0.jar`（现已禁用 `.disabled`），并入 HBM compat 包

## 一、问题

整合包（GTNH 版 NEI + AE2 等）渲染 HBM 的 3D 模型时，一帧内 Tessellator 命令量巨大，
现代 Windows + RTX 显卡上 GPU 驱动来不及消费积压的命令 → **渲染超时/卡死/驱动挂起**。
症状：进游戏瞬间卡 30 秒后恢复，或"日志消失后崩溃"。

此外 1.7.10 独占全屏切换（F11）会销毁/重建 OpenGL 上下文，同样触发 RTX 驱动挂起。

## 二、原 FullscreenFix 的 bug（本次修复）

原 jar 的 `TessellatorTransformer` 常量 `METHOD_NOTCH = "b"` 是**错的**：
- 实际 `Tessellator.draw` 的 notch 名是 **`a`**（映射 `MD: bmh/a ()I → func_78381_a ()I`）
- 原 jar 在整合包运行时（收到 notch 字节）匹配不到 draw → **glFlush 从未注入** → 问题一直存在

另外原 jar 用 `()I` desc 匹配 draw 也存疑（deobf 后 `func_78381_a` 的 desc 就是 `()I`，但匹配绕过了 notch 单字母歧义问题）。

## 三、修复方案（复用 coremod 混淆方案 B）

两个 transformer 都用 **deobf 方案**（见 `docs/coremod-obfuscation-fix.md`）：
- 收到 notch 字节时先用 `FMLRemappingAdapter` deobf 成 SRG
- 统一匹配 SRG 名（`func_78381_a` / `func_71352_i`）
- 彻底规避单字母 notch 名歧义

## 四、文件（compat 包）

| 文件 | 作用 |
|------|------|
| `NeiFix.java` | IModCompat：检测 NEI 是否加载（`Loader.isModLoaded`），设静态开关 `isNeiEnabled()` |
| `NeiFixTessellatorTransformer.java` | `Tessellator.draw` 注入 `GL11.glFlush()`（防渲染命令积压）|
| `NeiFixMinecraftTransformer.java` | 屏蔽 `Minecraft.toggleFullscreen`（防 GL 上下文重建）|
| `NeiFixHooks.java` | 全屏屏蔽反射 hook（找 isFullScreen 字段）|

## 五、无 NEI 保护

- `NeiFix.isNeiEnabled()` 在 `FMLPreInitializationEvent`（MainRegistry.PreLoad → ModCompatRegistry.applyAll）阶段设置
- 两个 transformer 的 `transform()` **第一行就检查开关**，NEI 未安装时直接返回原字节（零改动）
- transformer 必须注册在 `HbmLoadingPlugin.getASMTransformerClass()`（coremod 早期），无法走普通 IModCompat 事件链，因此用静态开关在两者间传递状态

## 六、注册与构建

1. `ModCompatRegistry.registerAll()` 加 `register(new NeiFix())`
2. `HbmLoadingPlugin.getASMTransformerClass()` 加 `NeiFixMinecraftTransformer` / `NeiFixTessellatorTransformer`
3. `./gradlew build`（含 reobf）+ `fix_dir_entries.py` 补目录条目
4. 部署整合包：替换 `HbmSpaceCore[1.0.27_X5771_H261].jar`（保持文件名），`FullscreenFix-1.0.0.jar.disabled` 保持禁用

## 七、验证方法

整合包启动后 `fml-client-latest.log` 应出现：
- `[hbmcore][compat] NeiFix: NotEnoughItems detected, ASM fixes enabled`
- `[hbmcore] injected glFlush into Tessellator.func_78381_a`
- `[hbmcore] fullscreen toggle suppressed, injected hook into Minecraft.func_71352_i`
- 无 NEI 环境（dev/runClient 若去 NEI）应出现 `NOT present, ASM fixes skipped` 且无注入

## 八、关联记忆

- [[coremod-obfuscation-deobf-fix]]（混淆方案 B 来源）
- [[feedback-dont-touch-nei]]（NEI 版本由环境约定，不改依赖）
- [[todo-list]] B6/B7（渲染超时相关）
