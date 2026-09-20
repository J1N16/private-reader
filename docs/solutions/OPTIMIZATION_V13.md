# Private Reader 项目优化方案 V13 —— 导航流水线去重 / 网络检测缓存 / 仓储拆分 / service 层补测

> 创建日期: 2026-09-20
> 项目版本: 3.0.0
> 配套: [OPTIMIZATION_V12.md](./OPTIMIZATION_V12.md)(V12 构造器注入改造)
> 实施状态: 已完成 ✅(2026-09-20)
> 目标: 按优化方向落地第一批 5 项 —— 重复流水线消除、EDT 阻塞风险收敛、巨类拆分、
>       service 层测试补齐、覆盖率护栏上调

---

## 一、背景

在完成 V1~V12 的架构梳理后,按"优化方向"分析按序执行了前 5 步。扫描到的核心遗留问题:

1. **`NotificationServiceImpl.showNavigatedChapter` 两个重载各约 60 行流水线几乎逐行重复**
   (同步 EnhancedChapter 与异步 cachedChapters 两个数据源),维护双倍成本且易漂移。
2. **`NetworkUtils.isNetworkAvailable()` 串行扫描 3 个主机 × 3s 超时**,网络不可用时
   错误恢复路径(`ExceptionHandler`/`ChapterListDialog`)最多阻塞 **9 秒**。
3. **`FileBookRepository` 1295 行**,Gson 序列化 / 索引 IO / 详情 IO / 损坏恢复 / 缓存调度
   混杂,可测性与可维护性差。
4. **service/impl 层覆盖率低**(V12 遗留服务层 14.4%),构造器注入改造后已具备补测条件。
5. **覆盖率阈值停在 0.27**,实测已 31%+。

## 二、改动内容

### 2.1 导航流水线去重 → 单一 `showNavigatedChapter`

**合并前**:两个私有重载,签名分别为
`(Project, String chapterId, String title, String content, int index, boolean)`
与 `(Project, Chapter, String content, int index, boolean)`,方法体重复度 >90%。

**合并后**:单一方法 `showNavigatedChapter(Project, NovelParser.Chapter, String content, int index, boolean, String sourceLogTag)`,
两个调用点各自负责数据源差异:
- `processChapterNavigation`(同步):构造 `new NovelParser.Chapter(title, url)` 传入,`sourceLogTag=""`
- `processChapterNavigationWithCachedChapters`(异步):传原始 `targetChapter`,`sourceLogTag="cachedChapters"`

统一流水线仍为"更新状态 → 分页 → 显示通知 → 保存进度 → 预加载 → 发布事件"。
行为等价性保障:发布事件统一使用 `Chapter` 对象(原同步分支的差异集中在事件入参,
现收敛为同一路径);日志区分通过 `sourceLogTag` 保留(空串 → 原"导航到章节"文案,
"cachedChapters" → 原"使用cachedChapters导航到章节"文案)。

**代码量**:净删约 60 行重复(94 行变更)。

### 2.2 NetworkUtils 并行探测 + 30s TTL 缓存

**原实现**:串行 `for (host) isHostReachable(host)`,最坏 3×3s=9s。

**新实现**:
- `probeHostsInParallel()`:3 个主机经 `CompletableFuture.supplyAsync` **并行探测**,
  任一可达即返回 true,最坏仍约 3s。
- 30s TTL 结果缓存:`volatile CachedResult(timestamp, available)`,TTL 内重复调用
  (如异常恢复路径的多次 `isNetworkAvailable`)直接复用,不再重复阻塞。
- `isNetworkAvailableAsync` 保留不动(已是并行实现)。

两个调用点(`ExceptionHandler.handleNetworkRecovery`、`ChapterListDialog.loadChapters`)
不改代码即受益。

### 2.3 FileBookRepository 职责拆分(1295 → 762 行)

按"纯逻辑 vs IO vs 调度"切分为 3 个包级内部类 + 1 个瘦身后的仓库:

| 新类 | 行数 | 职责 | 从原类迁移的方法 |
|------|------|------|------------------|
| `BookJsonCodec` | 318 | 安全 Gson 构建、Book/BookIndex 与 JSON 互转、JsonObject 安全读取 | `createSecureGson`、`parseBookFromJson`、`getStringFromJson`/`getIntFromJson`/`getLongFromJson`/`getBooleanFromJson`、索引手动解析/Gson 兜底解析 |
| `BookIndexStore` | 173 | 索引文件读/写/增删改,原子写入(temp+rename) | `readBookIndices`、`saveBookIndices`、`updateBookIndex`、`removeBookFromIndex`、`clear` |
| `BookDetailsIo` | 176 | 详情文件原子写入、内容读取、备份、目录删除 | `saveBookDetails` 的 IO 部分、`markCorruptedBookFile` 备份、`deleteDirectory` |

`FileBookRepository` 仅保留:内存缓存(Guava)、章节缺失时的 URL 补充(`blockingGet` 处)、
损坏文件清理/阅读位置修复调度、以及公开 API(`addBook`/`updateBook`/`removeBook`/
`getAllBooks`/`getBook`/`clearAllBooks`/`saveBookDetails`/`cleanupCorruptedBooks`/
`repairMissingReadingContent`)的外围入口。

**公开 API 零变化**:两个测试类 `FileBookRepositoryTest`/`FileBookRepositoryCoreTest`
无需改动即全部通过(行为等价拆分的回归验证)。

### 2.4 service 层补测(249 → 270)

