# Private Reader 项目优化方案 V14 —— P0/P1 落地:通知栏服务补测、注入化收尾、展示层回调化、章节列表去重

> 创建日期: 2026-09-20
> 项目版本: 3.0.0
> 配套: [OPTIMIZATION_V13.md](./OPTIMIZATION_V13.md)(V13 导航去重/网络缓存/仓储拆分)与 [archive/OPTIMIZATION_V12.md](./archive/OPTIMIZATION_V12.md)(V12 构造器注入)
> 实施状态: 已完成 ✅(2026-09-20)
> 目标: 按优化方向落地 P0(通知栏 0% 覆盖补测、NotificationServiceImpl 注入收尾、展示层回调化)
>      与 P1(ChapterListDialog 去重 + 纯逻辑提取、RepositoryModule 评估)

---

## 一、背景

在 V13 收尾后,`docs/solutions/README.md` 遗留了明确的 P0/P1 优化方向。本轮逐一落地:

1. **`NotificationBarModeService`(service 包,457 行)0% 覆盖**,是文档标的「下一个高收益补测目标」,
   已有构造器注入条件但缺测试。
2. **`NotificationServiceImpl` 仍有 4 处 `SqliteReadingProgressRepository` 的按需 `getService` 硬编码**,
   V12 构造器注入改造未覆盖到阅读进度仓库;且 `createRepositoryIfNeeded` 语义逐渐退化。
3. **`NotificationDisplayManager` 内部 4 个静态方法直接 `getService(NotificationService/ReaderModeSettings)`**,
   展示层与 IntelliJ 容器耦合,违背其「纯展示职责」注释,也不利于测试与复用。
4. **`ChapterListDialog`(732 行)存在三处几乎逐字重复的「选择上次阅读章节」代码**,且完全不可测(0% 覆盖)。
5. **`RepositoryModule`(V12 遗留)聚合格局待评估**。

## 二、改动内容

### 2.1 NotificationBarModeService 补测(0% → 74.79%)➕ 包级测试辅助

**新增测试类** `service/NotificationBarModeServiceTest.java`,13 个测试,复用
`NotificationServiceImplTest` 的成熟 mock 模式(`mockStatic(ApplicationManager)` 提供
mock Application/MessageBus、`mockStatic(ProjectManager)`、`mockStatic(ModalityState)`),
并新增关键技巧:`executeOnPooledThread`/`invokeLater`(单参+双参)全部 stub 为**同步执行**,
使异步加载/保存进度流程可确定性验证(verify `showChapterContent`、`updateProgress`)。

**覆盖分支**:
- 激活:切换 NOTIFICATION_BAR 模式 + showLoadingNotification + 异步展示章节内容
- 反激活:closeAllNotifications + 恢复 DEFAULT
- 翻页:活动会话中调 showNextPage/showPrevPage 并异步保存进度(page/position 断言)
- 翻页短路:无活动会话且无上次阅读记录 → 零副作用
- 翻页恢复:无活动会话但存在上次阅读记录 → 自动 activateNotificationBarMode
- 章节导航:handleNext/PrevChapterAction → navigateChapter(±1)+ 状态刷新
- 设置变更刷新:活动会话 → refreshNotificationDisplay;空闲 → 跳过
- 启动初始化:enabled 且存在进度 / disabled 跳过
- 项目回退:当前 project 弃置时 resolveProject 回退到 openProjects

**生产改动**:仅添加 3 个包级测试辅助 getter(`getCurrentBookIdForTest`/
`getCurrentChapterIdForTest`/`getCurrentPageNumberForTest`),参照 V13
`NetworkUtilsTest` 的 `setCachedResultForTest` 先例,不影响公开 API。

### 2.2 NotificationServiceImpl 注入化收尾(消除 4 处 getService)

按 V12 既定方向把阅读进度仓库纳入构造器注入:

- 新增 `private final ReadingProgressRepository readingProgressRepository`(接口类型,替换
  四处对具体类 `SqliteReadingProgressRepository` 的 `getService` 调用)
- 构造器签名扩展为 6 参:追加 `ReadingProgressRepository`
  (`NotificationServiceImplTest` 同步传入 `mock(ReadingProgressRepository.class)`)
- `restoreSavedPageNumber` / 事件处理页码恢复 / Reactive 保存 / `saveNotificationModeProgress`
  四处全部改为直接使用注入字段;
  `saveNotificationModeProgress(boolean, boolean)` 的 `createRepositoryIfNeeded` 参数
  语义已由容器注入替代(参数保留以兼容既有调用方,内部不再区分获取方式)
- 顺带修复事件处理分支页码恢复的**括号/缩进错位**(原 `try { Optional... } else { }` 嵌套错误,
  注入后重写为规整的 `if (repository != null) { ... }` 结构)

### 2.3 NotificationDisplayManager 回调化(纯展示职责兑现)

原 4 个方法 `showPrevPage`/`showNextPage`/`navigateChapter`/`switchBackToReader` 内部
直接 `getService(NotificationService/ReaderModeSettings)`,已从展示类中移除:

- `showReadingNotification` 新增 5 个 `Runnable` 回调参数(上一页/下一页/上一章/下一章/返回阅读器),
  按键可用性判断后接待入回调;callback 为 null 时不加按钮(向后兼容)
- 模式切换逻辑迁至新增 `NotificationBarModeServiceUtils.switchBackToReader()`
  (行为等价,仍经 invokeLater + setNotificationMode(false))
- `NotificationServiceImpl.showCurrentPageInternal` 调用点传入 5 个 lambda:
  `() -> showPrevPage(project)` 等,行为完全等价

