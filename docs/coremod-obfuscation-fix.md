# Coremod 混淆修复设计（方案 B）— 重生/盔甲注入在整合包静默失效

> 日期：2026-08-08　分支：`space-travel-twopointfive`
> 状态：**已实现，整合包实机验证通过**（B8 盔甲死亡不掉落已实测 OK；B7 重生点恢复正常）
> 关联待办：B7（死亡点失效+掉帧）、B8（盔甲死亡不掉落）——两者同一根因

## 一、问题现象（整合包 vs dev）

| 环境 | 现象 |
|------|------|
| dev（runClient） | 重生位置/维度正常，盔甲死亡保留正常 |
| **整合包（PCL2 全 mods）** | **死亡点失效（重生回原版位置）、盔甲死亡仍掉落**，且均**无崩溃、无报错**，只是静默失效 |

## 二、根因（已用日志 + FML 源码 + javap 三层铁证）

### 1. 铁证：整合包日志显示注入直接失败

`fml-client-latest.log`（1.7.10-hbm航天改实例）：

```
[com.hbm.core.HbmDeathDropTransformer:transform:55]
  [hbmcore] armor-drop transformer FAILED: IllegalStateException: method not found: func_70645_a
[com.hbm.core.HbmRespawnTransformer:transform:43]
  [hbmcore] respawn transformer FAILED: IllegalStateException: method not found: func_72368_a
```

两个 transformer 都抛了 `IllegalStateException` → 被自己的 try/catch 吞掉 → 返回原始字节 → **注入静默失败**。

### 2. 为什么 prod 找不到方法（transform 链时序）

**核心事实：整合包运行时，coremod transformer 收到的是 notch 混淆字节，不是 SRG 字节。**

1.7.10 的 transform 链（`LaunchClassLoader.runTransformers`）**顺序执行**，每个 transformer 的入参 `basicClass` 是**前一个 transformer 的输出**：

```
getClassBytes(untransformedName=notch名) → notch 原始字节
  → HbmRespawnTransformer（你的 coremod，先注册）
      ↑ 这里收到的是 notch 字节！方法名是 "a" 不是 "func_72368_a"
  → DeobfuscationTransformer（FML 注册，"always last"）
      ↑ 这里才把 notch → SRG
```

- FML 源码 `FMLDeobfTweaker` 注释明确：**"Deobfuscation transformer, always last"**
- coremod 的 transformer 由 `CoreModManager`（更早）注册 → **排在 Deobf 之前** → 先收到 notch 字节

### 3. 代码 bug：prod 分支用 SRG 方法名匹配 notch 字节

```java
// HbmRespawnTransformer（改前）
private static final String PROD_SCM = "oi";   // notch 类名 ✓ 对
String method = prod ? "func_72368_a" : "respawnPlayer";  // ✗✗ prod 用了 SRG 方法名！
```

- dev：收到 MCP 字节，方法名 `respawnPlayer` → dev 分支匹配 `respawnPlayer` ✓ 成功
- prod：收到 **notch 字节**，方法名是 `a` → prod 分支却匹配 `func_72368_a` → **找不到 → 抛异常 → 注入失败**

**一句话**：prod 分支应该匹配 notch 方法名（`a`），却写了 SRG 方法名（`func_72368_a`）。notch 类名对了，notch 方法名错了。

### 4. 为什么 dev 正常（不是巧合，是机制）

dev（runClient）是 **MCP 反编译环境**：磁盘 jar 就是 MCP 名（`respawnPlayer`），且 FML 检测到 `fml.deobfuscatedEnvironment=true` → **不注册 DeobfuscationTransformer**。所以 dev 下 transformer 收到的字节 = MCP 名 = dev 分支匹配的名字 → 成功。

## 三、修复方案 B：transformer 内先 deobf，统一走 SRG 逻辑

### 思路

不让 coremod 自己去猜 notch/SRG/MCP 三种名字，而是**在 transformer 内部先调用 FML 的 deobf 逻辑，把 notch 字节转成 SRG 字节**，然后统一用 SRG 名匹配（和 dev 同一套逻辑）。这样：

- 方法名匹配只写一套 SRG 名（`func_72368_a` / `func_70645_a` / `func_70436_m` / `func_73158_c`）
- 注入的 hook 调用 desc 用 SRG 类名 `Lnet/minecraft/entity/player/EntityPlayerMP;`
- 与 reobf 后的 hook 类签名**完全一致**（javap 已验证 hook 编译后就是 SRG 类名）

### 关键机制（已验证）

