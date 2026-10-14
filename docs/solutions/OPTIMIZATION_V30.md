# Private Reader 优化方案 V30 —— UI 动作类补测与通知服务活动会话状态机

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V29.md](./OPTIMIZATION_V29.md)(V29 三个设置面板 headless 补测)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 把 V29 的「UI 可测」结论推进到 `ui/actions`(18 个动作类),
> 并攻克 `NotificationServiceImpl` 最难覆盖的活动阅读会话状态机。

---

## 一、背景与关键发现

### 发现 1:`AnActionEvent`/`Presentation` 可在单测中 mock

V29 已证实 UI 组件可实例化;V30 进一步确认:

- `AnActionEvent`、`Presentation` 可被 Mockito mock;
- 面板/动作依赖的平台服务(`ApplicationManager`、`Application`、`Project`、
  `ReaderToolWindowFactory`、`ShowSettingsUtil`、`Messages`、`NotificationGroupManager`)均可
  `mockStatic` + mock 提供;
- 因此 18 个动作类的 `update()` 分支逻辑与 `actionPerformed()` 委派/早退分支全部可测。

### 发现 2:`Presentation` 必须用真实实例而非 mock

动作类的 `update()` 常写成:

```java
super.update(e);                       // 内部 setEnabled(pluginEnabled)
if (!e.getPresentation().isEnabled()) return;   // <-- 读取的是自己的写入
```

若 `Presentation` 是 mock,`isEnabled()` 恒为 `false`,`super.update()` 之后的逻辑**永不可达**,
测试会误判为「子类逻辑未覆盖」。改用 `new Presentation()` 真实实例后,
`setEnabled()/isEnabled()/setText()/getText()` 行为真实,断言也应改为
`assertTrue(presentation.isEnabled())` 而非 `verify(...setEnabled(true))`。

### 发现 3:`ModalityState.defaultModalityState()` 在 mock Application 下返回 null

`ChapterContentViewer` 的 UI 更新回调以
`application.invokeLater(runnable, ModalityState.defaultModalityState())` 提交。
在 `ApplicationManager` 被 mockStatic 后,`ModalityState.defaultModalityState()` 返回 **null**,
而该静态方法标注 `@NotNull`,平台在返回空值时**立即抛 `IllegalStateException`**——
此时 `invokeLater` **根本不会被调用**,导致 `showChapterContent` 的整段 UI 流水线静默失效。

这是本次最隐蔽的坑:日志里能看到 `onNext` 与 `beforeInvokeLater`,却永远到不了
`invokeLater`。解法:额外 `mockStatic(ModalityState.class)` 让
`defaultModalityState()` 返回一个 mock 实例,并对 `invokeLater(Runnable)` 与
`invokeLater(Runnable, ModalityState)` 两个重载都做同步执行桩。

## 二、改动内容(零生产代码改动,纯新增测试)

### 2.1 新增 `ActionsTest`(47 测试)

覆盖 18 个动作类:

- **BaseAction 契约**:`getActionUpdateThread() == BGT`;`update()` 依据
  「项目存在 + 插件启用」启停
- **章节/翻页导航**:通知栏模式下委派 `NotificationService.navigateChapter(±1)` /
  `NotificationBarModeService.handleNext|PrevPageAction`;阅读器模式下委派
  `ReaderPanel.navigateChapter(±1)`
- **`RemoveBookAction`**:无项目/无面板/未选书籍的早退 + `Messages.showWarningDialog`;
  确认删除后成功(→ `showInfoMessage` + `loadBooks`)/失败(→ `showWarningDialog`)/
  取消(→ 无操作)/服务缺失(→ `showError`)四条分支
- **`ShowChapterListAction`**:未选书籍告警;面板+选中书籍时 `update` 启用
- **设置类动作**:`ShowSettingsUtil.showSettingsDialog` 分别传入
  `CacheConfigurable` / `ReaderConfigurable` / `PluginConfigurable` / `ReaderModeConfigurable`
- **`ToggleReadingModeAction`**:翻转 `setNotificationMode`;update 文案随模式切换;插件禁用时禁用
- **`ClearScreenAction`**:生成 20 条空白通知(`hideBalloon` + `notify` 各 20 次)
- **`AboutAction`/`OpenPluginSettingsAction`/`OpenReaderModeSettingsAction`**:update 契约

> 注:`RemoveBookAction.actionPerformed` 在「未选书籍」时调用的是
> `Messages.showWarningDialog(Project, ...)` 而非 `(Component, ...)` 重载,断言须匹配实际重载。

### 2.2 扩展 `NotificationServiceImplTest`(8 → 20 测试)

新增活动阅读会话状态机(该类 42.6% → 74.1%):

- 经 `showChapterContent` 入口建立会话:书籍/章节 ID/标题/分页/当前页
- 空内容 → 报错且状态保持未激活
- `showNextPage` 前进页码;`showPrevPage` 回退到第一页
- **第一页再上一页** → 走 `chapterNavigator.navigateToLastPage(-1)` 分支
- **末页再下一页** → 走 `chapterNavigator.navigateChapter(1)` 分支
- 活动会话下 `navigateChapter` 不再短路,委托导航器
- `closeAllNotifications` 使当前通知 `expire()`
- 从进度仓库恢复页码:章节匹配时采用;越界时夹紧到最后一页
- 启用阅读进度时通知内容追加进度文案
- `dispose()` 可重入且安全

配套测试基础设施:
- `schedulers.io()` → trampoline,令 Rx 链在测试线程同步完成
- `application.invokeLater(Runnable)` 与 `invokeLater(Runnable, ModalityState)` 同步执行
- `ModalityState.defaultModalityState()` 返回 mock 实例(见「发现 3」)
- `NotificationDisplayManager.showReadingNotification` 返回 `RETURNS_SELF` mock 通知

## 三、结果

| 指标 | V29 | V30 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 59.53% | **63.70%**(5109/8021) | **+4.17pp** |
| 测试数 | 571 | **630** | +59 |
| `NotificationServiceImpl` | 42.6% | **74.1%** | **+31.5pp** |
| `ChapterContentViewer` | 41.3% | **87.3%** | +46.0pp |
| `ui/actions/*`(18 类) | ≈0% | 多数 60–100% | — |
| 覆盖率护栏 | 0.56 | **0.60** | +0.04 |

## 四、验证结果

- `./gradlew test` — ✅ 630 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **63.70%** > 阈值 0.60

## 五、遗留事项

- [ ] 三个 Dialog(`BookshelfDialog` 234 / `ChapterListDialog` 298 / `AddBookDialog` 76):
      构造即触发 `ExperimentalUI` 等深层平台服务查找,`@NotNull` 断言使 mock 环境不可行
- [ ] `ReaderPanel`(314):构造依赖 `SimpleToolWindowPanel` + MVI 全链路,同 Dialog 问题
- [ ] `DiagnosticAction$DiagnosticDialog`(128):同上
- [ ] `ProjectInitializationActivity`(66)/`PrivateReaderStartupActivity`(55):启动活动需平台上下文
- [ ] `NotificationBarModeService`(74.9%)/`ChapterNavigator`(77.7%):剩余为多分支编排与错误路径
- [ ] `FileBookRepository`(64.3%)/`UniversalParser`(66.8%):异常分支与 HTML 解析边界

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `ui/actions/ActionsTest.java` | **新增** | 47 个动作类测试(18 个动作类) |
| `service/impl/NotificationServiceImplTest.java` | 修改 | +12 个活动会话状态机测试(8→20) |
| `build.gradle` | 修改 | 覆盖率阈值 0.56 → 0.60 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V30 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/踩坑/文档清单更新至 V30 |