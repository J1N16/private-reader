# Private Reader 项目优化方案 V17 —— 章节导航流水线拆分 `ChapterNavigator`

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V16.md](./OPTIMIZATION_V16.md)(V16 拆分章节变更事件处理 `ChapterEventProcessor`)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 承接 V16 遗留的「`NotificationServiceImpl` 仍过大,可继续拆导航流水线」,抽出 `ChapterNavigator`

---

## 一、背景

V16 拆出 `ChapterEventProcessor` 后,`NotificationServiceImpl` 从 1186 降至 1096 行,但仍是全项目最大类。
按 `docs/solutions/README.md` 遗留事项,本轮继续拆分内聚度最高的第二块:**章节导航流水线**。

涉及方法(原 `NotificationServiceImpl`):

| 方法 | 职责 |
|------|------|
| `navigateChapter` | 导航到相邻章节第一页 |
| `navigateChapterToLastPage` | 导航到相邻章节最后一页(首页往前翻时触发) |
| `processChapterNavigation` | 同步数据源(`EnhancedChapter`)处理 |
| `processChapterNavigationWithCachedChapters` | 缓存数据源(`Book.cachedChapters`)处理 |
| `resolveNavigationTarget` | 索引解析 + 方向校验 |
| `showNavigatedChapter` | 统一展示流水线 |

这套逻辑依赖 `BookService` / 章节数据源 / 分页 / 通知/进度/预加载/事件发布,与通知栏服务自身的
「状态编排」耦合度低,是天然的第二条拆分边界。

## 二、改动内容

### 2.1 新增 `ChapterNavigator`(338 行)

`service/impl/notification/ChapterNavigator.java`,承接上述全部导航方法:

1. **两种数据源收敛**:优先 `Book.cachedChapters`(内存);为空时异步 `BookService.getChaptersSync`
2. **统一展示流水线** `showNavigatedChapter`:更新状态 → 分页 → 定位目标页 → 显示通知 → 保存进度 →
   触发预加载 → 发布章节变更事件
3. **边界保护**:第一章再往前 / 最后一章再往后,均走 `ChapterNavigationHelper` 校验并弹信息通知

**窄接口 `Host`(9 个方法)**:读/写状态、分页、展示当前页、展示错误/信息、展示加载中、触发预加载、
发布章节变更。宿主以匿名内部类实现。相比 V16 的 `Host`,新增 `showInfo` / `publishChapterChanged`,
并复用 `showLoadingNotification` / `triggerChapterPreload`。

### 2.2 `NotificationServiceImpl` 进一步瘦身

- 删除 6 个导航方法体(约 173 行)
- 新增 `chapterNavigator` 字段 + `navigatorHost` 匿名实现
- `showPrevPage` / `showNextPage` / `navigateChapter` 改为委托 `chapterNavigator`
- 新增 `publishChapterChanged(book, chapter)`(设置事件源 + 发布消息)
- 净变化:**1096 → 923 行(—173)**

> 加载状态门控(`isLoadingChapter`)仍保留在服务内,由 `showPrevPage`/`showNextPage`/`navigateChapter`
> 入口判断;`navigateToLastPage` 内部状态校验保持原语义。

## 三、测试

### 新增 `ChapterNavigatorTest`(8 测试)

「可断言内存 Host」策略(复用真实 `ReaderViewState` + `ChapterPaginationCache`):

| 测试 | 断言 |
|------|------|
| `navigateChapterReadsCachedChaptersAndAdvancesToNext` | 缓存源前进一章、展示标题、发布事件、预加载目标索引 |
| `navigateChapterMovesToPreviousChapter` | 缓存源后退一章 |
| `navigateChapterAtFirstChapterNotifiesInsteadOfNavigating` | 第一章再往前 → 信息通知、零事件 |
| `navigateChapterAtLastChapterNotifiesInsteadOfNavigating` | 最后一章再往后 → 信息通知、零事件 |
| `navigateToLastPagePositionsAtTargetChapterLastPage` | 首页往前翻 → 定位目标章末页、发布事件 |
| `navigateToLastPageWhenNothingReadingShowsInfo` | 空会话 → 信息通知 |
| `navigateChapterFallsBackToBookServiceWhenNoCachedChapters` | 无缓存 → 异步 `getChaptersSync` |
| `navigateChapterWithEmptyChapterListShowsInfo` | 空章节列表 → 信息通知 |

隔离:`mockStatic(ApplicationManager)`(供 `ProgressSaveHelper` 取进度仓库)、
`RxJavaPlugins.setIoSchedulerHandler(trampoline)` 同步化、mock `ReactiveSchedulers.runOnUI`。

## 四、覆盖率

| 指标 | V16 | V17 | 变化 |
|------|-----|-----|------|
| LINE | 39.95%(3161/7912) | **41.08%(3261/7939)** | +1.13pp |
| 测试数 | 331 | **339** | +8 |

| 文件 | V16 | V17 |
|------|-----|-----|
| `NotificationServiceImpl` | ~1096 行 | **923 行** |
| `ChapterEventProcessor` | 228 行(74.2%) | 228 行(74.2%) |
| `ChapterNavigator`(新) | — | 338 行(77.7%) |

护栏阈值维持 **0.35**(实测余量 6.08pp)。

## 五、验证结果

- `./gradlew test` — ✅ **339 测试全通过**(0 失败 / 0 错误)
- `./gradlew build` — ✅ 打包 + JaCoCo LINE **41.08%** > 阈值 0.35

## 六、遗留事项

- [ ] `NotificationServiceImpl` 仍有 ~923 行,可评估拆分 Reactive 兼容路径
      `showChapterContent(Book,...)`(~130 行,疑似无生产调用方)与 `dispose` 生命周期
- [ ] `NotificationDisplayManager.showReadingNotification` 真实通知创建路径仍未覆盖(32.8%)
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,仍需真实 IDE 或 UI 测试框架
- [ ] `storage` 主体、`initialization` 为低覆盖保留方向

## 七、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/impl/notification/ChapterNavigator.java` | **新增** | 章节导航流水线 + 窄接口 `Host` |
| `service/impl/NotificationServiceImpl.java` | 修改 | 删除 6 个导航方法,委托导航器,新增 `publishChapterChanged`(—173 行) |
| `service/impl/notification/ChapterNavigatorTest.java` | **新增** | 8 个导航/边界/数据源测试 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充导航拆分条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V17 |