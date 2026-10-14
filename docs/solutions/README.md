# Private Reader 优化方案 · 总索引

> 维护说明:本文档是优化工作的**单一事实源**(当前状态、指标、遗留事项、踩坑记录)。
> 已完成轮次的详细记录归档于 [`archive/`](./archive/),后续新增优化轮次请:
> 1. 新建 `OPTIMIZATION_V{n}.md`(沿用既有格式);
> 2. 完成后更新本文档的「时间线」「当前状态」「指标演进」三个章节;
> 3. 若轮次确已封闭(无新读者需求),按 [归档流程](#归档流程) 移入 archive。

---

## 一、目录结构

```
docs/solutions/
├── README.md              ← 本文件:总索引 / 单一事实源
├── OPTIMIZATION_V23.md    ← 最新优化轮次(V24 起顺延)
├── OPTIMIZATION_V22.md
├── OPTIMIZATION_V21.md
├── OPTIMIZATION_V20.md
├── OPTIMIZATION_V19.md
├── OPTIMIZATION_V18.md
├── OPTIMIZATION_V17.md
├── OPTIMIZATION_V16.md
├── OPTIMIZATION_V15.md
├── OPTIMIZATION_V14.md
├── archive/               ← 已完成轮次的详细记录(12 份)
└── legacy/                ← 已废弃的 Reactor 时代文档(仅存档)
```

## 二、时间线

| 轮次 | 日期 | 版本 | 核心成果 | 测试数 | LINE 覆盖率 |
|------|------|------|----------|--------|-------------|
| 初版 | 2026-05-20 | 2.5.0 | 10 阶段优化:统一缓存、线程安全、移除 Guice/ServiceLocator/.block()、测试基建 | 1 | — |
| 计划 | 2026-05-22 | 2.5.0 | P0-P2 优化计划(其中 P0-1~P0-3、P1-1/2/4、P2-1/2 完成;P1-3/5、P2-3/4 跳过) | — | — |
| V3 | 2026-08-10 | 2.5.1 | 缓存路径统一、ReactiveTaskManager 收敛、ReaderViewModel 线程安全、HTTP 路径统一、日志降级、DB 连接复用、死代码清理 | 20 | — |
| V4 | 2026-08-10 | 2.5.1 | 消除 UI 线程阻塞(BookshelfDialog/ChapterListDialog)、删除 Deprecated 代码、线程池生命周期、补充核心逻辑测试(20→48) | 48 | — |
| V5 | 2026-08-11 | 2.5.1 | 核心大文件(UniversalParser/仓储/ChapterService)补测、TODO 空壳修复、网络重试测试(48→81) | 81 | — |
| V6 | 2026-08-11 | 2.5.1 | 导航方法 4→2 合并、分页结果缓存、仓储核心测试(81→99) | 99 | — |
| V7 | 2026-08-12 | 2.5.1 | 全链路冒烟验证(构建/GUI/二进制兼容/弃用 API 清理) | 99 | — |
| V8 | 2026-08-13 | 2.5.1 | JaCoCo 覆盖率集成(首次 LINE 18.24%)+ GitHub Actions CI | 99 | 18.24% |
| V9 | 2026-08-13 | 2.5.1 | 零覆盖包补测:settings/model/parser 等 16 个测试类(99→209),阈值 0.15→0.27 | 209 | 28.02% |
| V10 | 2026-08-13 | 2.5.1 | 预加载器/接口 default 方法/存储读写补测(209→236) | 236 | 31.02% |
| V11 | 2026-09-18 | 3.0.0 | 通知展示拆分→NotificationDisplayManager、多 volatile 字段→ReaderViewState、正则预编译(236→248) | 248 | 31.49% |
| V12 | 2026-09-18 | 3.0.0 | 8 个核心服务类构造器注入改造,移除 `ensureServicesInitialized` 惰性机制 | 249 | 31.48% |
| **V13** | **2026-09-20** | **3.0.0** | **导航流水线去重、NetworkUtils 并行探测+30s TTL、FileBookRepository 1295→762 行职责拆分、service 层补测、getChapterTitle NPE 修复、阈值 0.27→0.30(249→270)** | **270** | **33.54%** |
| **V14** | **2026-09-20** | **3.0.0** | **P0/P1 落地:NotificationBarModeService 0%→74.79% 补测、NotificationServiceImpl 阅读仓库注入化收尾、NotificationDisplayManager 回调化+纯函数提取、ChapterListDialog 三处重复收敛+纯逻辑提取、RepositoryModule 评估保留(270→299)** | **299** | **36.35%** |
| **V15** | **2026-10-09** | **3.0.0** | **修复缓存过期/容量设置不生效与启动清理失效(字段去重)、仓储启动清理回归补测(299→311)** | **311** | **38.34%** |
| **V16** | **2026-10-10** | **3.1.0** | **拆分 `NotificationServiceImpl` 章节变更事件处理为 `ChapterEventProcessor`(窄接口 Host 解耦,代码 1186→1096 行)、新增 4 测试、阈值 0.30→0.35** | **331** | **39.95%** |
| **V17** | **2026-10-10** | **3.1.1** | **拆分导航流水线为 `ChapterNavigator`(上/下章、末页、双数据源收敛,1096→923 行)、新增 8 测试** | **339** | **41.08%** |
| **V18** | **2026-10-10** | **3.1.1** | **删除无调用方的 Reactive 兼容路径 `showChapterContent(Book,...)`(923→788 行)与 5 个失效 import** | **339** | **41.45%** |
| **V19** | **2026-10-10** | **3.1.1** | **拆分阅读进度保存/恢复为 `NotificationProgressManager`(788→741 行)、新增 7 测试** | **346** | **41.89%** |
| **V20** | **2026-10-10** | **3.1.1** | **拆分章节内容展示流水线为 `ChapterContentViewer`(741→694 行)、新增 3 测试** | **349** | **42.44%** |
| **V21** | **2026-10-10** | **3.1.1** | **`NetworkPerformanceMonitor` 可测性改造(注入调度器,0.8%→93%)、缓存仓储 CRUD 补测(30.7%→76.5%)、阈值 0.35→0.40** | **372** | **45.88%** |
| **V22** | **2026-10-10** | **3.1.1** | **`DatabaseManager` 注入式构造(25.4%→71.2%)、迁移逻辑外提补测、`NetworkUtils` 本地 HTTP 补测(28.4%→39.8%)、阈值 0.40→0.42** | **389** | **46.84%** |
| **V23** | **2026-10-10** | **3.1.1** | **零覆盖工具类补测:`PrivateReaderConfig` 0→74%、`ReaderModeSwitcher` 0→57%、`StorageManager`/`ExceptionHandler`/`ConfigDiagnosticTool` 均从 0 起步、阈值 0.42→0.45** | **428** | **49.34%** |

## 三、当前状态(单一事实源)

> 最后核对:2026-10-10(对照代码库核实,源码实际行数以代码为准)

### 3.1 已完成

- **架构**:移除 Guice、ServiceLocator、旧缓存管理器/存储适配器;统一 IntelliJ Service + 构造器注入
- **响应式**:统一 RxJava3(移除 Reactor);消除 UI 线程 `.block()` 与阻塞轮询/EDT 内 sleep
- **线程安全**:ConcurrentHashMap/CopyOnWriteArrayList、`toSerialized()`、ReaderViewState 不可变快照
- **文件仓储**:`FileBookRepository` 拆分出 `BookJsonCodec`/`BookIndexStore`/`BookDetailsIo`(1295→762 行)
- **注入收尾**:`NotificationServiceImpl` 阅读进度仓库四入构造器注入(消除按需 getService);`NotificationDisplayManager` 回调化+纯函数提取(展示层不再直接依赖容器)
- **事件处理拆分**:`NotificationServiceImpl` 章节变更事件处理下沉至 `ChapterEventProcessor`(窄接口 `Host` 解耦,1186→1096 行)
- **导航流水线拆分**:`NotificationServiceImpl` 章节导航下沉至 `ChapterNavigator`(上/下章、末页、双数据源收敛,1096→923 行)
- **死代码清理**:删除无调用方的 Reactive 兼容路径 `showChapterContent(Book,...)` 与 5 个失效 import(923→788 行)
- **进度逻辑拆分**:阅读进度保存/恢复下沉至 `NotificationProgressManager`(788→741 行)
- **展示流水线拆分**:章节内容展示下沉至 `ChapterContentViewer`(741→694 行)
- **可测性改造**:`NetworkPerformanceMonitor` 构造器改为可注入 `ScheduledExecutorService`,周期任务具名常量、任务体包级可见(生产行为不变)
- **数据路径补测**:缓存仓储读写/清理主干(CRUD)与监控器记录/报告/生命周期补测,LINE 42.44%→45.88%
- **存储可测性改造**:`DatabaseManager` 包级注入 dbUrl 构造器、初始化护栏静态→实例、迁移逻辑外提为包级重载;`DatabaseConstants.pathFromUrl` 纯函数,LINE 45.88%→46.84%
- **零覆盖工具类补测**:`PrivateReaderConfig`/`ReaderModeSwitcher`/`StorageManager`/`ExceptionHandler`/`ConfigDiagnosticTool` 从 0 起步,LINE 46.84%→49.34%
- **工程化**:JaCoCo 覆盖率护栏(当前 **0.45**,实测 49.34%)+ GitHub Actions CI(构建/测试/打包/`verifyPlugin`)

### 3.2 遗留事项(按文档轮次汇总)

| 事项 | 来源 | 优先级 |
|------|------|--------|
| `NotificationServiceImpl` 仍有 ~694 行,剩余主要为接口契约 API(分页/状态读取/通知展示)与 `dispose` 生命周期 | V11/V13/V14/V16/V17/V18/V19/V20 遗留 | 低 |
| `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,0 覆盖,需真实 IDE 或 UI 测试框架 | V9-V14 遗留 | 中 |
| `storage` 主体、`initialization` 为低覆盖保留方向 | V9/V10 遗留 | 低 |
| `NotificationDisplayManager.showReadingNotification` 真实通知创建路径未覆盖(需 mockStatic 门面或 GUI 冒烟) | V14 遗留 | 中 |
| 覆盖率实测 36.35%,阈值可上调至 0.33(余量 6.35pp) | V14 建议 | 低 |
| 覆盖率实测 38.34%,阈值可上调至 0.35(余量 3.34pp) | V15 建议 | 低 |
| 覆盖率实测 39.95%,阈值已于 V16 上调至 0.35(余量 4.95pp) | V16 完成 | — |
| 覆盖率实测 41.08%(V17),阈值 0.35 仍有 6.08pp 余量 | V17 | 低 |
| 覆盖率实测 41.45%(V18),阈值 0.35 仍有 6.45pp 余量 | V18 | 低 |
| 覆盖率实测 41.89%(V19),阈值 0.35 仍有 6.89pp 余量 | V19 | 低 |
| 覆盖率实测 42.44%(V20),阈值 0.35 仍有 7.44pp 余量 | V20 | 低 |
| `DatabaseManager` 25.4%:迁移/初始化依赖静态 flag 与 `PropertiesComponent`,需重构可注入后再补测 | V21 | 中 |
| `NetworkUtils` 28.4%:`getBestHost`/`isNetworkAvailableAsync` 需网络打桩框架 | V21 | 中 |
| 覆盖率实测 45.88%(V21),阈值已上调至 0.40(余量 5.88pp) | V21 完成 | — |
| `DatabaseConstants` 15.4%:`getDatabasePath`/`getDatabaseUrl` 仍依赖 IntelliJ 容器与 fallback 目录 | V22 | 低 |
| `NetworkUtils` 39.8%:`isNetworkAvailable` 并行探测与 `getBestHost` 依赖外网,需可替换探测适配器 | V22 | 中 |
| 覆盖率实测 46.84%(V22),阈值已上调至 0.42(余量 4.84pp) | V22 完成 | — |
| `ConfigDiagnosticTool` 22.3% / `ExceptionHandler` 24.4% / `StorageManager` 21.3%:剩余为容器服务查询与 `LOG.error` 分支 | V23 | 低 |
| 覆盖率实测 49.34%(V23),阈值已上调至 0.45(余量 4.34pp) | V23 完成 | — |
| `verifyPlugin` 本地需联网下载 IDE 263 平台;**CI 的 verify-plugin job 有外网正常执行** | V13 遗留 | 环境 |

### 3.3 覆盖率阈值历史

| 版本 | 阈值 | 触发轮次 |
|------|------|----------|
| 0.15 | 首次引入(基线 18.24%) | V8 |
| 0.27 | V9 补测后(28.02%) | V9 |
| 0.30 | V13 补测后(33.54%) | V13 |
| 0.35 | V16 拆分 `ChapterEventProcessor` 并补测后(39.95%) | V16 |
| 0.40 | V21 可测性改造 + 缓存仓储补测后(45.88%) | V21 |
| 0.42 | V22 存储/网络可测性改造补测后(46.84%) | V22 |
| 0.45 | V23 零覆盖工具类补测后(49.34%) | V23 |

> V15 实测 LINE 达 38.34%;V16 拆分事件处理并补测后达 39.95% 并将阈值上调至 0.35;
> V17 拆分导航流水线补测后达 41.08%;V18 删死代码后达 41.45%;V19 拆进度逻辑后达 41.89%;V20 拆内容展示后达 42.44%;
> V21 可测性改造监控器 + 缓存仓储 CRUD 补测后达 45.88%,阈值上调至 0.40;
> V22 存储/网络可测性改造并补测后达 46.84%,阈值上调至 0.42;
> V23 零覆盖工具类补测后达 49.34%,阈值上调至 0.45。

## 四、指标演进

| 指标 | V8 | V9 | V10 | V11 | V12 | V13 | V14 | V15 | V16 | V17 | V18 | V19 | V20 | V21 | V22 | V23 |
|------|----|----|----|----|----|----|----|----|----|----|----|----|----|----|----|----|
| LINE | 18.24% | 28.02% | 31.02% | 31.49% | 31.48% | 33.54% | **36.35%** | **38.34%** | **39.95%** | **41.08%** | **41.45%** | **41.89%** | **42.44%** | **45.88%** | **46.84%** | **49.34%** |
| 测试数 | 99 | 209 | 236 | 248 | 249 | 270 | **299** | **311** | **331** | **339** | **339** | **346** | **349** | **372** | **389** | **428** |
| 总行数 | 7581 | 7581 | 7581 | 7692 | 7692 | 7672 | 7653 | 7653 | 7912 | 7939 | 7867 | 7876 | 7902 | 7907 | 7919 | 7918 |

> 注:总行数为各轮报告的统计口径,代码持续变化,精确值以 JaCoCo 报告为准。

## 五、踩坑速查(长期复用价值)

1. **JaCoCo 在 IntelliJ 平台测试 0%**:测试经 `PathClassLoader` 加载无 location 的类,须 `test { jacoco { includeNoLocationClasses = true } }`(V8)。
2. **IntelliJ `Logger.error` 在测试环境抛 AssertionError**:测试运行时错误日志即失败,须避开 `LOG.error` 分支(V9)。
3. **BaseSettings 延迟加载覆盖 setter**:首次 getter 才加载,setter 前须先触发加载(V9)。
4. **Mockito mock 接口默认不执行 default 方法**:用 `withSettings().defaultAnswer(CALLS_REAL_METHODS)`(V10)。
5. **mockStatic 线程本地性**:作用域仅注册线程;`subscribeOn(io)` 会把 lambda 挪到 io 线程,须用 `RxJavaPlugins.setIoSchedulerHandler` 替换为 trampoline 或起本地 HttpServer 测真实网络(V5/V10)。
6. **反序列化 Settings 实例 getter 陷阱**:`loaded=false` 首次 getter 触发再加载,断言用反射读字段(V10)。
7. **lambda 引用非 final 变量编译失败**:先用 final 副本再进 lambda(V11)。
8. **SQLite 测试连接未关闭锁文件**:Windows 下 @TempDir 删不掉,`@AfterEach` 统一关闭连接(V5)。
9. **“双份设置字段”静默失效**:`CacheSettings` 同时存在 `cacheExpiryHours`/`maxCacheSizeMB` 与 `maxCacheAge`/`maxCacheSize` 两组字段,UI 写一组、仓储读另一组,设置看似保存成功却完全不影响行为。凡“设置项不生效”类缺陷,先核对写入方与读取方是否指向同一变量(V15)。
10. **定时清理首次触发过晚**:`Observable.interval(6, HOURS)` 首次触发在前,短会话永远等不到。启动型清理需在构造/启动阶段显式跑一次(V15)。
11. **构造函数副作用阻塞可测性**:`NetworkPerformanceMonitor` 构造器直接向 `AppExecutorUtil` 全局调度器注册 5 分钟周期任务,导致每次测试 new 都污染全局定时器、只能靠 dispose 清理。凡「构造即注册全局资源」的服务,应抽出可注入依赖(如 `ScheduledExecutorService`)的包级构造器,生产默认构造器再委托(V21)。
12. **静态初始化 flag 与全局容器耦合**:`DatabaseManager` 的 `databaseInitialized` 原为静态字段、dbUrl 与目录解析走 IntelliJ 容器,导致无法用不同数据库多次实例化。改为**实例级** flag + 包级注入 dbUrl 构造器 + 由 URL 推导目录后即可独立测试;迁移逻辑内联的 `ApplicationManager.getService`/`PropertiesComponent.getInstance` 也须外提为包级重载(V22)。

## 六、归档流程

已完成且无新读者需求的轮次,按以下步骤归档(保持 git 历史与相对链接可用):

```bash
cd docs/solutions
git mv OPTIMIZATION_V{n}.md archive/
# 修正 archive 内文档的「前序文档」相对链接:./OPTIMIZATION_* → ../OPTIMIZATION_*
sed -i 's|](\./OPTIMIZATION|](../OPTIMIZATION|g' archive/OPTIMIZATION_V{n}.md
# 更新 README 的时间线 / 当前状态 / 归档列表
```

## 七、文档清单

### 顶层(当前轮次)

| 文档 | 说明 |
|------|------|
| `OPTIMIZATION_V23.md` | 最新轮次:零覆盖工具类与设置聚合补测 + 阈值上调 0.45(V23) |
| `OPTIMIZATION_V22.md` | DatabaseManager/NetworkUtils 可测性改造 + 阈值上调 0.42(V22) |
| `OPTIMIZATION_V21.md` | 监控器可测性改造 + 缓存仓储 CRUD 补测 + 阈值上调 0.40(V21) |
| `OPTIMIZATION_V20.md` | 章节内容展示流水线拆分 `ChapterContentViewer`(V20) |
| `OPTIMIZATION_V19.md` | 阅读进度保存/恢复拆分 `NotificationProgressManager`(V19) |
| `OPTIMIZATION_V18.md` | 删除无调用方的 Reactive 兼容路径(V18) |
| `OPTIMIZATION_V17.md` | 导航流水线拆分 `ChapterNavigator`(V17) |
| `OPTIMIZATION_V16.md` | 章节变更事件拆分 `ChapterEventProcessor` / 阈值上调(V16) |
| `OPTIMIZATION_V15.md` | 缓存设置字段去重 / 启动清理修复 / 仓储回归补测(V15) |
| `OPTIMIZATION_V14.md` | 通知栏服务补测 / 注入收尾 / 展示层回调化 / 章节列表去重(V14) |
| `OPTIMIZATION_V13.md` | 导航去重 / 网络缓存 / 仓储拆分 / service 补测(V13) |

### archive(已完成轮次)

| 文档 | 说明 |
|------|------|
| `archive/OPTIMIZATION.md` | 2.5.0 初版 10 阶段优化方案与实施记录 |
| `archive/OPTIMIZATION_PLAN.md` | 2.5.0 P0-P2 优化计划 |
| `archive/OPTIMIZATION_V3.md` | 缓存路径 / HTTP 路径 / DB 连接 / 死代码 |
| `archive/OPTIMIZATION_V4.md` | UI 阻塞消除 / 线程池生命周期 / 补测 |
| `archive/OPTIMIZATION_V5.md` | 核心大文件补测 / TODO 修复 / 网络重试 |
| `archive/OPTIMIZATION_V6.md` | 导航合并 / 分页缓存 |
| `archive/OPTIMIZATION_V7.md` | 全链路冒烟验证 |
| `archive/OPTIMIZATION_V8.md` | JaCoCo + GitHub Actions CI |
| `archive/OPTIMIZATION_V9.md` | 零覆盖包补测(209 测试) |
| `archive/OPTIMIZATION_V10.md` | 预加载器 / default 方法 / 存储读写补测 |
| `archive/OPTIMIZATION_V11.md` | 通知服务拆分 / 不可变状态 / 正则预编译 |
| `archive/OPTIMIZATION_V12.md` | 构造器注入改造 |

### legacy(废弃)

| 文档 | 说明 |
|------|------|
| `legacy/COMPARISON.md` / `DEPENDENCY_GUIDE.md` / `REACTIVE_FAQ.md` | Reactor 时代响应式文档,git mv 移出源码目录(commit 2489117),仅存档 |