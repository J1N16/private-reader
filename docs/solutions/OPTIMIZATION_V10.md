# Private Reader 项目优化方案 V10 —— 预加载器/接口 default 方法/存储读写补测

> 创建日期: 2026-08-13
> 项目版本: 2.5.1
> 配套: [OPTIMIZATION_V9.md](./OPTIMIZATION_V9.md)（零覆盖包补测与覆盖率护栏上调）
> 实施状态: 已完成 ✅（2026-08-13）

---

## 一、背景

V9 补测后遗留三个低覆盖方向：`storage/cache`（预加载器）、接口 default 方法
（`NovelParser`）、`storage`（`SettingsStorage` 文件读写）。V10 补测这三个方向，
总 LINE 覆盖率 28.02% → **31.02%**。

## 二、补测范围与策略

| 测试文件 | 测试数 | 覆盖要点 |
|----------|--------|----------|
| `ReactiveChapterPreloaderTest` | 9 | 优先级预加载顺序、缓存命中跳过、插件/预加载禁用、null 解析器/空章节、解析错误容忍、stopPreload |
| `NovelParserDefaultMethodsTest` | 8 | `getChapterList` 缓存优先/解析刷新/失败降级；`getChapterContent` 缓存命中/回写/过期回退/无仓库直连 |
| `SettingsStorageTest` | 8 | 文件写入路径/目录创建/JSON 序列化、缺失/空文件返回 null、字段往返、覆盖写 |

**合计**：3 个测试文件，**25 个新增测试方法**（原 209 → 现 236）。

## 三、覆盖率提升

| 指标 | V9 | V10 | 提升 |
|------|-----|-----|------|
| LINE | 28.02%（2606/7581） | **31.02%（2352/7581）** | +3.00pp |

重点包：

| 包 | V9 | V10 |
|----|-----|-----|
| storage/cache | 0%（零覆盖） | **89.5%** |
| parser | 0%（零覆盖） | **75.4%** |
| storage | 0%（零覆盖） | **22.4%** |
| repository | 0%（零覆盖） | 2.1%（仅接口 default 方法走了一遍） |

仍为低覆盖（保留后续补测方向）：`ui/*`（EDT/Swing 依赖）、`storage` 主体、
`repository` 实现类、`initialization`、`service/impl`。

## 四、阈值护栏

`build.gradle` 中 `jacocoTestCoverageVerification` 维持 **0.27**（低于实际 31.02%，
留有约 4 个百分点余量）。

## 五、踩坑记录

### 5.1 Mockito mock 接口默认不执行 default 方法

**现象**：`mock(NovelParser.class)` 后调用 `getChapterList`/`getChapterContent`，
断言值全部为 null（default 方法被 mock 替代，返回默认值）。

**根因**：Mockito 对接口的 mock 默认不调用 default 方法体，只返回类型默认值。

**解决**：用 `mock(NovelParser.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))`，
让 default 方法执行真实逻辑、抽象方法由 stub 提供。

### 5.2 mockStatic 的线程本地性与 Schedulers.io()

**现象**：Preloader 测试在 `preloadChapter` 内 `ApplicationManager.getApplication()` 返回
null 抛 NPE；外层 `doOnError(LOG.error)` 又抛 AssertionError（见 V9 5.1）。

**根因**：源码 `subscribeOn(Schedulers.io())` 把 lambda 挪到 io 线程执行，而
`MockedStatic` 的作用域是**注册时的线程**（测试线程），io 线程上静态门面未生效。

**解决**：在 `setUp` 用 `RxJavaPlugins.setIoSchedulerHandler` 与
`setComputationSchedulerHandler` 把 io/computation 调度器替换为
`Schedulers.trampoline()`，并调用 `Schedulers.io()`/`computation()` 触发单例重建，
确保预加载在测试线程同步执行。`tearDown` 中 `RxJavaPlugins.reset()` 恢复。

### 5.3 SettingsStorage 反序列化实例的 getter 陷阱

**现象**：对 `loadSettings` 返回的实例调用 getter，会触发
`ensureSettingsLoaded()` → `SettingsStorage.getInstance()` 静态门面（在测试环境返回
null → LOG.error → AssertionError）。

**根因**：反序列化实例 `loaded=false`，首次 getter 触发再次加载。

**解决**：用反射读私有字段验证往返（`fieldValue(loaded, "cacheExpiryHours")`），
不触发 getter。

## 六、验证结果

- `./gradlew clean build` — ✅ 全链路通过（236 测试 + JaCoCo 报告 + 0.27 阈值）
- 单元测试总数 99 → 209 → **236**
- LINE 覆盖率 18.24% → 28.02% → **31.02%**

## 七、遗留事项

- [ ] `ui/*`（dialog/actions/settings）依赖 EDT/Swing，仍需真实 IDE 或 UI 测试框架
- [ ] `storage` 主体（文件仓储实现）、`repository` 实现类、`initialization`、
      `service/impl`（网络链路）为下一轮补测候选
- [ ] 若后续覆盖率达 35%+，可再次上调阈值至 0.32
