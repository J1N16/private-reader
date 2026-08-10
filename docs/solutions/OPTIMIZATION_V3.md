# Private Reader 项目优化方案 V3

> 生成日期: 2026-08-10
> 项目版本: 2.5.1
> 分析范围: 全量代码核查（106 文件 / 约 1.9 万行）
> 前序文档: [OPTIMIZATION.md](./OPTIMIZATION.md)（2.5.0 实施记录）、[OPTIMIZATION_PLAN.md](./OPTIMIZATION_PLAN.md)（2.5.0 计划）

---

## 实施记录

| 批次 | 任务 | 状态 | 完成日期 |
|------|------|------|----------|
| A | P0-1 统一缓存路径 | ✅ 已完成 | 2026-08-10 |
| A | P0-2 收敛 ReactiveTaskManager | ✅ 已完成 | 2026-08-10 |
| A | P0-3 ReaderViewModel 线程安全 | ✅ 已完成 | 2026-08-10 |
| B | P1-1 统一 HTTP 执行路径 | ✅ 已完成 | 2026-08-10 |
| B | P1-2 日志降级与格式化 | ✅ 已完成 | 2026-08-10 |
| B | P1-3 DatabaseManager 连接复用 | ✅ 已完成 | 2026-08-10 |

### 批次 A 已完成的修改

| 编号 | 文件 | 修改内容 |
|------|------|----------|
| P0-1 | `ReactiveChapterCacheRepositoryImpl.java` | 缓存目录改从 `StorageRepository.getCachePath()`（`~/.private-reader/cache`）获取，替代硬编码 `~/.privatereader/chapter_cache`；新增 `migrateLegacyCacheDir()` 一次性迁移旧缓存 |
| P0-2 | `BookshelfDialog.java` | 删除 `ReactiveTaskManager.cancelTasksByPrefix()` 无效调用，改用 `ReactiveChapterPreloader.stopPreload()` 停止书籍预加载 |
| P0-2 | `plugin.xml` | 移除 `ReactiveTaskManager` 的 applicationService 注册（类本身已删除，双实例问题根除） |
| P0-2 | `async/ReactiveTaskManager.java` | ✅ 已删除（`submitTask` 从未被调用，属死代码） |
| P0-3 | `ReaderViewModel.java` | `intentSubject` 用 `PublishSubject.<IReaderIntent>create().toSerialized()`；新增 `synchronized updateState()` 收敛所有"读-改-写"状态更新，消除跨线程竞态 |

### 批次 B 已完成的修改

| 编号 | 文件 | 修改内容 |
|------|------|----------|
| P1-1 | `util/SafeHttpRequestExecutor.java` | 删除 `executeGetRequest(url, RequestConfigurator)` 重载与 `RequestConfigurator` 接口（全项目无调用方，走自建线程池路径已统一）；移除多余 `ApplicationManager` import |
| P1-2 | `util/SafeHttpRequestExecutor.java` | `logPerformanceStats()` 与请求开始/成功日志降级为 `LOG.debug` + 添加 `isDebugEnabled()` 快速路径 + 改用 `{}` 占位符（避免无条件 `String.format`） |
| P1-2 | `parser/site/UniversalParser.java` | 章节页面连接/获取/解析 3 处 `LOG.info` 降级为 `LOG.debug` |
| P1-2 | `service/impl/NotificationServiceImpl.java` | 分页、关闭通知、翻页显示、页码调试、空章节检查等 10 余处 `LOG.info` 降级为 `LOG.debug`，改用 `{}` 占位符 |
| P1-3 | `storage/DatabaseManager.java` | `getConnection()` 改为复用单连接：`ReentrantLock` 排他锁 + `Proxy` 动态代理包装（`close()` 释放锁而非关闭底层连接），懒加载共享连接，`dispose()` 统一关闭 |

**批次 B 验证**：`./gradlew test` 20 个测试全通过（5 个测试类，0 失败 / 0 错误 / 0 跳过）。

---

## 一、前序文档核对结果

