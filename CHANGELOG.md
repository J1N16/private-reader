# Changelog 更新日志

## [3.1.2] - 2026-11-03

### 修复 | Fixes
- 修复通知栏模式在第一页向前翻页时误报「导航失败」:章节导航的兜底数据源调用了始终返回 `null` 的 `BookService.getChaptersSync`,而 RxJava 的 `Single.fromCallable` 不接受 `null`,会抛 NPE 并被包装为「获取章节列表时出错」。现导航统一改用 `ChapterService.getChapterList/getChapterContent`(自带缓存、网络与过期回退),并校验章节索引 Map 与当前列表是否一致(失效时回退线性查找),信息提示后同时复位加载状态,避免边界提示后翻页被永久短路 | Fixed the false "navigation failed" error when paging back from the first page in notification-bar mode: the fallback chapter source called `BookService.getChaptersSync`, which always returns `null`, and RxJava's `Single.fromCallable` rejects `null` with an NPE surfaced as "failed to load chapter list". Navigation now uses `ChapterService.getChapterList/getChapterContent` (with cache/network/stale-cache fallback), validates the chapter index map against the current list (falling back to a linear scan when stale), and resets the loading flag after info notifications so boundary notices no longer block further paging
- 修复设置面板中配置的字体/字号不在系统字体列表或预设字号列表时,面板一打开就误报「已修改」并在应用时静默替换用户字体的问题:非可编辑下拉框 `setSelectedItem` 找不到目标项会保留原选中项,现改为先确保当前配置的字体与字号在下拉项中存在,再加载设置 | Fixed the settings panel falsely reporting "modified" on open and silently replacing the configured font when the configured font family or size was missing from the system font list or preset size list: a non-editable `JComboBox#setSelectedItem` keeps the previous selection when the item is absent, so the panel now inserts the configured font/size before loading settings
- 修复首次添加书籍时误报「网络和所有缓存都获取章节列表失败」:原「缓存优先」流程在缓存必然为空的新书场景下仍调用 `LOG.error`,IDE 随即弹出错误通知并打印异常堆栈,极易被误认为添加失败(实际随后网络请求成功)。现缓存为空改记 debug 并显式进入网络分支,网络失败才包装为 `PrivateReaderException(NETWORK_ERROR)` 并保留书籍标题与原始 cause;后台刷新订阅补上错误处理 | Fixed a false "network and all caches failed to load chapter list" error right after adding a book: the cache-first flow still called `LOG.error` in the guaranteed cache-miss case of a new book, popping an IDE error notification with a stack trace that looked like an add failure (the following network request actually succeeded). A cache miss is now logged at debug and explicitly falls through to the network branch; only genuine network failures are wrapped as `PrivateReaderException(NETWORK_ERROR)` with the book title and original cause, and the background refresh subscription now handles errors
- 修复删除当前阅读书籍后通知栏模式仍可上下翻页:通知栏阅读会话(书籍/章节/分页/页码)原本从不清理,删除书籍后 `isReadingActive()` 仍为 true,翻页继续作用于旧章节的残留分页。现新增书籍删除事件并在 `BookServiceImpl.removeBook` 成功后发布,`NotificationService` 新增 `clearReadingState()` 重置会话与分页缓存,通知栏模式在被删书籍正是当前阅读书籍时退出模式,删除其他书籍不受影响 | Fixed notification-bar mode still turning pages after the current book was deleted: the notification reading session (book/chapter/pages/page index) was never cleared, so `isReadingActive()` stayed true and paging kept walking the stale chapter pages. Added a book-removed event published after a successful `BookServiceImpl.removeBook`, a new `NotificationService.clearReadingState()` that resets the session and pagination cache, and notification-bar mode now exits when the removed book is the one being read, while removing other books is unaffected

