# Private Reader 优化方案 V32 —— 编排类分支补测与书籍缺章自愈

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V31.md](./OPTIMIZATION_V31.md)(V31 存储迁移与模块补测)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 攻坚 V29–V31 已可测但分支未尽的三个「编排类」
> (`ReaderModeSwitcher`、`NotificationBarModeService`、`ChapterNavigator`),
> 并覆盖 `FileBookRepository` 的 details.json 缺章自愈路径。

---

## 一、背景

V31 后 LINE 65.18%。剩余高价值缺口分两类:

1. **已有测试但分支未尽**(可确定性补测):
   | 类 | V31 覆盖 | 缺口 |
   |----|---------|------|
   | `ReaderModeSwitcher` | 56.8% | `invokeLater` 内的隐藏/显示工具窗口 lambda 从未执行(测试只 mock 了 Application,平台的 `invokeLater` 在 mock 下不入队执行) |
   | `NotificationBarModeService` | 74.9% | 无项目守卫、页码夹紧、状态清理、默认构造器、`getInstance` |
   | `ChapterNavigator` | 77.7% | 异步数据源下的末页导航、未知当前章节 |

2. **深层自愈逻辑(需注入服务)**:`FileBookRepository.getBook()` 在
   details.json 无章节时会经 `ApplicationManager` 取 `ChapterService` 重抓章节并回写文件。

## 二、改动内容(零生产代码改动,纯新增/扩展测试)

### 2.1 `ReaderModeSwitcherTest`(7 → 14)

**关键**:把 mock `Application` 的 `invokeLater(Runnable)` 改为**同步执行**
(`doAnswer` 直接 `run()`)。此前该 lambda 永不执行,导致 `toolWindow.hide/show`
与 `applyInitialModeForProject` 的多条路径 0 覆盖。新增:

- 通知栏模式下隐藏**可见**工具窗口(`verify(toolWindow).hide(null)`)
- 面板模式下显示**隐藏**工具窗口(`verify(toolWindow).show(null)`)
- 面板模式但工具窗口缺失 → 不抛异常(仅 warn)
- `applyInitialModeForProject`(面板/通知栏)分别重读设置、绑定 `NotificationBarModeService`
- **默认构造器**从 `ApplicationManager` 解析三个服务
- `notificationReaderSettings` 为 null 时禁用自动阅读但不失败

> 踩坑:通知栏模式下 `applyModeForProject`(构造期)与 `applyInitialModeForProject`
> 都会调用 `barService.setProject(project)`,断言须用 `atLeastOnce()` 而非 `times(1)`。

### 2.2 `NotificationBarModeServiceTest`(14 → 24)

新增:`activateNotificationBarMode` 的**页码夹紧**(超上界→总页数、非正→1)、
无项目守卫(`handleNext/PrevPageAction`、`handleNext/PrevChapterAction` 早退)、
`deactivate` 清除 `currentBookId/ChapterId/PageNumber`、`setProject` 忽略已释放项目、
`settingsChanged` 的「无项目 / 有模式无书籍」两条跳过分支、`getInstance` 委派、`dispose`。

### 2.3 `ChapterNavigatorTest`(8 → 11)

- 异步数据源(`cachedChapters` 为空)下的 `navigateToLastPage` 委派 `BookService.getChaptersSync`
- 异步数据源下空章节列表弹信息
- 当前章节不在列表中 → 弹「导航失败」

> 说明:「目标章节内容为空」「无解析器」「分页后内容为空」三个分支**不可测**:
> 前两者令 `Single.fromCallable` 返回 null,RxJava 立即抛 NPE 走 `doOnError` 的
> `LOG.error`(测试环境断言错误日志,不作为用例);第三者被上游内容校验遮蔽，
> 实际不可达。已在测试文件中注明。

### 2.4 `FileBookRepositoryChapterFetchTest`(新,2)

复用生产 `FileStorageRepository(Path)`(V28 已提供),配合
`mockStatic(ApplicationManager)` 注入 `ChapterService`:

- details.json 无章节 → 成功抓取后**补全 + 回写文件**(该段此前 0 覆盖)
- 抓取到空列表 → 返回原书籍不崩溃

## 三、结果

| 指标 | V31 | V32 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 65.18% | **66.04%**(5300/8025) | **+0.86pp** |
| 测试数 | 655 | **678** | +23 |
| `ReaderModeSwitcher` | 56.8% | **82.0%** | **+25.2pp** |
| `NotificationBarModeService` | 74.9% | **79.8%** | +4.9pp |
| `ChapterNavigator` | 77.7% | **86.0%** | +8.3pp |
| `FileBookRepository` | 64.3% | **70.0%** | +5.7pp |
| 覆盖率护栏 | 0.61 | **0.62** | +0.01 |

## 四、验证结果

- `./gradlew test` — ✅ 678 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **66.04%** > 阈值 0.62

## 五、遗留事项

- [ ] `NotificationBarModeService` 剩余 ~49 行:`activate/refresh` 的
      `doOnError`/顶层 `catch` 与「内容为 null」等 `LOG.error` 分支
- [ ] `ChapterNavigator` 剩余 ~17 行:`doOnError` 与目标内容为空(见上)
- [ ] `ReaderViewModel`(68.8%)/`CacheConfigurable`(54.3%)/`UniversalParser`(66.8%):
      多分支编排与 HTML 解析边界,需按路径逐个补
- [ ] 三个 Dialog + `ReaderPanel` + `DiagnosticDialog`:构造期深层平台服务
      (`ExperimentalUI`)`@NotNull` 断言,当前 mock 环境不可测
- [ ] `ProjectInitializationActivity`(66)/`PrivateReaderStartupActivity`(55):启动活动

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/ReaderModeSwitcherTest.java` | 修改 | +7 个(invokeLater 同步化后覆盖窗口隐藏/显示与应用初始模式) |
| `service/NotificationBarModeServiceTest.java` | 修改 | +10 个(页码夹紧/守卫/状态清理/默认构造器) |
| `service/impl/notification/ChapterNavigatorTest.java` | 修改 | +3 个(异步末页导航/空列表/未知章节) |
| `repository/impl/FileBookRepositoryChapterFetchTest.java` | **新增** | 2 个(缺章自愈与空抓取) |
| `build.gradle` | 修改 | 覆盖率阈值 0.61 → 0.62 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V32 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/踩坑/文档清单更新至 V32 |