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
├── OPTIMIZATION_V18.md    ← 最新优化轮次(V19 起顺延)
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
- **工程化**:JaCoCo 覆盖率护栏(当前 **0.30**,实测 36.35%)+ GitHub Actions CI(构建/测试/打包/`verifyPlugin`)

### 3.2 遗留事项(按文档轮次汇总)

| 事项 | 来源 | 优先级 |
|------|------|--------|
| `NotificationServiceImpl` 仍有 ~788 行,可评估拆分 `dispose` 生命周期/进度保存恢复与 `showChapterContent(Project,...)` 展示路径 | V11/V13/V14/V16/V17/V18 遗留 | 低 |
| `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,0 覆盖,需真实 IDE 或 UI 测试框架 | V9-V14 遗留 | 中 |
| `storage` 主体、`initialization` 为低覆盖保留方向 | V9/V10 遗留 | 低 |
| `NotificationDisplayManager.showReadingNotification` 真实通知创建路径未覆盖(需 mockStatic 门面或 GUI 冒烟) | V14 遗留 | 中 |
| 覆盖率实测 36.35%,阈值可上调至 0.33(余量 6.35pp) | V14 建议 | 低 |
| 覆盖率实测 38.34%,阈值可上调至 0.35(余量 3.34pp) | V15 建议 | 低 |
| 覆盖率实测 39.95%,阈值已于 V16 上调至 0.35(余量 4.95pp) | V16 完成 | — |
| 覆盖率实测 41.08%(V17),阈值 0.35 仍有 6.08pp 余量 | V17 | 低 |
| 覆盖率实测 41.45%(V18),阈值 0.35 仍有 6.45pp 余量 | V18 | 低 |
| `verifyPlugin` 本地需联网下载 IDE 263 平台;**CI 的 verify-plugin job 有外网正常执行** | V13 遗留 | 环境 |

### 3.3 覆盖率阈值历史

| 版本 | 阈值 | 触发轮次 |
|------|------|----------|
| 0.15 | 首次引入(基线 18.24%) | V8 |
| 0.27 | V9 补测后(28.02%) | V9 |
| 0.30 | V13 补测后(33.54%) | V13 |
| 0.35 | V16 拆分 `ChapterEventProcessor` 并补测后(39.95%) | V16 |

> V15 实测 LINE 达 38.34%;V16 拆分事件处理并补测后达 39.95% 并将阈值上调至 0.35;
> V17 拆分导航流水线补测后达 41.08%;V18 删死代码后达 41.45%。

## 四、指标演进

| 指标 | V8 | V9 | V10 | V11 | V12 | V13 | V14 | V15 | V16 | V17 | V18 |
|------|----|----|----|----|----|----|----|----|----|----|----|
| LINE | 18.24% | 28.02% | 31.02% | 31.49% | 31.48% | 33.54% | **36.35%** | **38.34%** | **39.95%** | **41.08%** | **41.45%** |
| 测试数 | 99 | 209 | 236 | 248 | 249 | 270 | **299** | **311** | **331** | **339** | **339** |
| 总行数 | 7581 | 7581 | 7581 | 7692 | 7692 | 7672 | 7653 | 7653 | 7912 | 7939 | 7867 |

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
| `OPTIMIZATION_V18.md` | 最新轮次:删除无调用方的 Reactive 兼容路径(V18) |
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