### 质量与维护 | Quality and Maintenance
- 新增 `NotificationBarModeServiceTest` 3 个(删除当前书籍退出模式/删除其他书籍不退出/无 ID 书籍忽略)、`NotificationServiceImplTest` 1 个(`clearReadingState` 清空后旧分页不可翻动)与 `ReaderConfigurableTest` 2 个(字体/字号不在列表时仍能正确选中),共 684 测试全通过 | Added 3 `NotificationBarModeServiceTest` cases (removing the current book exits the mode / removing another book keeps it / books without an id are ignored), 1 `NotificationServiceImplTest` case (`clearReadingState` makes the old pages unturnable) and 2 `ReaderConfigurableTest` cases (configured font/size still selectable when absent from the lists), 684 tests passing
- 消除 3 个环境/时序相关的不稳定用例：`NotificationServiceImplTest` 的末页跳章用例改为注入 mock parser 并轮询等待异步导航结果（不再依赖 `example.com` 真实网络与 `timeout()` 调度时序）；`ReaderViewModelTest`/`ReaderViewModelIntentTest` 中仅由后台进度保存消费的 `saveReadingProgress`/`getBookById` 桩改为 `lenient`，避免测试先结束时误报 `UnnecessaryStubbingException` | Stabilized 3 environment/timing-dependent tests: the last-page chapter-navigation case in `NotificationServiceImplTest` now injects a mock parser and polls for the asynchronous navigation result (no longer depending on real network access to `example.com` or the `timeout()` scheduler timing), while the `saveReadingProgress`/`getBookById` stubs in `ReaderViewModelTest`/`ReaderViewModelIntentTest` that are only consumed by the background progress save are now `lenient` to avoid a spurious `UnnecessaryStubbingException` when the test finishes first

## [3.1.1] - 2026-10-10

### 重构 | Refactor
- 拆分 `NotificationServiceImpl` 中的章节变更事件处理为独立 `ChapterEventProcessor`,通过窄接口 `Host` 解耦(该服务 1186→1096 行),无行为变更 | Extracted chapter-change event handling from `NotificationServiceImpl` into a standalone `ChapterEventProcessor` decoupled via a narrow `Host` interface (service shrunk from 1186 to 1096 lines), with no behavior change
- 继续拆分章节导航流水线为独立 `ChapterNavigator`(上/下章、跳转末页、缓存/异步双数据源收敛),该服务 1096→923 行,无行为变更 | Extracted the chapter navigation pipeline into a standalone `ChapterNavigator` (prev/next chapter, jump-to-last-page, cached/async source convergence), shrinking the service from 1096 to 923 lines, with no behavior change
- 删除无调用方的 Reactive 兼容路径 `NotificationService.showChapterContent(Book, chapterId, content)` 及其失效 import,该服务 923→788 行,无行为变更 | Removed the unused reactive compatibility path `NotificationService.showChapterContent(Book, chapterId, content)` and its stale imports, shrinking the service from 923 to 788 lines, with no behavior change
- 拆分通知栏阅读进度保存/恢复为独立 `NotificationProgressManager`(窄接口 Host 解耦),该服务 788→741 行,无行为变更 | Extracted notification-bar reading-progress save/restore into a standalone `NotificationProgressManager` (decoupled via a narrow `Host` interface), shrinking the service from 788 to 741 lines, with no behavior change
- 拆分通知栏章节内容展示流水线为独立 `ChapterContentViewer`(窄接口 Host 解耦),该服务 741→694 行,无行为变更 | Extracted the notification-bar chapter-content display pipeline into a standalone `ChapterContentViewer` (decoupled via a narrow `Host` interface), shrinking the service from 741 to 694 lines, with no behavior change
- 可测性改造 `NetworkPerformanceMonitor`:构造器改为可注入 `ScheduledExecutorService`(生产默认构造器委托 `AppExecutorUtil`,行为不变),`null` 调度器不注册周期任务,周期参数抽为具名常量,报告任务体放宽为包级可见 | Made `NetworkPerformanceMonitor` testable: the constructor now accepts an injectable `ScheduledExecutorService` (the production default delegates to `AppExecutorUtil`, behavior unchanged), a `null` scheduler skips periodic registration, period values became named constants, and the report task body is now package-visible
- 可测性改造 `DatabaseManager`:新增包级注入 `dbUrl` 构造器,初始化护栏由静态改为实例字段,目录由 URL 推导,迁移核心逻辑外提为包级重载(生产公开入口行为不变);`DatabaseConstants` 新增 `pathFromUrl` 纯函数 | Made `DatabaseManager` testable: added a package-level constructor accepting an injected `dbUrl`, changed the initialization guard from static to an instance field, derived the directory from the URL, and extracted the migration core into a package-level overload (the public entry point is unchanged); added the pure function `DatabaseConstants.pathFromUrl`

