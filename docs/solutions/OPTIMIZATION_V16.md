# Private Reader 项目优化方案 V16 —— NotificationServiceImpl 章节变更事件拆分与覆盖率护栏上调

> 创建日期: 2026-10-10
> 项目版本: 3.1.0
> 配套: [OPTIMIZATION_V15.md](./OPTIMIZATION_V15.md)(V15 缓存设置字段去重 / 启动清理修复)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 兑现 V11/V13/V14/V15 连续多轮遗留的「`NotificationServiceImpl` 过大,可再拆 `ChapterEventProcessor`」,
>      并顺带上调覆盖率护栏

---

## 一、背景

`NotificationServiceImpl` 在 V11 拆分展示层后仍长期维持在 ~1186 行,是 `docs/solutions/README.md`
连续 3 轮标注的头号技术债:

| 来源 | 遗留描述 | 优先级 |
|------|----------|--------|
| V11 | `NotificationServiceImpl` 仍有 ~1174 行,可再拆 `ChapterEventProcessor` | 低 |
| V13 | 同上 | 低 |
| V14 | 同上(注入化收尾后仍未拆分) | 低 |

本轮聚焦其中一个**内聚性最强、边界最清晰**的职责:**跨模式章节变更事件处理**
(`handleChapterChangedEvent`)。该职责负责在阅读器面板切换章节时,若处于通知栏模式则同步更新通知栏
展示,原生依赖 `ChapterChangeEventSource` / `ReaderModeSettings` / `ProjectManager` 等外部状态,
与通知栏服务本身的「状态编排」耦合度低,适合先行拆出。

## 二、改动内容

### 2.1 新增 `ChapterEventProcessor`(228 行)

`service/impl/notification/ChapterEventProcessor.java`,承接原 `handleChapterChangedEvent` 全链路:

1. 事件源门控(仅处理 `READER_PANEL` 事件,避免自身发布事件回环)
2. 通知栏模式门控(`ReaderModeSettings.isNotificationMode()`)
3. 打开项目解析(`ProjectManager.getOpenProjects()`)
4. 拉取新章节内容 + 标题(响应式链,`io` 线程订阅)
5. UI 线程内分页、恢复页码、更新状态、展示通知、保存进度

**解耦设计——窄接口 `Host`**:处理器不直接依赖 `NotificationServiceImpl` 完整 API,而是定义只含
6 个方法的 `Host` 接口(读/写视图状态、分页、展示当前页、展示错误、保存进度)。宿主以匿名内部类实现,
映射到自身内部方法。这样处理器可脱离完整服务独立测试,也阻断了后续再膨胀时的反向依赖。

**重入保护**:原 `isHandlingEvent` 由宿主服务下沉到处理器内部,职责归属更自然。

### 2.2 `NotificationServiceImpl` 瘦身

- 删除 `handleChapterChangedEvent` 方法体(约 136 行)与 `isHandlingEvent` 字段
- 新增 `chapterEventProcessor` 字段 + `eventProcessorHost` 匿名实现(约 40 行)
- 消息总线订阅回调改为委托 `chapterEventProcessor.handleChapterChangedEvent(...)`
- 净变化:**1186 → 1096 行(—90 行)**

> 选择把 `Host` 实现放在服务内(而非让服务实现 `Host`),是为了避免把 6 个内部方法暴露为 public
> 而污染 `NotificationServiceImpl`/`NotificationService` 的对外契约。

## 三、测试

### 新增 `ChapterEventProcessorTest`(4 测试)

采用「可断言内存 Host」策略:实现一个复用真实 `ReaderViewState` + `ChapterPaginationCache`
的 `TestHost`,验证处理器对宿主的调用序列与状态结果,而不依赖 IntelliJ 通知链路。

| 测试 | 断言 |
|------|------|
| `ignoresEventWhenSourceIsNotReaderPanel` | 事件源为 `NOTIFICATION_SERVICE` 时零副作用 |
| `ignoresEventWhenNotInNotificationMode` | 非通知栏模式时零副作用 |
| `syncsChapterIntoNotificationOnReaderPanelChange` | 正常链路:更新书/章状态、展示解析标题、保存进度一次、分页非空 |
| `restoresSavedPageForMatchingChapter` | 仓库存在匹配章节进度时恢复到该页码(3 → 索引 2) |

隔离沿用成熟套路:`mockStatic(ApplicationManager)` + `invokeLater` 同步化、
`mockStatic(ProjectManager)`、`mockStatic(ModalityState)`,并新增
`RxJavaPlugins.setIoSchedulerHandler(trampoline)` 让 `subscribeOn/observeOn(io)` 同步执行
(规避 `mockStatic` 线程本地性陷阱,见 README 踩坑 #5)。

## 四、覆盖率

| 指标 | V15 文档记录 | V16 基线(3.1.0) | V16 | 变化(对基线) |
|------|--------------|------------------|-----|---------------|
| LINE | 38.34% | 39.13%(3088/7891) | **39.95%(3161/7912)** | +0.82pp |
| 测试数 | 311 | 327 | **331** | +4 |

> V15 文档记录的 311 为 3.0.0 时口径;V15→V16 之间 3.1.0 的多项缺陷修复已并入回归测试,
> 故本轮以重新实测的 3.1.0 基线 327 为准,V16 新增 4 个 `ChapterEventProcessorTest` 后达 331。
> 精确口径以 JaCoCo/测试报告为准。

`ChapterEventProcessor` 自身 LINE 覆盖率 **74.2%(72/97)**,拆分出的代码被新测试直接覆盖。

### 护栏上调

实测 39.95%,余量 4.95pp,按 V15 建议将 `jacocoTestCoverageVerification` 阈值 **0.30 → 0.35**
(余量约 5pp),收紧回归护栏。

## 五、验证结果

- `./gradlew test` — ✅ **331 测试全通过**(0 失败 / 0 错误)
- `./gradlew build` — ✅ 打包 + JaCoCo LINE **39.95%** > 阈值 0.35

## 六、遗留事项

- [ ] `NotificationServiceImpl` 仍有 ~1096 行,可继续拆导航流水线(`processChapterNavigation*` /
      `showNavigatedChapter` / `resolveNavigationTarget`)为 `ChapterNavigator`
- [ ] `NotificationServiceImpl.showChapterContent(Book,...)`(Reactive 兼容路径,~130 行)疑似无生产
      调用方,评估可否删除(需先确认二进制兼容性)
- [ ] `NotificationDisplayManager.showReadingNotification` 真实通知创建路径仍未覆盖(32.8%),
      需 mockStatic 门面或 GUI 冒烟
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,仍需真实 IDE 或 UI 测试框架
- [ ] `storage` 主体、`initialization` 为低覆盖保留方向

## 七、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/impl/notification/ChapterEventProcessor.java` | **新增** | 章节变更事件处理器 + 窄接口 `Host` |
| `service/impl/NotificationServiceImpl.java` | 修改 | 删除事件处理方法与 `isHandlingEvent`,委托新处理器(—90 行) |
| `service/impl/notification/ChapterEventProcessorTest.java` | **新增** | 4 个事件门控/同步/页码恢复测试 |
| `build.gradle` | 修改 | 版本 3.1.0→3.1.1,覆盖率阈值 0.30 → 0.35,插件变更说明 |
| `src/main/resources/META-INF/plugin.xml` | 修改 | change-notes 更新至 3.1.1 |
| `CHANGELOG.md` | 修改 | 3.1.1 条目(重构/质量) |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V16 |