#### ChapterServiceImplTest +5(共 14)
| 测试 | 覆盖点 |
|------|--------|
| `getChapterReturnsMatchingChapterFromList` | getChapter 从列表取匹配章节 |
| `getChapterWithFallbackThrowsWhenChapterNotFound` | 回退抛 PrivateReaderException(兼容测试环境 CompositeException 包装) |
| `getChapterTitleReturnsErrorWhenBookMissing` | 书籍不存在 → 错误消息 |
| `getChapterTitleReturnsChapterTitleWhenFound` | 正常取标题 |
| `getChapterTitleReturnsErrorWhenChapterMissingFromList` | 章节不在列表 → 错误消息 |

#### NotificationServiceImplTest(新建,8 个)
mock 隔离策略:`mockStatic(ApplicationManager)`(提供 mock Application/MessageBus 供构造器
安全通过)+ `mockStatic(ReactiveSchedulers)`(runOnUI 同步执行,避免真实 EDT)
+ `mockStatic(NotificationDisplayManager)`(加载/错误/信息通知均不真正显示)。

| 分组 | 测试 |
|------|------|
| 分页核心 | `setCurrentChapterContentPaginatesAndPositionsFirstPage`、`setCurrentChapterContentWithNullContentDoesNotCrash`、`calculateTotalPagesMatchesConfiguredPageSize`、`getPageContentReturnsContentForValidPageAndPlaceholderForInvalid` |
| 状态读取 | `gettersReportEmptyStateBeforeReadingStarts` |
| 空状态短路 | `showPrevAndNextPageAreNoOpsWhenNothingReading`、`navigateChapterIsNoOpWhenNothingReading` |
| 加载状态 | `showLoadingNotificationCreatesLoadingNotificationAndSetsFlag` |

#### NetworkUtilsTest(新建,8 个,确定性不依赖外网)
通过包级测试辅助方法(`setCachedResultForTest`/`resetProbeCacheForTest`/`getCachedResultForTest`)
直接操纵缓存状态,TLL 内命中/失效、非法输入、不可解析主机快速失败等语义均被覆盖。

### 2.5 顺带修复的真实 Bug:`getChapterTitle` NPE

新测试 `getChapterTitleReturnsErrorWhenBookMissing` 暴露:

```
Single.fromCallable(() -> bookRepository.getBook(bookId))  // book 不存在 → null
    .flatMap(book -> { if (book == null) return "Error..."}) // 永远走不到!
```

RxJava 的 `Single.fromCallable` 在 callable 返回 null 时直接抛 **NPE**,
导致"书籍不存在应返回错误消息"的路径从未生效,真实表现为异常而非友好消息。

**修复**:`Single.fromCallable` → `Single.defer`(defer 中同步判断 book==null,
返回 `Single.just("Error: Book not found.")`),语义与注释一致。

### 2.6 覆盖率阈值 0.27 → 0.30

实测 LINE 覆盖率 31.47% → **33.54%**,按 V12 建议上调护栏至 0.30(安全余量约 3.5pp)。

## 三、覆盖率

| 指标 | V12 | V13 | 变化 |
|------|-----|-----|------|
| LINE | 31.47%(2414/7670) | **33.54%(2573/7672)** | +2.07pp |
| METHOD | 38.23% | **42.17%** | +3.94pp |
| BRANCH | 25.64% | **27.05%** | +1.41pp |

新增覆盖主要来自:ChapterServiceImpl 的 getChapter/getChapterTitle 分支、
NotificationServiceImpl 分页/空状态短路路径、NetworkUtils 缓存与容错路径。

## 四、验证结果

- `./gradlew clean build` — ✅ 全链路通过(**270 测试** + JaCoCo 报告 + **0.30 阈值**)
- 单元测试总数 249 → **270**(+21,含新建 2 个测试类与 1 个既有测试类扩充)
- `FileBookRepository` 拆分后原 14 个核心读写测试零改动全过(行为等价)

## 五、遗留事项

- [ ] `verifyPlugin` 本地未能运行:需联网下载 IDEA 263(比项目 sinceBuild=261 更新的平台)
      做兼容性验证,本地网络超时。**CI 的 verify-plugin job 有外网会正常执行**;
      如需本地复跑需配置代理或用本地缓存平台。
- [ ] `NotificationServiceImpl` 仍有 ~780 行(导航/事件处理/进度保存),可继续按 V11 遗留
      将事件处理链独立为 `ChapterEventProcessor`
- [ ] `NotificationBarModeService`(457 行,0% 覆盖)是下一个高收益补测目标,
      已有构造器注入条件,可按 `NotificationServiceImplTest` 的 mock 模式补测
- [ ] 若覆盖率达 36%+,可将阈值再上调至 0.33

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/impl/NotificationServiceImpl.java` | 修改 | 合并 showNavigatedChapter 双重载 |
| `util/NetworkUtils.java` | 修改 | 并行探测 + 30s TTL 缓存 + 包级测试辅助方法 |
| `repository/impl/FileBookRepository.java` | 修改 | 1295→762 行,委托 3 个内部职责类 |
| `repository/impl/BookJsonCodec.java` | 新增 | 安全 Gson 与 Book/BookIndex JSON 编解码 |
| `repository/impl/BookIndexStore.java` | 新增 | 索引文件原子读写 |
| `repository/impl/BookDetailsIo.java` | 新增 | 详情文件原子读写/备份/清理 |
| `service/impl/ChapterServiceImpl.java` | 修改 | 修复 getChapterTitle 书籍缺失 NPE |
| `build.gradle` | 修改 | 覆盖率阈值 0.27→0.30 |
| `service/impl/ChapterServiceImplTest.java` | 修改 | +5(共 14) |
| `service/impl/NotificationServiceImplTest.java` | 新增 | 8 个(分页/状态/空短路) |
| `util/NetworkUtilsTest.java` | 新增 | 8 个(缓存/TTL/容错) |

统计:6 个文件改动 355 insertions / 810 deletions;新增 5 个文件(3 主 + 2 测试)。