### 质量与维护 | Quality and Maintenance
- 新增 `ChapterEventProcessorTest` 4 个、`ChapterNavigatorTest` 8 个、`NotificationProgressManagerTest` 7 个、`ChapterContentViewerTest` 3 个回归测试(349 测试全通过) | Added 4 `ChapterEventProcessorTest`, 8 `ChapterNavigatorTest`, 7 `NotificationProgressManagerTest`, and 3 `ChapterContentViewerTest` regression tests (349 tests passing)
- 新增 `NetworkPerformanceMonitorTest` 11 个(记录/报告/错误聚合/生命周期,该类覆盖 0.8%→93.0%)、`ReactiveChapterCacheRepositoryImplCrudTest` 12 个(缓存读写/过期/容量清理/全量与按书清理,该类覆盖 30.7%→76.5%),共 372 测试全通过 | Added 11 `NetworkPerformanceMonitorTest` cases (recording/reporting/error aggregation/lifecycle; class coverage 0.8%→93.0%) and 12 `ReactiveChapterCacheRepositoryImplCrudTest` cases (cache read/write, expiry, size-based eviction, full and per-book cleanup; class coverage 30.7%→76.5%), 372 tests passing
- JaCoCo LINE 覆盖率 42.44%→45.88%,回归护栏阈值 0.35→0.40 | JaCoCo LINE coverage 42.44%→45.88%, regression gate raised from 0.35 to 0.40
- 新增 `DatabaseManagerCoreTest` 8 个(注入 URL 建库/连接/迁移)、`DatabaseConstantsTest` 2 个、`NetworkUtilsHttpTest` 7 个(本地 `HttpServer` 覆盖 2xx/3xx/4xx/5xx 与 `checkNetworkSpeed`),共 389 测试全通过 | Added 8 `DatabaseManagerCoreTest` cases (injected-URL init/connection/migration), 2 `DatabaseConstantsTest` cases, and 7 `NetworkUtilsHttpTest` cases (local `HttpServer` covering 2xx/3xx/4xx/5xx and `checkNetworkSpeed`), 389 tests passing
- JaCoCo LINE 覆盖率 45.88%→46.84%,回归护栏阈值 0.40→0.42 | JaCoCo LINE coverage 45.88%→46.84%, regression gate raised from 0.40 to 0.42
- 将 `ExceptionHandler` 的类型推断/标题/文案纯函数与 `ConfigDiagnosticTool` 的目录诊断放宽为可注入的包级入口(行为不变) | Relaxed `ExceptionHandler`'s type-inference/title/message pure functions and `ConfigDiagnosticTool`'s directory diagnostics into injectable package-level entry points (behavior unchanged)
- 新增 `PrivateReaderConfigTest` 3 个、`StorageManagerTest` 10 个、`ExceptionHandlerTest` 12 个、`ConfigDiagnosticToolTest` 6 个、`ReaderModeSwitcherTest` 7 个,共 428 测试全通过 | Added 3 `PrivateReaderConfigTest`, 10 `StorageManagerTest`, 12 `ExceptionHandlerTest`, 6 `ConfigDiagnosticToolTest`, and 7 `ReaderModeSwitcherTest` cases, 428 tests passing
- JaCoCo LINE 覆盖率 46.84%→49.34%,回归护栏阈值 0.42→0.45 | JaCoCo LINE coverage 46.84%→49.34%, regression gate raised from 0.42 to 0.45
- 可测性改造 `StorageManager`:新增包级注入 `basePath` 构造器(默认构造器委托 `user.home`,行为不变) | Made `StorageManager` testable: added a package-level constructor accepting an injected `basePath` (the default constructor delegates to `user.home`, behavior unchanged)
- 新增 `StorageManagerPathTest` 6 个(目录结构/路径 getter/书籍目录按需创建)与 `FileBookRepositoryRepairTest` 9 个(阅读位置恢复/修复/详情加载),共 443 测试全通过 | Added 6 `StorageManagerPathTest` cases (directory layout/path getters/on-demand book-directory creation) and 9 `FileBookRepositoryRepairTest` cases (reading-position restore/repair/detail loading), 443 tests passing
- JaCoCo LINE 覆盖率 49.34%→50.29%(突破 50%),回归护栏阈值 0.45→0.46 | JaCoCo LINE coverage 49.34%→50.29% (crossed 50%), regression gate raised from 0.45 to 0.46
- 新增 `NotificationDisplayManagerDisplayTest` 6 个(展示/加载/错误/信息通知与按钮装配,该类覆盖 32.8%→100%)与 `BookServiceImplMutationTest` 9 个(增删改/进度保存/异常透传,该类覆盖 40.7%→70.9%),共 458 测试全通过 | Added 6 `NotificationDisplayManagerDisplayTest` cases (reading/loading/error/info notifications and button wiring; class coverage 32.8%→100%) and 9 `BookServiceImplMutationTest` cases (mutation/progress-save/exception propagation; class coverage 40.7%→70.9%), 458 tests passing
- JaCoCo LINE 覆盖率 50.29%→51.11%,回归护栏阈值 0.46→0.47 | JaCoCo LINE coverage 50.29%→51.11%, regression gate raised from 0.46 to 0.47
- 新增 `ReaderViewModelIntentTest` 4 个(SelectBook/SearchBook 意图,该类覆盖 63.5%→68.4%)、`BookIndexStoreTest` 6 个与 `BookDetailsIoTest` 6 个(索引/详情文件 IO 直测),共 474 测试全通过 | Added 4 `ReaderViewModelIntentTest` cases (SelectBook/SearchBook intents; class coverage 63.5%→68.4%), plus 6 `BookIndexStoreTest` and 6 `BookDetailsIoTest` cases (direct index/details file IO), 474 tests passing
- JaCoCo LINE 覆盖率 51.11%→51.38%,回归护栏阈值 0.47→0.48 | JaCoCo LINE coverage 51.11%→51.38%, regression gate raised from 0.47 to 0.48
- 可测性改造:`ConfigDiagnosticTool` 的 `ApplicationManager` 服务查询抽为可注入的 `DiagnosticsHosts.Host`(生产实现 `ApplicationManagerHost`),该类覆盖率 22.3%→74.0%;新增 `ConfigDiagnosticToolHostTest` 11 个与 `ApplicationManagerHostTest` 11 个 | Testability refactor: extracted `ConfigDiagnosticTool`'s `ApplicationManager` service lookups into injectable `DiagnosticsHosts.Host` (production impl `ApplicationManagerHost`), lifting that class's coverage 22.3%→74.0%; added 11 `ConfigDiagnosticToolHostTest` and 11 `ApplicationManagerHostTest` cases
- 修复:接线此前声明但未处理的 `IReaderIntent.NavigateChapter` 意图(上一章/下一章),`ReaderViewModelIntentTest` 补 4 个用例 | Fixed: wired up the previously declared-but-unhandled `IReaderIntent.NavigateChapter` intent (previous/next chapter); added 4 `ReaderViewModelIntentTest` cases
- JaCoCo LINE 覆盖率 51.38%→53.47%,回归护栏阈值 0.48→0.50(501 测试全通过) | JaCoCo LINE coverage 51.38%→53.47%, regression gate raised from 0.48 to 0.50 (501 tests passing)
- 可测性改造:`FileStorageRepository` 新增包级 `(Path)` 构造器(默认构造器委托),该 0% 覆盖类提升至 81.2%;`ExceptionHandler` 新增 `RecoveryHost` 抽象与注入式恢复重载,24.4%→47.9% | Testability refactor: added a package-level `(Path)` constructor to `FileStorageRepository` (default constructor delegates), lifting that 0%-coverage class to 81.2%; introduced the `RecoveryHost` abstraction and injectable recovery overload in `ExceptionHandler`, 24.4%→47.9%
- 新增 `FileStorageRepositoryTest` 13 个、`ExceptionHandlerRecoveryTest` 10 个、`NetworkUtilsProbeTest` 11 个,并补测 `SafeHttpRequestExecutor` 性能统计 API 4 个 | Added 13 `FileStorageRepositoryTest`, 10 `ExceptionHandlerRecoveryTest` and 11 `NetworkUtilsProbeTest` cases, plus 4 cases for the `SafeHttpRequestExecutor` performance-stats API
- JaCoCo LINE 覆盖率 53.47%→55.23%,回归护栏阈值 0.50→0.52(541 测试全通过) | JaCoCo LINE coverage 53.47%→55.23%, regression gate raised from 0.50 to 0.52 (541 tests passing)
- 突破 UI 类零覆盖:确认 headless JUnit 环境下 Swing 与 IntelliJ UI 组件可实例化,由此补测三个设置面板 —— `NotificationReaderConfigurable` 0%→100%、`ReaderConfigurable` 0%→89.4%、`CacheConfigurable` 0%→54.3%,共新增 29 个测试 | Broke the zero-coverage barrier for UI classes: verified that Swing and IntelliJ UI components are instantiable in a headless JUnit environment, then covered three settings panels — `NotificationReaderConfigurable` 0%→100%, `ReaderConfigurable` 0%→89.4%, `CacheConfigurable` 0%→54.3%, adding 29 tests
- JaCoCo LINE 覆盖率 55.23%→59.53%,回归护栏阈值 0.52→0.56(571 测试全通过) | JaCoCo LINE coverage 55.23%→59.53%, regression gate raised from 0.52 to 0.56 (571 tests passing)
- 把 V29 的「UI 可测」结论推进到 `ui/actions`:确认 `AnActionEvent`/`Presentation` 可 mock,新增 `ActionsTest` 47 个测试覆盖 18 个动作类的 `update()` 启停契约与 `actionPerformed()` 委派/早退分支 | Extended V29's "UI is testable" finding to `ui/actions`: verified `AnActionEvent`/`Presentation` are mockable, adding 47 `ActionsTest` cases covering the `update()` enablement contract and `actionPerformed()` delegation/early-exit branches of 18 action classes
- 扩展 `NotificationServiceImplTest`(8→20)覆盖活动阅读会话状态机,`NotificationServiceImpl` 42.6%→74.1%；期间定位并修复测试基建问题:`ModalityState.defaultModalityState()` 在 mock Application 下返回 null 会令 UI 回调静默不执行 | Extended `NotificationServiceImplTest` (8→20) to cover the active reading-session state machine, lifting `NotificationServiceImpl` 42.6%→74.1%; this surfaced and fixed a test-harness issue where `ModalityState.defaultModalityState()` returning null under a mocked Application silently suppressed UI callbacks
- JaCoCo LINE 覆盖率 59.53%→63.70%,回归护栏阈值 0.56→0.60(630 测试全通过) | JaCoCo LINE coverage 59.53%→63.70%, regression gate raised from 0.56 to 0.60 (630 tests passing)
- `StorageManager` 新增包级 `(Path base, Path legacy)` 构造器注入旧版本目录(既有 `(Path)` 与默认构造器均委托之),使原先内联 `PathManager.getConfigPath()` 而不可测的数据迁移全流程可在 `@TempDir` 下验证;该类覆盖率 44.2%→85.2% | Added a package-level `(Path base, Path legacy)` constructor to `StorageManager` injecting the legacy directory (both the existing `(Path)` and default constructors delegate to it), making the previously untestable data-migration flow (which inlined `PathManager.getConfigPath()`) verifiable under `@TempDir`; class coverage 44.2%→85.2%
- 补测 `PluginConfigurable`(0%→94.1%)与 `RepositoryModule`(3.0%→90.9%,含「惰性服务解析只查询一次」契约) | Added coverage for `PluginConfigurable` (0%→94.1%) and `RepositoryModule` (3.0%→90.9%, including the "lazy service resolution queries only once" contract)
- JaCoCo LINE 覆盖率 63.70%→65.18%,回归护栏阈值 0.60→0.61(655 测试全通过) | JaCoCo LINE coverage 63.70%→65.18%, regression gate raised from 0.60 to 0.61 (655 tests passing)
- 补测三大编排类的未尽分支:`ReaderModeSwitcher` 56.8%→82.0%(将 mock `Application.invokeLater` 改为同步执行后解开工具窗口隐藏/显示路径)、`NotificationBarModeService` 74.9%→79.8%(页码夹紧/无项目守卫/状态清理)、`ChapterNavigator` 77.7%→86.0%(异步数据源末页导航) | Extended coverage of three orchestration classes: `ReaderModeSwitcher` 56.8%→82.0% (making mock `Application.invokeLater` run synchronously unlocked the tool-window hide/show paths), `NotificationBarModeService` 74.9%→79.8% (page clamping / no-project guards / state reset), `ChapterNavigator` 77.7%→86.0% (async last-page navigation)
- 新增 `FileBookRepositoryChapterFetchTest` 覆盖 details.json 缺章时经 `ChapterService` 重抓并回写文件的自愈路径(类覆盖 64.3%→70.0%) | Added `FileBookRepositoryChapterFetchTest` covering the self-healing path that re-fetches chapters via `ChapterService` and writes them back when details.json lacks them (class coverage 64.3%→70.0%)
- JaCoCo LINE 覆盖率 65.18%→66.04%,回归护栏阈值 0.61→0.62(678 测试全通过) | JaCoCo LINE coverage 65.18%→66.04%, regression gate raised from 0.61 to 0.62 (678 tests passing)

