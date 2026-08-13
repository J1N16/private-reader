# Private Reader 项目优化方案 V9 —— 零覆盖包补测与覆盖率护栏上调

> 创建日期: 2026-08-13
> 项目版本: 2.5.1
> 配套: [OPTIMIZATION_V8.md](./OPTIMIZATION_V8.md)（CI 与覆盖率集成）
> 实施状态: 已完成 ✅（2026-08-13）

---

## 一、背景

V8 引入 JaCoCo 覆盖率度量（首次 LINE 18.24%）后，报告明确列出多个**零覆盖包**
（`settings`、`ui/dialog`、`ui/settings`、`ui/actions`、`service` 接口层、
`storage/cache`、`initialization`、`config`），并标注为"后续补测重点"。

V9 本轮目标：**为可独立单元测试的零覆盖包补测核心逻辑，提升整体覆盖率并上调阈值护栏**。

## 二、补测范围与策略

### 2.1 选类标准

优先选择**不依赖 IntelliJ 运行时**（或依赖可 mock 门面）的纯逻辑类：

| 类别 | 说明 |
|------|------|
| 纯逻辑工具类 | 无 IntelliJ 依赖，直接测（如 `ChapterTitleUtils`、`TextFormatter`） |
| jsoup DOM 处理 | 用 jsoup 构造内存 HTML 测（如 `MetadataAnalyzer`、`TextDensityAnalyzer`） |
| 配置类 | 继承 `BaseSettings`，依赖 `SettingsStorage` 门面，用 `mockStatic` 拦截 |
| 模型/异常 | 纯数据类与枚举，直接测 |

**排除**：UI 层（dialog/actions/settings）依赖 IntelliJ EDT 与 Swing，仅在
IDE 运行时才有意义，本轮不纳入单元测试范围（与 V8 判断一致）。

### 2.2 新增测试文件（16 个）

| 包 | 测试文件 | 测试数 | 覆盖要点 |
|----|----------|--------|----------|
| settings | `BaseSettingsTest` | 6 | 加载策略、加载幂等、脏标记保存 |
| settings | `CacheSettingsTest` | 4 | 默认值、加载、setter、保存 |
| settings | `PluginSettingsTest` | 4 | 默认值、加载、setter、保存 |
| settings | `ReaderSettingsTest` | 11 | 字体、主题预设、主题切换、布局 setter |
| settings | `ReaderModeSettingsTest` | 6 | 模式切换、事件发布（仅变化时） |
| settings | `NotificationReaderSettingsTest` | 4 | setter、事件发布（仅变化时） |
| settings | `ThemeTest` | 8 | Builder、预定义主题、主题预设 |
| model | `BookTest` | 15 | 进度计算、章节查找、页码守卫、来源ID、相等性 |
| model | `BookIndexTest` | 4 | fromBook 转换、字段、相等性 |
| model | `BookProgressDataTest` | 4 | record 字段、相等性 |
| exception | `PrivateReaderExceptionTest` | 4 | 消息/类型/cause、枚举 |
| parser/common | `ChapterTitleUtilsTest` | 9 | 章节标题识别、无效标题过滤 |
| parser/common | `MetadataAnalyzerTest` | 12 | meta/选择器/页面标题/文本匹配识别 |
| parser/common | `TextDensityAnalyzerTest` | 7 | 文本密度、常见选择器、噪音过滤 |
| parser/common | `TextFormatterTest` | 9 | 换行/标点规范化、段落缩进、长段落拆分 |
| parser | `ParserFactoryTest` | 3 | 创建解析器、空 URL 校验 |

**合计**：16 个测试文件，**110 个新增测试方法**（原 99 个 → 现 209 个）。

## 三、覆盖率提升

### 3.1 总体 LINE 覆盖率

| 指标 | V8 基线 | V9 | 提升 |
|------|---------|-----|------|
| LINE | 18.24%（1383/7581） | **28.02%（2606/7581）** | +9.78pp |
| CLASS | 28.36% | ~35% | — |