| 前提 | 验证结果 |
|------|---------|
| `FMLDeobfuscatingRemapper.INSTANCE` 在 coremod transform 前已 `setup()` | ✅ `FMLSanityChecker`（FMLCorePlugin 的 setupClass）调用，早于任何 vanilla 类加载 |
| `FMLRemappingAdapter` 对 SRG/MCP 名**幂等**（map 原样返回） | ✅ `map()` 只在 notch→SRG 表命中时才改写，SRG 名不在 key → 原样返回 |
| Deobf 二次处理不破坏注入代码 | ✅ 注入的 desc/method 都是 SRG 名，Deobf 再跑一遍仍幂等 |
| `FMLRemappingAdapter` 在编译 classpath 可用 | ✅ 位于 `forgeBin-1.7.10-10.13.4.1614-1.7.10.jar` |

### deobf 工具方法（两个 transformer 共用）

```java
// 在 transformer 内把 notch 字节转成 SRG 字节
private static byte[] deobfuscate(byte[] bytes) {
    ClassReader cr = new ClassReader(bytes);
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    FMLRemappingAdapter adapter = new FMLRemappingAdapter(cw);
    cr.accept(adapter, ClassReader.EXPAND_FRAMES);
    return cw.toByteArray();
}
```

（与 `DeobfuscationTransformer.transform` 完全相同的实现。）

### transform() 入口逻辑（改后）

```java
@Override
public byte[] transform(String name, String transformedName, byte[] basicClass) {
    if (basicClass == null) return null;
    String dotName = name.replace('/', '.');
    boolean isSCM = dotName.equals(DEV_SCM) || dotName.equals(PROD_SCM);
    if (!isSCM) return basicClass;

    try {
        // 关键：prod（notch 字节）先 deobf 成 SRG，再统一走 SRG 逻辑
        if (dotName.equals(PROD_SCM)) {
            basicClass = deobfuscate(basicClass);
        }
        return patchRespawnPlayer(basicClass);
    } catch (Throwable t) {
        System.err.println("[hbmcore] respawn transformer FAILED: " + t);
        t.printStackTrace();
        return basicClass;   // 保持容错：不崩游戏
    }
}
```

### patch 逻辑（改后，dev/prod 统一）

```java
// 不再需要 prod 分支判断，方法名、desc、owner 全用 SRG 名
private byte[] patchRespawnPlayer(byte[] classBytes) {
    String ehmp   = "net/minecraft/entity/player/EntityPlayerMP";  // 固定 SRG 类名
    String method = "func_72368_a";                                // 固定 SRG 方法名
    // ...其余锚点逻辑不变，owner 匹配用 SRG 类名
}
```

## 四、具体改动点

### 文件 1：`src/main/java/com/hbm/core/HbmRespawnTransformer.java`

1. 新增 import：`cpw.mods.fml.common.asm.transformers.deobf.FMLRemappingAdapter`
2. `transform()`：匹配到 PROD 类名时先 `deobfuscate(basicClass)`，再统一走 SRG 逻辑
3. `patchRespawnPlayer`：删除 prod 参数，方法名固定 `func_72368_a`，`ehmp` 固定 SRG 类名
4. 新增私有 `deobfuscate(byte[])` 工具方法

### 文件 2：`src/main/java/com/hbm/core/HbmDeathDropTransformer.java`

1. 同样新增 deobf 处理（PROD_EP / PROD_EMP 时先 deobf）
2. `patchEntityPlayerOnDeath` / `patchEntityPlayerMPOnDeath`：方法名固定 `func_70645_a`（onDeath）、`func_70436_m`（dropAllItems），owner/desc 用 SRG 类名

## 五、验证方法

1. **编译**：`./gradlew build`（含 reobf，产出 SRG 正式版）
2. **javap 静态验证**：确认 transformer 内调用 `FMLRemappingAdapter`，hook desc 用 SRG 类名
3. **整合包实机验证**：把新 jar 放进 `1.7.10-hbm航天改/mods/`，启动后 `fml-client-latest.log` 应出现：
   - `[hbmcore] injected dimension+position hooks into func_72368_a`
   - `[hbmcore] injected armor-drop hook into EntityPlayerMP.func_70645_a` / `EntityPlayer.func_70645_a`
   - 不再出现 `[hbmcore] ... transformer FAILED: method not found`
4. **功能验证**：游戏内测试死亡重生位置（SPAWN）+ 死亡后盔甲保留

## 六、为什么不用方案 A（notch 名硬编码）

- notch 方法名是单字母（`a`），可读性差、依赖具体 notch 版本、类内可能有多个重载 `a` 需额外 desc 校验
- 方案 B 让 dev/prod 逻辑统一为 SRG 名，与 reobf jar 和 FML 运行时一致，长期可维护

## 七、关联记忆

- [[mod-reobf-mcp-srg]]（reobf 铁律：必须 gradlew build 产出 SRG）
- [[respawn-bug-rootcause]]（B7 详细根因）
- 待办 [[todo-list]] B7/B8
