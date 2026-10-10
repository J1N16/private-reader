# Private Reader 项目优化方案 V20 —— 章节内容展示流水线拆分 `ChapterContentViewer`

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V19.md](./OPTIMIZATION_V19.md)(V19 阅读进度保存/恢复拆分)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 承接 V19 遗留的「拆分 `showChapterContent(Project,...)` 展示路径」

---

## 一、背景

V16–V19 后,`NotificationServiceImpl` 由 1186 行降至 741 行。剩余最大的一块是
**`showChapterContent(Project, bookId, chapterId, pageNumber, title, content)`** 的完整展示流水线
(约 80 行),它把「异步取书 → UI 线程状态写入 → 页码恢复/分页 → 标题内容构建 → 展示 → 预加载 → 保存进度」
全部内联在宿主中,既难以独立测试,也占据大量行数。

## 二、改动内容

### 2.1 新增 `ChapterContentViewer`(192 行)

`service/impl/notification/ChapterContentViewer.java`,承接上述展示流水线:

1. `bookService.getBookById(bookId)` 异步取书(IO 调度)
2. `ApplicationManager.invokeLater(..., defaultModalityState())` 回到 UI 线程
3. 关闭旧通知 → 写入书籍/章节状态
4. 仅当传入页码为默认值 1 时,经 `NotificationProgressManager` 恢复保存页码
5. 分页 → 页码区间校验 → 构建通知标题/内容 → 展示当前页
6. 触发章节预加载 → 保存阅读进度

**窄接口 `Host`(7 方法)**:`getViewState`、`updateViewState`、`setCurrentChapterContent`、
`showCurrentPageInternal`、`showError`、`closeCurrentNotificationInternal`、`triggerChapterPreload`。

> 依赖 `NotificationProgressManager` 复用 V19 的恢复/保存逻辑,避免重复实现页码语义。

### 2.2 `NotificationServiceImpl` 瘦身

- `showChapterContent(Project,...)` 缩减为一行委托:`chapterContentViewer.showChapterContent(...)`
- 新增 `chapterContentViewer` 字段 + `contentViewerHost` 匿名实现
- 删除 2 个失效 import(`NovelParser.Chapter`、`ModalityState`)
- 净变化:**741 → 694 行(—47)**

## 三、测试

### 新增 `ChapterContentViewerTest`(3 测试)

| 测试 | 断言 |
|------|------|
| `showsChapterContentAtFirstPage` | 书/章节写入状态、定位首页、展示标题正确、触发当前章节预加载 |
| `restoresSavedPageWhenIncomingPageIsDefault` | 传入页码为 1 时从仓库恢复到已保存页码(索引 2) |
| `emptyContentShowsErrorWithoutFetchingBook` | 空内容直接错误提示,且**不触发**取书 |

隔离:窄接口 `Host` 用可断言内存实现(复用真实 `ReaderViewState` + `ChapterPaginationCache`);
`mockStatic(ApplicationManager)` + `mockStatic(ModalityState)` 使 `invokeLater` 同步执行;
`RxJavaPlugins.setIoSchedulerHandler(trampoline)` 同步化 IO 调度。

> 说明:测试中发现 RxJava 禁止 `Single.just(null)`(null 插值),故「书籍不存在」分支的
> 真实触发需真实 `BookService` 返回空 Single;当前未覆盖该错误分支。

## 四、结果

| 指标 | V19 | V20 | 变化 |
|------|-----|-----|------|
| `NotificationServiceImpl` 行数 | 741 | **694** | **−47** |
| LINE 覆盖率 | 41.89% | **42.44%** | +0.55pp |
| `NotificationServiceImpl` 自身覆盖 | 33.8% | **42.6%** | +8.8pp |
| `ChapterContentViewer`(新) | — | 192 行(**84.1%**) | — |
| 测试数 | 346 | **349** | +3 |

**拆分历史**:`1186 →(V16)1096 →(V17)923 →(V18)788 →(V19)741 →(V20)694` 行,累计 **−492 行(−41.5%)**。

## 五、验证结果

- `./gradlew test` — ✅ 349 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **42.44%** > 阈值 0.35
- `./gradlew buildPlugin` — ✅ 产出 `private-reader-3.1.1.zip`

## 六、遗留事项

- [ ] `NotificationServiceImpl` 仍有 ~694 行;剩余主要职责:
      - `dispose` 生命周期(消息总线断开 + 通知关闭 + 模式判定,约 25 行)
      - 接口契约 API:分页(`setCurrentChapterContent`/`calculateTotalPages`/`getPageContent`)、
        状态读取(getter)、通知展示/关闭(应保留于接口实现)
- [ ] 「书籍不存在」错误分支(需真实空 Single)未覆盖
- [ ] `NotificationDisplayManager.showReadingNotification` 真实通知路径未覆盖(32.8%)
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,需真实 IDE/UI 测试框架
- [ ] `storage` 主体、`initialization` 为低覆盖保留方向

## 七、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/impl/notification/ChapterContentViewer.java` | **新增** | 内容展示流水线 + 窄接口 `Host` |
| `service/impl/NotificationServiceImpl.java` | 修改 | 展示方法缩减为委托 + 清理 import(−47 行) |
| `service/impl/notification/ChapterContentViewerTest.java` | **新增** | 3 个展示/恢复/短路测试 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充展示拆分条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V20 |