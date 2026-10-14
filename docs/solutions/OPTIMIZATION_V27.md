# Private Reader 优化方案 V27 —— 诊断工具可测性改造与 ViewModel 意图接线

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V26.md](./OPTIMIZATION_V26.md)(V26 ViewModel 意图/书籍 IO 补测)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 用「环境查询宿主」抽象把 `ConfigDiagnosticTool` 从
> `ApplicationManager` 硬依赖中解耦并补测(22.3%→74.0%),
> 并接线 V26 发现的未处理意图 `IReaderIntent.NavigateChapter`。

---

## 一、背景

V26 后 LINE 51.38%,仍有两个高价值缺口:

| 类 | V26 覆盖 | 问题 |
|----|---------|------|
| `ConfigDiagnosticTool` | 22.3%(53/238) | 五个诊断方法直接静态调用 `ApplicationManager.getService(...)`,IntelliJ 容器外不可测 |
| `ReaderViewModel` | 68.4%(234/342) | `IReaderIntent.NavigateChapter` 已声明但 `handleIntent` 未接线(死意图) |

`ConfigDiagnosticTool` 238 行中约 185 行因「容器依赖」不可达,是当前单一最大的可确定性提升点。

## 二、改动内容

### 2.1 新增「环境查询宿主」抽象(生产行为不变)

- **`settings/DiagnosticsHosts.java`(新)**:把诊断所需的环境查询拆成 6 个最小接口
  (`PluginSettingsHost`/`ReaderSettingsHost`/`CacheSettingsHost`/`ReaderModeSettingsHost`/
  `NotificationSettingsHost`/`NotificationServiceHost`),外加聚合 `Host`(含
  `applyReaderMode`、`saveAllConfigs`)。
- **`settings/ApplicationManagerHost.java`(新)**:Host 的生产实现,把每个查询委托回
  `ApplicationManager.getService(...)`,逐字段适配为最小接口。**拆出前的直接调用逻辑与
  顺序完全保留**(含「服务缺失 → LOG.warn」与「聚合保存计数」语义)。
- **`util/ConfigDiagnosticTool.java`(改)**:`runDiagnostic()`/`forceSaveAllConfigs()`/
  `applyReaderMode()` 保留原公有签名,内部委托给注入式包级重载
  `runDiagnostic(Host)`/`forceSaveAllConfigs(Host)`/`applyReaderMode(Host)`;
  `checkConfigValues`/`checkReaderMode`/`checkNotificationMode` 改为接收 `Host` 并按接口取值。
  公有入口默认传入 `new ApplicationManagerHost()`,**行为对生产完全一致**。

### 2.2 接线 `IReaderIntent.NavigateChapter`(修复死意图)

- **`ui/mvi/ReaderViewModel.java`(改)**:新增 `NavigateChapter` 分支与
  `navigateChapter(int direction)` 实现——在已加载目录中按 `direction`(-1 上一章 / 1 下一章)
  定位并 `loadChapterContent`,越界/目录未就绪时仅告警(既有容错语义)。

### 2.3 新增测试 13 个

- **`util/ConfigDiagnosticToolHostTest`(11)**:以「全部可用 / 全部缺失 / 通知设置缺失」三种
  fake Host 覆盖值检查、阅读模式、通知栏模式、聚合保存、应用模式委托。
- **`settings/ApplicationManagerHostTest`(11)**:mockStatic `ApplicationManager` +
  mock 设置服务,逐字段校验适配器映射,并覆盖 `saveAllConfigs` 计数与
  `applyReaderMode`(多项目/无项目分支,mockStatic `ProjectManager`)。
- **`ui/mvi/ReaderViewModelIntentTest`(4→8)**:新增 `NavigateChapter` 前进/后退/首章越界/
  末章越界四个用例。

> **踩坑**:`forceSaveAllConfigs` 的异常回退与 `applyReaderMode` 的「设置缺失」分支会触发
> `LOG.error`,测试环境对 error 日志断言,故这两条错误分支**不纳入测试**(沿用既有约束)。

## 三、结果

| 指标 | V26 | V27 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 51.38% | **53.47%**(4283/8010) | **+2.09pp** |
| 测试数 | 474 | **501** | +27 |
| `ConfigDiagnosticTool` | 22.3% | **74.0%** | **+51.7pp** |
| `ApplicationManagerHost` | —(新增) | **96.9%** | — |
| `ReaderViewModel` | 68.4% | **69.0%** | +0.6pp |
| 覆盖率护栏 | 0.48 | **0.50** | +0.02 |

## 四、验证结果

- `./gradlew test` — ✅ 501 测试全通过(0 失败 0 错误)
- `./gradlew build` — ✅ JaCoCo LINE **53.47%** > 阈值 0.50

## 五、遗留事项

- [x] ~~`IReaderIntent.NavigateChapter` 未接线~~ — V27 已接线并补测
- [ ] `ConfigDiagnosticTool` 剩余 54 行:主要为错误分支(测试环境对 error 日志断言,不覆盖)
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,需真实 IDE/UI 测试框架
- [ ] `NotificationServiceImpl` 42.6%:接口契约 API 与 dispose,优先级低
- [ ] `FileBookRepository` 64.3% / `UniversalParser` 66.8%:剩余为异常分支与 HTML 解析边界

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `settings/DiagnosticsHosts.java` | **新增** | 诊断环境查询宿主接口 |
| `settings/ApplicationManagerHost.java` | **新增** | Host 的生产适配实现 |
| `util/ConfigDiagnosticTool.java` | 修改 | 服务查询改为注入式 Host |
| `ui/mvi/ReaderViewModel.java` | 修改 | 接线 `NavigateChapter` 意图 |
| `util/ConfigDiagnosticToolHostTest.java` | **新增** | 11 个诊断逻辑测试 |
| `settings/ApplicationManagerHostTest.java` | **新增** | 11 个适配器测试 |
| `ui/mvi/ReaderViewModelIntentTest.java` | 修改 | +4 个 NavigateChapter 测试 |
| `build.gradle` | 修改 | 覆盖率阈值 0.48 → 0.50 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V27 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V27 |