旧文档声称"已完成"的项目中，经代码核实仍有 3 项**未真正落实**：

| 旧文档项目 | 声称状态 | 代码现状 | 证据 |
|-----------|---------|---------|------|
| P0-2 DatabaseManager 连接池 | ✅ 已完成 | ❌ 每次 `getConnection()` 仍 `DriverManager.getConnection()` 新建连接，注释与实现不符 | `DatabaseManager.java:94-98` |
| P0-3 NotificationServiceImpl 拆分 | ✅ 已完成 | ⚠️ 仅拆出 2 个小 helper（共 168 行），主类仍 **1506 行**，4 组导航方法重复 | `NotificationServiceImpl.java` |
| P2-3 删除未使用的 CacheManager | ⏭️ 跳过 | ❌ `CacheManager.java` 仍在，全项目无引用 | `cache/CacheManager.java` |
| P2-4 ReaderViewModel 线程安全 | ⏭️ 跳过 | ❌ 未落实（见 P0-3） | `ReaderViewModel.java:39-40` |

另发现旧文档**未覆盖**的新问题：缓存路径不一致（P0-1）、ReactiveTaskManager 双实例（P0-2）、日志噪音（P1-2）等。

---

## 二、已核实问题清单（按优先级）

### P0 级 —— Bug / 数据一致性 / 线程安全

#### P0-1 章节缓存目录路径不一致【Bug】
**问题**: 主存储与章节缓存使用了不同的根目录，缓存完全与主存储隔离：
- `StorageManager`: 根目录 `~/.private-reader/`，缓存子目录 `cache/`
- `ReactiveChapterCacheRepositoryImpl.initCacheDir()`: 硬编码 `~/.privatereader/chapter_cache`（**少了连字符** + 子目录名也不同）

**后果**:
- 缓存设置界面统计/清理的目录与章节实际读写目录不一致，用户清缓存无效
- 卸载或迁移数据时 `.privatereader` 成为遗留目录
- 两套路径并存，无法统一备份/迁移

**证据**: `StorageManager.java:48-52` vs `ReactiveChapterCacheRepositoryImpl.java:58-70`

**修复**: 章节缓存仓库通过 `StorageRepository`/`StorageManager` 获取缓存根目录（或复用 `~/.private-reader/cache`），删除硬编码路径。

#### P0-2 ReactiveTaskManager 双实例【架构】
**问题**: `plugin.xml` 将 `ReactiveTaskManager` 注册为 applicationService，但代码内 `getInstance()` 使用 `SingletonHolder` 手动单例。`BookshelfDialog.java:221` 走手动单例，与 IntelliJ 托管实例是**两个不同对象**，各自启动一个 1 分钟常驻监控定时器。

**证据**: `plugin.xml:214` + `ReactiveTaskManager.java:40-46`

**修复**: 三选一（推荐①）：
1. 删除 plugin.xml 注册，仅保留手动单例（项目内 `getInstance()` 风格已统一，且 IntelliJ 服务实例无人通过 `getService` 获取）
2. 删除手动单例，改为 IntelliJ 服务 + `getService` 获取
3. 若整个类仅有 `BookshelfDialog` 一处使用，直接内联删除，简化架构

#### P0-3 ReaderViewModel 响应式竞态【线程安全】
**问题**: `intentSubject`（PublishSubject）从 UI 线程 `onNext`、io 线程 `observeOn` 消费；`uiState`（BehaviorSubject）从多个 io/UI 线程 `onNext`。均未 `.toSerialized()`，存在并发发射竞态，可导致状态丢失或偶发异常。

**证据**: `ReaderViewModel.java:39-40`

**修复**:
```java
private final PublishSubject<IReaderIntent> intentSubject = PublishSubject.create().toSerialized();
private final BehaviorSubject<ReaderUiState> uiState =
    BehaviorSubject.createDefault(ReaderUiState.initial()).toSerialized();
```

---

### P1 级 —— 性能 / 一致性

