# Private Reader 项目优化方案 V4

> 生成日期: 2026-08-10
> 项目版本: 2.5.1
> 分析范围: V3 之外的新优化方向（线程安全、测试覆盖、UI 阻塞、资源生命周期、死代码）
> 前序文档: [OPTIMIZATION_V3.md](./OPTIMIZATION_V3.md)（2.5.1 实施记录，已完成 P0/P1/P2/P3）
> 实施状态: 全部完成 ✅（2026-08-10）

---

## 实施记录

| 编号 | 任务 | 状态 | 完成日期 |
|------|------|------|----------|
| N1 | 消除 UI 线程阻塞（BookshelfDialog 轮询 / ChapterListDialog 延睡） | ✅ 已完成 | 2026-08-10 |
| N2 | 删除 StorageManager @Deprecated 死代码 | ✅ 已完成 | 2026-08-10 |
| N3 | SafeHttpRequestExecutor 线程池生命周期管理 | ✅ 已完成 | 2026-08-10 |
| N4 | 补充核心逻辑单元测试（48 个测试，原 20 个） | ✅ 已完成 | 2026-08-10 |
| N5 | 评估并加固非 volatile 竞态 | ✅ 已完成 | 2026-08-10 |

### 批次 N1 已完成的修改
- `BookshelfDialog.doOKAction()`：移除 `Thread.sleep(100)` 轮询 `operationCompleted` 的 5 秒阻塞，
  改为 `CompletableFuture` 在 EDT 内完成操作后直接关闭对话框，后台线程用 `get(5s)` 超时保护兜底
- `ChapterListDialog`：重试延时从 EDT 内 `Thread.sleep(1000)` 改为 `CompletableFuture.delayedExecutor` 后台延时

### 批次 N2 已完成的修改
- `StorageManager.clearAllStorage()` 内 `getChapterCacheManager()`（@Deprecated）改为
  `getReactiveChapterCacheRepository()`，删除废弃方法

### 批次 N3 已完成的修改
- `SafeHttpRequestExecutor`：自建静态 `ThreadPoolExecutor` 改为复用 IntelliJ 平台线程池
  `AppExecutorUtil.getAppExecutorService()`（生命周期随应用管理，避免插件卸载泄漏）
- 删除无调用方的 `shutdown()`、`getThreadPoolStatus()`、`createHttpExecutor()`；移除 4 处线程池状态日志

### 批次 N4 已完成的修改
- 抽取 `PaginationHelper` 工具类（分页纯逻辑），`NotificationServiceImpl.paginateContent()` 委托
- 新增 3 个测试类：`ChapterNavigationHelperTest`（11）、`ProgressSaveHelperTest`（7）、`PaginationHelperTest`（10）
- 测试总数：20 → 48，全部通过

### 批次 N5 已完成的修改
- 评估：所有分页/导航字段写均收敛到 EDT（`invokeLater` 回调），io 线程仅产生参数传递，竞态窗口实际极小
- 加固：`currentPages`/`currentPageIndex`/`currentBook`/`currentChapterId`/`currentChapterTitle` 加 `volatile`
  消除跨线程陈旧读隐患（成本极低）

**验证**：`./gradlew test` 48 个测试全通过（8 个测试类，0 失败 / 0 错误 / 0 跳过）。

---

## 一、背景

V3 文档覆盖了缓存路径、ReactiveTaskManager 双实例、ReaderViewModel 线程安全、HTTP 路径统一、
日志降级、DatabaseManager 连接复用、死代码清理、NotificationServiceImpl 拆分等。V3 完成后重新
审视项目，发现 5 个 **V3 未覆盖** 的方向。本文档按优先级记录并分批次实施。

---

## 二、已核实问题清单（按优先级）

### N1 级 —— 用户体验 / 正确性

#### N1-1 UI 线程阻塞（Thread.sleep 轮询 / 延睡）【UX】
**问题**:
- `BookshelfDialog.doOKAction()`（`:392-409`）: 后台线程中 `Thread.sleep(100)` 循环轮询
  `operationCompleted` 标志最多 5 秒。但该标志由 EDT 内的 `invokeLater(() -> panel.selectBookAndLoadProgress(...))`
  设置——后台线程在**死等一个 EDT 才可能置位的标志**，纯无谓阻塞；且若 `selectBookAndLoadProgress` 内部
  也依赖 EDT，可能互相等待。
- `ChapterListDialog`（`:378`）: 章节列表加载失败重试时，在 EDT 内 `Thread.sleep(1000)`，直接冻结界面。

**后果**: 打开书籍操作可能卡住 5 秒；章节重试时 IDE 界面冻结。

**修复**: 改为事件驱动，移除轮询与 EDT 内 sleep：
1. `BookshelfDialog`: 后台线程在 `SwingUtilities.invokeLater` 内完成操作后直接通过 EDT 关对话框，
   去掉 `operationCompleted` 轮询；超时改用后台线程自身 `Future.get(timeout)` 或完全依赖操作自身完成。