## [3.1.0] - 2026-10-10

### 修复 | Fixes
- 修复切换章节时页码未重置、章节名未写入数据库的问题:进度保存一度按具体实现类 `SqliteReadingProgressRepository` 从容器查询(插件仅按接口注册,查询为 null)而回退到会丢弃页码的 `BookService` 重载,导致新章节仍写入旧页码;阅读面板滚动防抖保存又固定传入空标题,覆盖了 `last_read_chapter_title`。现改为按 `ReadingProgressRepository` 接口保存带页码进度、滚动保存解析并带上真实章节标题,且切换章节时同步重置内存页码 | Fixed page number not resetting on chapter switch and chapter title not persisted: progress saving looked up the concrete `SqliteReadingProgressRepository` (registered only by interface, so the lookup returned null) and fell back to a `BookService` overload that discards the page, leaving the old page on the new chapter; the reader panel's debounced scroll save also passed an empty title, overwriting `last_read_chapter_title`. Saving now goes through the `ReadingProgressRepository` interface with an explicit page, the scroll save resolves and passes the real chapter title, and switching chapters resets the in-memory page index
- 修复部分站点目录为倒序导致阅读器从末章开始、上一章/下一章颠倒的问题:解析后依据章号(阿拉伯/中文数字)自动规范为正序,历史缓存写入时同样归一 | Fixed reversed chapter catalogs on some sites causing the reader to start from the last chapter and prev/next to be inverted: catalogs are now normalized to ascending order by chapter number (Arabic/Chinese numerals), including when written to cache
- 修复缓存设置不生效导致过期章节未按时清除的问题:设置界面的「缓存过期时间/最大缓存大小」与仓储实际读取的字段原本彼此独立,现已统一为同一份配置 | Fixed cache settings not taking effect, which left expired chapters uncleared: the UI's "cache expiry/max cache size" and the fields read by the repository were independent, now unified into a single configuration
- 修复启动时清理过期缓存设置(`cleanupOnStartup`)从未被读取的问题:现按设置在启动阶段立即清理一次,不再只依赖 6 小时定时任务 | Fixed the startup cache-cleanup setting (`cleanupOnStartup`) never being read: now an immediate cleanup runs at startup per the setting, instead of relying solely on the 6-hour timer
- 修复部分站点(如 biquge.one)编码乱码与目录不完整的问题 | Fixed garbled encoding and incomplete catalogs on some sites (e.g. biquge.one)
- 修复书架为空时未清空目录列表与章节内容、获取上次阅读书籍报错的问题 | Fixed empty bookshelf not clearing the chapter list/content and errors when fetching the last read book