**新增纯函数(可测性收益)**:
- `buildDisplayTitle(title, pageIndex, totalPages, showPageNumbers)` — 标题构建规则
- `isPrevPageActionEnabled(pageIndex)` / `isNextPageActionEnabled(pageIndex, totalPages)` — 按钮可用性

配套 `NotificationDisplayManagerTest` 新增 6 个测试(标题构建 4 个 + 按钮可用性 2 个),
覆盖率 17.81% → **32.76%**。

### 2.4 ChapterListDialog 去重 + 纯逻辑提取(P1)

**新增纯逻辑辅助类** `ui/dialog/ChapterListDialogSupport.java`(100% 覆盖,10 测试):

| 方法 | 职责 |
|------|------|
| `findChapterIndex(chapters, url)` | 章节 URL 查找(-1 未命中),null/空安全 |
| `resolveSelectIndex(chapters, lastChapterId)` | 选中决策:优先上次阅读,否则第一章 |
| `buildInfoLabel(book)` | 信息面板 HTML 文案(totalChapters=0 防除零) |
| `buildLoadingDots(count)` | 加载动效省略号(floorMod 负数安全) |

`ChapterListDialog` 中原**三处几乎重复**的「setListData + 选上次阅读章节」分支收敛为
`selectLastReadChapter(...)` 委托(`updateInfoLabel`/`createInfoPanel` 同样委托),
净删约 40 行重复;行为等价(UI 副作用经回调注入,纯函数部分全部可测)。

### 2.5 RepositoryModule 评估结论(保留)

核实两个既有调用方都属于**无法构造器注入**的场景:

1. `NovelParser.getChapterContent` 接口 **default 方法** —— 接口默认方法不能被服务容器注入,
   只能经 `RepositoryModule.getInstance()` 定位服务;
2. `CacheConfigurable`(IntelliJ 容器实例化的 UI 设置类)—— UI 组件无法走自定义构造器注入。

结论:**保留**,勿盲目拆除;其 `ensureInitialized` 惰性 + 空安全 getService 已是合理实现。
该项从 README 遗留事项中移除。

## 三、覆盖率

| 指标 | V13 | V14 | 变化 |
|------|-----|-----|------|
| LINE | 33.54%(2573/7672) | **36.35%(2782/7653)** | +2.81pp |
| 测试数 | 270 | **299** | +29 |

关键文件:

| 文件 | V13 | V14 | 变化 |
|------|-----|-----|------|
| NotificationBarModeService | 0.00% | **74.79%** | +74.79pp ✅ |
| NotificationDisplayManager | 17.81% | **32.76%** | +14.95pp |
| ChapterListDialogSupport(新) | — | **100.00%** | 新增 |
| NotificationServiceImpl | 14.86% | 15.03% | 注入化后微升(事件/保存流程待 GUI 冒烟覆盖) |

## 四、验证结果

- `./gradlew clean build` — ✅ 全链路通过(**299 测试** + JaCoCo LINE 36.35% > 阈值 0.30 + 打包)
- 单元测试总数 270 → **299**(+29:NotificationBarModeService 13 / ChapterListDialogSupport 10 / DisplayManager 6)
- 覆盖率护栏 0.30 保持(实测 36.35%,余量 6.35pp;按 README 长期建议,36%+ 可上调至 0.33)

## 五、遗留事项

- [ ] `NotificationServiceImpl` 事件处理链(`handleChapterChangedEvent` 等)仍可独立为 `ChapterEventProcessor`(V11/V13 持续遗留,低优先级)
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,仍需真实 IDE 或 UI 测试框架(V9-V14 保留方向)
- [ ] `storage` 主体、`initialization` 为低覆盖保留方向(V9/V10 遗留)
- [ ] `NotificationDisplayManager.showReadingNotification` 创建真实通知的路径仍依赖 NotificationGroupManager,未在单元测试覆盖(需 mockStatic 门面或 GUI 冒烟)
- [ ] 覆盖率实测 36.35%,阈值可上调至 0.33(按 README 建议待下一轮补测后执行)
- [ ] `verifyPlugin` 本地需联网下载 IDE 263 平台;**CI 的 verify-plugin job 有外网正常执行**

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/NotificationBarModeService.java` | 修改 | +3 包级测试辅助 getter |
| `service/NotificationBarModeServiceTest.java` | **新增** | 13 个测试(激活/翻页/导航/刷新/初始化/回退) |
| `service/impl/NotificationServiceImpl.java` | 修改 | 阅读进度仓库构造注入(4 处 getService 消除)+ 事件分支括号修复 + 回调调用点 |
| `service/impl/notification/NotificationDisplayManager.java` | 修改 | 5 回调参数化 + 3 纯函数提取 + 移除 getService |
| `service/impl/notification/NotificationBarModeServiceUtils.java` | **新增** | switchBackToReader 迁移 |
| `service/impl/notification/NotificationDisplayManagerTest.java` | 修改 | +6 测试(标题构建/按钮可用性) |
| `ui/dialog/ChapterListDialogSupport.java` | **新增** | 纯逻辑辅助类(4 方法) |
| `ui/dialog/ChapterListDialogSupportTest.java` | **新增** | 10 个测试 |
| `ui/dialog/ChapterListDialog.java` | 修改 | 3 处重复选择逻辑收敛 + 信息文案/加载动效委托 |
| `service/impl/NotificationServiceImplTest.java` | 修改 | 适配 6 参构造器 |

统计:4 个新增文件(2 主 + 2 测试)+ 6 个文件修改;新增测试 29 个。