2. `ChapterListDialog`: 重试延时移到后台线程（如 `CompletableFuture.delayedExecutor`），EDT 内不 sleep。

---

### N2 级 —— 死代码清理

#### N2-1 StorageManager @Deprecated 方法【死代码】
**问题**: `getChapterCacheManager()`（`StorageManager.java:173-177`）标记 `@Deprecated`，唯一调用方
是 `clearAllStorage()`（`:288`），可用非废弃的 `getReactiveChapterCacheRepository()` 替代。

**修复**: 将 `clearAllStorage()` 内调用改为 `getReactiveChapterCacheRepository()`，删除废弃方法。
另注意 `StorageManager` 已有 `getReactiveChapterCacheRepository()` 非废弃副本，二者等价。

---

### N3 级 —— 资源生命周期

#### N3-1 SafeHttpRequestExecutor 线程池泄漏【资源】
**问题**: `createHttpExecutor()`（`SafeHttpRequestExecutor.java:44-72`）自建固定线程数
`ThreadPoolExecutor`（有界队列 100，CallerRunsPolicy）。静态字段持有，插件卸载时若无人调用
`shutdown()` 则线程池泄漏（虽线程为 daemon，但占用资源）。

**修复**: 二选一：
1. 复用 IntelliJ 平台线程池（`AppExecutorUtil.getAppExecutorService()`），移除自建线程池；
2. 保留自建但接入插件卸载生命周期（如 `ApplicationManager` 的应用 Disposable）统一关闭。

---

### N4 级 —— 测试覆盖

#### N4-1 核心逻辑无测试【可维护性】
**问题**: 主代码 17604 行 / 99 文件，测试仅 5 个测试类 20 个测试（仓库层 + ViewModel）。
`NotificationServiceImpl`（1439 行）、`FileBookRepository`（1300 行）、`UniversalParser`（~416 行）
等核心类 **零测试**。V3 中 P3-1 合并 4 个导航方法只能靠"行为逐字比对"保障，风险高。

**修复**: 为导航/分页/进度保存等纯逻辑补充单元测试。优先覆盖：
- `NotificationServiceImpl.paginateContent()`（纯函数分页）
- `ChapterNavigationHelper`（索引查找、导航验证、目标索引计算）
- `ProgressSaveHelper`（标题/内容构建）
- `UniversalParser`（章节标题识别）

---

### N5 级 —— 线程安全

#### N5-1 NotificationServiceImpl 分页状态非 volatile【竞态】
**问题**: `currentPages`/`currentPageIndex`/`currentBook`/`currentChapterId`/`currentChapterTitle`
（`NotificationServiceImpl.java:72-76`）在 UI 线程与 io 线程间共享，均非 `volatile`。
`navigateChapter` 的异步回调在 io 线程 `runOnUI` 切回 EDT 后写入，读取方分散在多处。

**后果**: 极端情况下读到过期状态（如导航后 `currentPages` 未刷新）。实际影响取决于 EDT 汇聚程度，
需结合 N4-1 测试评估。

**修复**: 先评估；若实际影响小可保持现状（EDT 已收敛多数写），或对关键字段加 `volatile`。

---

## 三、实施批次建议

> 每批完成后执行：`./gradlew build` + 全部测试。

### 批次 N1 —— N1（UI 阻塞，1 项，先做）
1. **N1-1** 消除 BookshelfDialog / ChapterListDialog 的 Thread.sleep 阻塞

风险：低-中。涉及文件：`BookshelfDialog`、`ChapterListDialog`。

### 批次 N2 —— N2（死代码，1 项）
1. **N2-1** 删除 StorageManager @Deprecated 方法

风险：极低。涉及文件：`StorageManager`。

### 批次 N3 —— N3（资源，1 项）
1. **N3-1** 线程池接入生命周期或复用平台池

风险：低-中。涉及文件：`SafeHttpRequestExecutor`、`plugin.xml`（如需注册 Disposable）。

### 批次 N4 —— N4（测试，1 项）
1. **N4-1** 补充核心逻辑单元测试

风险：低。涉及新增测试文件。**收益最高，建议至少做完 N1-N3 后必做。**

### 批次 N5 —— N5（评估，1 项）
1. **N5-1** 评估非 volatile 竞态，视结果处理

风险：评估为主，改动视结论。

---

## 四、验证标准

- [ ] `./gradlew build` 成功
- [ ] `./gradlew test` 全部通过
- [ ] 打开书籍无卡顿（BookshelfDialog 不再轮询等待 5 秒）
- [ ] 章节列表加载失败重试不冻结 UI
- [ ] 无新增 IDE 错误日志（Event Log）