### 新增 | Added
- 阅读进度表新增书名与章节名记录,阅读时间改用可读的字符串 | The reading progress table now records book and chapter titles, and reading time is stored as a readable string

## [3.0.0] - 2026-08-14

### 性能优化 | Performance Improvements
- 优化翻页性能,章节分页结果缓存,翻页更流畅 | Optimized page-turning performance with chapter pagination caching
- 重构响应式阅读流程,消除界面卡顿 | Refactored the reactive reading pipeline for a smoother UI

### 稳定性 | Stability
- 增强异常恢复能力,存储/解析异常时自动重建缓存 | Enhanced error recovery to automatically rebuild cache on storage/parse errors
- 优化数据库连接与线程池管理,提升插件稳定性 | Improved database connection and thread pool management for stability

## [2.5.1] - 2026-07-16

### 新增 | Added
- 添加网络性能监控器，统一跟踪网络请求耗时和执行状态 | Added a network performance monitor to track request timing and execution status

### 修复 | Fixes
- 修复无最近阅读记录时初始化失败的问题 | Fixed initialization failures when no recent reading record exists
- 修复安全 HTTP 请求日志中的线程 ID 获取方式 | Fixed thread ID retrieval in safe HTTP request logs

## [2.5.0] - 2026-05-27

### 架构优化 | Architecture Improvements
- 统一响应式阅读链路，清理旧的同步实现和重复任务管理逻辑 | Unified the reactive reading pipeline and removed legacy synchronous implementations and duplicate task management logic
- 简化服务注册与依赖结构，移除 Guice、ServiceLocator、旧存储适配器、旧缓存管理器和未使用的诊断/来源模块 | Simplified service registration and dependency structure by removing Guice, ServiceLocator, legacy storage adapters, legacy cache managers, and unused diagnostic/source modules
- 优化仓储与缓存实现，统一书籍、章节缓存和阅读进度的数据访问路径 | Improved repository and cache implementations with unified access paths for books, chapter cache, and reading progress

