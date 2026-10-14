# Private Reader 优化方案 V25 —— 通知展示与书籍服务补测

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V24.md](./OPTIMIZATION_V24.md)(V24 存储路径/书籍修复补测)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 补齐 V14 遗留的「真实通知创建路径未覆盖」与 `BookServiceImpl` 增删改分支,
> 将 `NotificationDisplayManager` 覆盖推至 100%。

---

## 一、背景

V24 后 LINE 达 50.29%,两处仍有明显缺口:

| 类 | V24 覆盖 | 缺口 |
|----|---------|------|
| `NotificationDisplayManager` | 32.8% | 既有测试只覆盖纯函数(标题/按钮可用性/HTML 清理);`showReadingNotification`/`showLoadingNotification`/`showError`/`showInfo` 的真实创建路径未测(V14 遗留) |
| `BookServiceImpl` | 40.7% | `getAllBooks`/`addBook`/`updateBook`/`saveReadingProgress` 及失败分支未测 |

两者均可通过既有公开入口 + `mockStatic` 通知门面测试,无需改生产代码。

## 二、改动内容

### 2.1 新增 `NotificationDisplayManagerDisplayTest`(6 测试)

`mockStatic(NotificationGroupManager)` + `mockStatic(Notifications.Bus)` 拦截通知门面,
以 `RETURNS_SELF` 的 mock `Notification` 验证:

- `showReadingNotification`:HTML 净化后内容、首页标题 + 页码后缀、`Notifications.Bus.notify` 调用
- 按钮装配:中间页启用 5 个动作(上/下页、上/下章、返回阅读器);单页且 `showButtons=false` 时 0 个动作
- `showLoadingNotification`:标题「加载中」
- `showError`/`showInfo`:类型分别为 `ERROR`/`INFORMATION`,`notify(null)`

### 2.2 新增 `BookServiceImplMutationTest`(9 测试)

以 mock 仓库注入 `BookServiceImpl`:

- `getAllBooks`:逐本发射 / 空仓库无发射
- `addBook`/`updateBook`:成功返回 true 并委托;仓库抛异常时错误透传
- `removeBook`:异常透传分支
- `saveReadingProgress`:委托 `updateProgress(book, chapterId, title, position)`
- `getChaptersSync`:未实现占位返回 null

> 未改一行生产代码。

### 2.3 覆盖率护栏上调 0.46 → 0.47

实测 51.11%,新阈值留约 4.1pp 余量。

## 三、结果

| 指标 | V24 | V25 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 50.29% | **51.11%**(4048/7920) | **+0.82pp** |
| 测试数 | 443 | **458** | +15 |
| `NotificationDisplayManager` | 32.8% | **100.0%** | +67.2pp |
| `BookServiceImpl` | 40.7% | **70.9%** | +30.2pp |
| 覆盖率护栏 | 0.46 | **0.47** | +0.01 |

## 四、验证结果

- `./gradlew test` — ✅ 458 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **51.11%** > 阈值 0.47

## 五、遗留事项

- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,需真实 IDE/UI 测试框架
- [ ] `ReaderViewModel` 63.5% / `UniversalParser` 66.8%:剩余为 UI 状态机与解析边界分支
- [ ] `NotificationServiceImpl` ~694 行,剩余为接口契约 API 与 `dispose`,优先级低

## 六、踩坑记录

- **Kotlin `final` 类的静态门面须 mock 其嵌套类**:`NotificationGroupManager` 是接口可直接 mockStatic;
  而未插桩环境中 `NotificationGroup` 为 `final`,只能用 mockStatic + mock 返回值绕过。
- **`Notifications.Bus` 是嵌套类**:直接 `mockStatic(Notifications.class)` 无效,须 `mockStatic(Notifications.Bus.class)`。
- **链式 API 的 mock 返回值**:`Notification.setTitle(...)` 返回 `this`(流式),mock 默认返回 null 会导致
  后续 `addAction` NPE,须用 `Mockito.RETURNS_SELF`。

## 七、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/impl/notification/NotificationDisplayManagerDisplayTest.java` | **新增** | 6 个通知创建路径测试 |
| `service/impl/BookServiceImplMutationTest.java` | **新增** | 9 个增删改/进度保存测试 |
| `build.gradle` | 修改 | 覆盖率阈值 0.46 → 0.47 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V25 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V25 |