#### P1-1 网络请求两条执行路径不一致【一致性】
**问题**: 
- `SafeHttpRequestExecutor.executeGetRequest(url)`：走自建 HTTP 线程池 + 完整 NetworkPerformanceMonitor 统计
- `SafeHttpRequestExecutor.executeGetRequest(url, configurator)`：走 `ApplicationManager.executeOnPooledThread`，**绕过**性能监控与重试机制

同一执行器两套行为，监控数据不完整。

**证据**: `SafeHttpRequestExecutor.java:353-390` vs `:118-292`

**修复**: 统一到自建线程池路径，configurator 变体复用主路径（将 `NetworkPerformanceMonitor` 调用抽到公共方法）。

#### P1-2 日志级别噪音【性能】
**问题**: 大量 INFO 级 + 无条件 `String.format` 日志，生产环境每次翻页/切章/HTTP 请求都产生多行 INFO 输出：
- `NotificationServiceImpl.paginateContent()` 逐页打印 INFO（`:918-976`）
- `closeCurrentNotificationInternal()` 每次关闭打 INFO（`:1260`）
- `SafeHttpRequestExecutor` 每个请求 2-3 行 INFO + 每请求 `logPerformanceStats()`（`:138-210`）
- `ReactiveTaskManager.monitorTasks()` 每分钟遍历打印所有任务指标（`:202-214`）

**后果**: 日志 I/O 开销、`String.format` 无条件执行（即使日志被过滤）、问题定位被噪音淹没。

**修复**: 
- 将上述 `LOG.info` 降为 `LOG.debug`
- `LOG.info(String.format(...))` → `LOG.info("... {} ...", arg)`（延迟格式化）
- `monitorTasks()` 改为仅在有失败/超时任务时打印，或整体移除

#### P1-3 DatabaseManager 连接复用【性能】
**问题**: 每次 `getConnection()` 都新建 SQLite 连接，注释声称"缓存连接"但未实现。进度保存频繁触发时反复建连。

**证据**: `DatabaseManager.java:36,94-98`

**修复**: 引入单连接复用 + `synchronized`（SQLite 单写入者模型），或引入轻量连接池（HikariCP）。注意 IntelliJ 卸载/重载插件时的连接生命周期，需实现 `Disposable` 关闭连接。

---

### P2 级 —— 死代码清理

| 编号 | 目标 | 现状 | 建议 |
|------|------|------|------|
| P2-1 | `cache/CacheManager.java` | 全项目 0 引用 | 删除 |
| P2-2 | `UniversalParser.calculateGarbageScore()` / `isPunctuationMark()` | 定义但从未调用；README 声称"自动修复乱码内容"实际未接线 | 接线到解析流程，或删除方法并修正 README 描述 |
| P2-3 | `ReactiveSchedulers` 冗余调度器 | `BACKGROUND`==`IO`；`TIMER`/`PLATFORM` 未使用；`getDetailedStatusReport()`/`shutdown()`/`calculateOptimalThreads()` 无调用方 | 裁剪为 `io()` 单方法，删除冗余调度器与死方法 |
| P2-4 | `NotificationService.showPrevPageReactive()` / `showNextPageReactive()` / `navigateChapterReactive()` | 实现即 `UnsupportedOperationException`，无调用方 | 从接口与实现中删除 |
| P2-5 | `StorageManager.createBackup()/restoreFromBackup()`、`FileStorageRepository` 备份/恢复、`ExceptionHandler` 存储/解析恢复 | 空 TODO 实现 | 实现或删除（若 UI 不可达则删除） |

---

### P3 级 —— 可维护性重构

#### P3-1 NotificationServiceImpl 拆分【可维护性】
**问题**: 1506 行，职责混杂（通知显示、章节导航、进度管理、事件处理），4 组几乎相同的导航方法重复实现：
- `processChapterNavigation` / `processChapterNavigationWithCachedChapters`
- `processChapterNavigationToLastPage` / `processChapterNavigationToLastPageWithCachedChapters`