### 修复 | Fixes
- 修复通知栏模式销毁阶段初始化新服务导致的插件责任异常 | Fixed plugin-blame errors caused by service initialization during notification mode disposal
- 修复书架删除、通知栏触发、阅读面板切书同步和章节变更事件处理问题 | Fixed bookshelf deletion, notification mode triggering, reader panel book switching synchronization, and chapter change event handling issues
- 修复上一页/下一页操作在不同阅读状态下的行为一致性 | Fixed previous/next page action consistency across reader states

### 质量与维护 | Quality and Maintenance
- 清理大量无用导入和冗余代码，降低维护成本 | Cleaned unused imports and redundant code to reduce maintenance overhead
- 补充书籍仓储、章节事件、阅读 ViewModel 和书籍服务相关回归测试 | Added regression tests for book repository, chapter events, reader ViewModel, and book service
- 更新优化方案文档、构建配置和插件变更说明 | Updated optimization documents, build configuration, and plugin change notes

## [2.4.1] - 2026-01-23

### 功能优化 | Feature Improvements
- 修复已知问题 | Fixed known issues
- 性能优化 | Performance optimization

## [2.4.0] - 2025-12-26

### 架构升级 | Architecture Upgrade
- 重构 UI 架构为 MVI 模式 | Refactored UI architecture to MVI pattern
- 增强响应式数据处理 | Enhanced reactive data handling
- 优化阅读器性能 | Optimized reader performance

