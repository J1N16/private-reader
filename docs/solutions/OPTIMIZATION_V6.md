# Private Reader 项目优化方案 V6

> 生成日期: 2026-08-11
> 项目版本: 2.5.1
> 分析范围: V3 遗留的 P3-1 导航方法重复合并、P3-3 分页重复计算
> 前序文档: [OPTIMIZATION_V5.md](./OPTIMIZATION_V5.md)（测试覆盖补齐 + TODO 修复，已完成）
> 实施状态: 全部完成 ✅（2026-08-11）

---

## 一、背景

V3 文档明确列出但未完成的 P3 级重构遗留项，经 V4/V5 后仍存在：

- **P3-1 导航方法重复**：`NotificationServiceImpl` 内 4 组近乎相同的导航方法
  （`processChapterNavigation` / `processChapterNavigationWithCachedChapters` /
  `processChapterNavigationToLastPage` / `processChapterNavigationToLastPageWithCachedChapters`），
  唯一差异是数据源（`EnhancedChapter` 同步含内容 vs `Chapter` 需异步从 parser 获取）与
  `navigateToLastPage` 标志，其余逻辑逐行重复。
- **P3-3 分页重复计算**：`setCurrentChapterContent()` 每次切章/更新都整章重 `paginateContent()`，
  多处调用点（`:137,:149,:157,:670`）对大章节反复 O(n) 分页，无分页缓存。

---

## 二、已核实问题

### P1 级 —— 导航方法重复【可维护性】

`NotificationServiceImpl.java` 中 4 组导航方法均含以下重复块（共重复 4 次）：
空列表检查 → 提取 URL → `ChapterNavigationHelper.findChapterIndex` → `validateNavigation`
→ 错误弹窗 → `calculateTargetIndex` → 取目标章节 → `showNavigatedChapter`。

其中 ToLastPage 变体与普通变体的差异仅是 `showNavigatedChapter` 的
`navigateToLastPage` 布尔参数不同。

### P2 级 —— 分页重复计算【性能】

`setCurrentChapterContent()` 每次调用都执行 `paginateContent(content, pageSize)` 整章分页。
阅读流程中同一章节内容会被多次传入（`showChapterContent` 双路径、导航展示流水线），
每次触发重复分页。

---

## 三、修复方案

### P1 —— 合并 4 组导航方法为 2 组 + 抽取公共解析

- 4 组 → 2 组：`processChapterNavigation`（同步数据源）、
  `processChapterNavigationWithCachedChapters`（异步数据源），各新增
  `navigateToLastPage` 布尔参数。
- 新增 `resolveNavigationTarget(List<String> chapterUrls, int direction, int totalChapters, String logPrefix)`
  私有 helper，收敛重复的"查找索引 → 验证 → 错误弹窗 → 计算目标"四步逻辑。
- 删除 `processChapterNavigationToLastPage` / `processChapterNavigationToLastPageWithCachedChapters`。
- 调用点更新：`navigateChapter` 传 `false`，`navigateChapterToLastPage` 传 `true`。
- **行为保真**：`resolveNavigationTarget` 的日志前缀差异保留（普通导航无前缀，ToLastPage 带
  `[通知栏模式] `），错误弹窗与返回路径与原实现完全一致。

### P2 —— 分页结果缓存

- 新增字段 `currentChapterContent` / `currentPageSize`，记录上次分页的原文与 pageSize。
- `setCurrentChapterContent`：内容与 pageSize 均未变化时复用 `currentPages` 直接返回，
  否则重分页并更新缓存。
- **安全性**：核实全部 4 个调用点（`:205,:670` 的恢复页码路径、`showNavigatedChapter`
  两个重载）均在调用后立即覆盖 `currentPageIndex`，不依赖方法内部"重置为 0"的副作用，
  缓存命中直接 return 不影响页码定位。

---

## 四、实施结果

| 编号 | 任务 | 状态 |
|------|------|------|
| P1 | 4 组导航方法合并为 2 组，抽取 resolveNavigationTarget | ✅ |
| P2 | setCurrentChapterContent 分页结果缓存 | ✅ |
| T1 | 抽取 ChapterPaginationCache 分页缓存 helper + 8 测试 | ✅ |
| T2 | FileBookRepositoryCoreTest 核心读写路径 10 测试 | ✅ |

**涉及文件**:
- `NotificationServiceImpl.java`（1400 → 1308 行，-92 行）
- 新增 `service/impl/notification/ChapterPaginationCache.java`
- 新增测试 `ChapterPaginationCacheTest`（8）、`FileBookRepositoryCoreTest`（10）

**测试总数**: 81 → 99（+18），14 个测试类，0 失败 / 0 错误 / 0 跳过。

---

## 五、验证标准

- [x] `./gradlew compileJava` 编译通过
- [x] `./gradlew test` 99 个测试全部通过（0 失败 / 0 错误 / 0 跳过）
- [x] 导航方向（上一章/下一章）、末页导航、同步/异步数据源四条路径行为保持一致
- [x] 同一章节内容重复展示时复用分页结果，不再重复整章分页
- [x] FileBookRepository 的 addBook/updateBook/缓存命中/索引恢复/排序/清空/损坏清理路径被覆盖