**重构方向**（沿用 P0-3 未完成方案，低风险优先）:
1. 抽 `PaginationHelper`：`paginateContent` 逻辑（当前每次翻页都重新分页整章，可缓存分页结果）
2. 合并 4 组导航方法为 2 组（`withCachedChapters` 与 `fromChapters` 的差异仅是数据源，可抽公共处理）
3. 保留 `ChapterNavigationHelper` / `ProgressSaveHelper` 已拆分成果

#### P3-2 UniversalParser 全局代理副作用【副作用】
**问题**: `initialize()` 中 `System.setProperty("http.proxyHost", "")` 等 4 行清除 JVM 全局代理设置，影响 IDE 其他网络功能，且每次初始化都执行。

**证据**: `UniversalParser.java:64-67`

**修复**: 在请求层通过 RequestBuilder 配置代理（或删除——请求走 `SafeHttpRequestExecutor` 时应继承 IDE 代理设置）。

#### P3-3 分页重复计算【性能】
**问题**: `NotificationServiceImpl.setCurrentChapterContent()` 每次调用都 `paginateContent()` 整章重分页，翻页时（`updateAndShowPage`）虽不重分页，但 `showChapterContent` 与事件处理路径多次全量分页。大章节多次 O(n)。

**修复**: 缓存当前章节的分页结果（章节 id → pages），仅当内容变更时重分页。

---

## 三、实施批次建议

> 每批完成后执行：`./gradlew build` + 全部测试 + 手动冒烟（添加书籍、阅读、翻页、进度保存、切模式）。

### 批次 A —— P0（3 项，建议先行）
1. **P0-1** 缓存路径统一（修复后清理遗留 `~/.privatereader`）
2. **P0-2** ReactiveTaskManager 双实例收敛
3. **P0-3** ReaderViewModel `toSerialized()`

风险：低。涉及文件：`ReactiveChapterCacheRepositoryImpl`、`plugin.xml`、`ReactiveTaskManager`、`BookshelfDialog`、`ReaderViewModel`。**注意 P0-1 修复后旧缓存会"丢失"（需迁移或接受重建）**。

### 批次 B —— P1（3 项）
1. **P1-1** HTTP 执行路径统一
2. **P1-2** 日志降级与格式化
3. **P1-3** DatabaseManager 连接复用

风险：低。涉及文件：`SafeHttpRequestExecutor`、`NotificationServiceImpl`、`ReactiveTaskManager`、`DatabaseManager`。

### 批次 C —— P2（死代码清理）
删除上述 5 处死代码。风险：极低，但需全量编译确认无遗漏引用。

### 批次 D —— P3（重构）
P3-1 拆分类、P3-2 代理副作用、P3-3 分页缓存。风险：中，建议 P3-1 单独一个批次并补充回归测试。

---

## 四、验证标准

每个批次完成后的验收清单：
- [ ] `./gradlew build` 成功
- [ ] `./gradlew test` 全部通过（现有测试：ReaderViewModelTest / FileBookRepositoryTest / BookServiceImplTest / DatabaseManagerTest / ChapterChangeManagerTest）
- [ ] 插件可正常加载，工具窗口正常显示
- [ ] 添加书籍 → 解析 → 阅读 → 翻页 → 进度恢复 全链路可用
- [ ] 通知栏模式切换、章节预加载、缓存清理功能正常
- [ ] 无新增 IDE 错误日志（Event Log）

---

## 五、已知约束与注意事项

1. **P0-1 数据迁移**：缓存路径统一后，旧 `~/.privatereader` 下的章节缓存不会自动迁移。建议在 `StorageManager.performPostInitializationTasks()` 中做一次性迁移（旧缓存 → 新缓存目录），或接受重建（仅影响缓存命中，不影响书籍数据与进度）。
2. **P1-3 连接生命周期**：SQLite 连接复用需在插件 dispose 时关闭，避免 IDE 重载插件时句柄泄漏。
3. **日志降级需谨慎**：`LOG.debug` 级别在用户开启 debug 日志时才输出，不影响问题排查（可用 `git log` 对照历史调试）。