## [2.3.0] - 2025-11-27

### 功能优化 | Feature Improvements
- 优化了核心性能 | Optimized core performance
- 改进了UI响应 | Improved UI responsiveness
- 修复了一些已知问题 | Fixed several known issues

## [2.2.0] - 2025-07-30

### 功能优化 | Feature Improvements
- 优化大文件加载性能 | Optimized large file loading performance
- 调整缓存策略 | Adjusted caching strategy
- 修复已知问题 | Fixed known issues

## [2.1.0] - 2025-05-27

### 功能优化 | Feature Improvements
- 优化解析引擎性能 | Optimized parsing engine performance
- 改进缓存管理机制 | Improved cache management mechanism
- 增强用户界面响应速度 | Enhanced UI response speed
- 修复已知问题 | Fixed known issues

## [2.0.0] - 2025-05-09

### 重大版本升级 | Major Version Upgrade
- 全面重构核心解析引擎 | Complete refactoring of core parsing engine
- 优化内存使用和性能 | Optimized memory usage and performance
- 改进用户界面体验 | Improved user interface experience
- 增强缓存管理系统 | Enhanced cache management system

## [1.1.0] - 2025-03-05

### 功能优化 | Feature Improvements
- 优化阅读器界面 | Enhanced reader interface
- 提升章节加载速度 | Improved chapter loading speed
- 改进缓存机制 | Enhanced caching mechanism