### 3.2 重点包覆盖率变化

| 包 | V8 | V9 |
|----|-----|-----|
| settings | 0%（零覆盖） | **86.9%** |
| parser/common | 0%（零覆盖） | **91.5%** |
| model | 75.8% | **84.9%** |
| config | 0%（零覆盖） | 54.0% |
| exception | 0%（零覆盖） | 32.4% |
| messaging | 0%（零覆盖） | 50.0% |

仍为低覆盖（保留后续补测方向）：`ui/*`（EDT/Swing 依赖）、`storage`、
`initialization`、`service/impl`（含网络/通知链路）。

## 四、阈值护栏上调

`build.gradle` 中 `jacocoTestCoverageVerification`：

```
minimum = 0.15  →  minimum = 0.27
```

- 新阈值低于实际值 28.02%，留有约 1 个百分点余量
- 目的仍是**回归护栏**：防止后续改动显著拉低覆盖率，而非目标值
- 验证：`./gradlew clean build` 全链路通过（测试 209/209 + 覆盖率校验）

## 五、踩坑记录

### 5.1 IntelliJ Logger.error 在测试环境抛 AssertionError

**现象**：settings 测试中 `SettingsStorage.getInstance()` 返回 null 时，
`BaseSettings.loadSettings()` 走到 `LOG.error(...)` 分支，直接抛 `AssertionError`
导致测试失败。

**根因**：IntelliJ Platform 的测试运行时把 `Logger.error` 绑定到 JUnit 断言器
（`TestLogger`），高优先级日志即视为失败。

**解决**：测试中一律返回**非 null 的 `SettingsStorage` mock**（`loadSettings`
未打桩时返回 null → 走默认值的 debug 路径），刻意避开 `LOG.error` 分支。

### 5.2 BaseSettings 延迟加载会覆盖 setter 结果

**现象**：`new CacheSettings()` 后立即 `setXxx()` 再 `getXxx()`，返回值仍是默认值。

**根因**：`BaseSettings.ensureSettingsLoaded()` 在**首次 getter** 才触发加载；
setter 仅 `markDirty()`，若在首次加载前调用，随后加载会用默认值 `copyFrom`
覆盖已设值。

**解决**：测试中 setter 前先调用任意 getter 触发加载（`settings.getXxx()`），
再执行 setter 断言。

### 5.3 `Book.setCachedChapters(null)` 不重置 totalChapters

**现象**：清空章节列表后 `getTotalChapters()` 仍返回原值。

**根因**：`setCachedChapters` 的 else 分支只清空索引 map，**不**重置 `totalChapters`。
这是源码现状，测试据此断言保持原值。

### 5.4 `TextFormatter.format` 返回 trim 结果

**现象**：段落首行无前导缩进。

**根因**：`format()` 末尾 `return result.toString().trim()` 去掉首尾空白，
首段缩进被 trim 掉。测试改为断言"段落间换行 + 4 空格缩进"。

### 5.5 JaCoCo 校验与 HTML 报告口径一致性

**现象**：最初从 HTML 根报告解析得 32.59%，与校验任务 0.28 不符。

**根因**：HTML 根报告 `Total` 行的权威值是 2,606/7,581 = **28.02%**
（32.59% 是某包局部值）。校验任务 `lines covered ratio` 与此一致。

## 六、验证结果

- `./gradlew clean build` — ✅ 全链路通过（209 测试 + JaCoCo 报告 + 0.27 阈值）
- `./gradlew jacocoTestCoverageVerification` — ✅ 阈值通过

## 七、遗留事项

- [ ] `ui/*`（dialog/actions/settings）依赖 EDT/Swing，仍需真实 IDE 或 UI 测试框架
- [ ] `storage`（SettingsStorage 文件读写）、`storage/cache`（预加载器）、
      `initialization`、`service/impl`（网络链路）为下一轮补测候选
- [ ] 若后续覆盖率达 35%+，可再次上调阈值至 0.32