## [1.0.0] - 2025-02-27

### 新增功能 | New Features
- 基础功能实现 | Basic Features Implementation
  - 支持添加、移除书籍 | Add and remove books
  - 支持阅读进度保存和同步 | Reading progress save and sync
  - 支持章节内容缓存和预加载 | Chapter content cache and preload
  - 支持通用网站智能解析 | Universal website smart parsing
  - 支持阅读器模式和通知栏模式切换 | Switch between reader mode and notification bar mode
  - 支持章节内容刷新（忽略缓存）| Refresh chapter content (ignore cache)
  - 支持书籍列表排序（按书名、进度、时间）| Book list sorting (by name, progress, time)
  - 支持阅读进度实时更新 | Real-time reading progress update
  - 支持章节列表缓存 | Chapter list caching

### 界面优化 | UI Optimization
- UI 优化 | UI Improvements
  - 优雅的阅读界面 | Elegant reading interface
  - 合理的菜单布局 | Reasonable menu layout
  - 快捷键支持 | Keyboard shortcuts support
  - 清屏、翻页、刷新等快捷键 | Shortcuts for clear screen, page turning, refresh
  - 可折叠的侧边栏 | Collapsible sidebar
  - 自定义字体和大小 | Custom font and size
  - 阅读进度实时显示 | Real-time progress display
  - 通知栏阅读模式优化 | Notification bar reading mode optimization

### 存储优化 | Storage Optimization
- 分离存储方案 | Separated Storage Solution
  - 主索引文件：存储所有书籍的基本信息 | Main index file: stores basic information of all books
  - 书籍详情文件：每本书单独存储详细信息 | Book detail files: separate storage for each book's details
  - 章节缓存：分散存储减少内存占用 | Chapter cache: distributed storage to reduce memory usage
  - LRU 缓存策略 | LRU cache strategy
  - 定期清理过期缓存 | Regular expired cache cleanup

### 性能优化 | Performance Optimization
- 内存缓存优化 | Memory Cache Optimization
  - 智能预加载机制 | Smart preloading mechanism
  - 缓存大小限制 | Cache size limitation
  - 过期缓存自动清理 | Automatic expired cache cleanup
  - 磁盘空间管理 | Disk space management
- 文件系统优化 | File System Optimization
  - 安全的文件名处理 | Safe filename handling
  - 分散存储减少锁竞争 | Distributed storage to reduce lock contention
  - 异步写入和预加载 | Asynchronous writing and preloading
- 后台任务处理 | Background Task Processing
  - 章节预加载 | Chapter preloading
  - 缓存清理 | Cache cleanup
  - 存储同步 | Storage synchronization

### 安全性 | Security
- 文件名安全处理 | Filename Security Handling
  - Unicode 规范化 | Unicode normalization
  - 非法字符过滤 | Illegal character filtering
  - 长度限制处理 | Length limitation handling
  - Hash 处理保持可读性 | Hash processing while maintaining readability
- 缓存目录权限控制 | Cache directory permission control
- 异常处理和日志记录 | Exception Handling and Logging
  - 详细的错误日志 | Detailed error logs
  - 优雅的降级处理 | Graceful degradation
  - 备用方案支持 | Fallback solution support