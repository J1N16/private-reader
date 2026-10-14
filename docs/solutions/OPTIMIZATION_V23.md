# Private Reader 优化方案 V23 —— 零覆盖工具类与设置聚合补测

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V22.md](./OPTIMIZATION_V22.md)(V22 数据库/网络可测性改造)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 清理一批**长期 0 覆盖**、逻辑简单却直接影响文件命名/异常归类/配置读取的类,
> 并顺带为可注入的 APP 服务补测,把总覆盖率推过 49%。

---

## 一、背景

V22 后 LINE 达 46.84%,但仍有若干**类级 0 覆盖**的「小而重要」目标未纳入:

| 类 | V22 覆盖 | 风险 |
|----|---------|------|
| `PrivateReaderConfig` | **0%** | 聚合全部设置读取,默认值分支无回归保护 |
| `StorageManager`(静态方法) | **0%** | `getSafeFileName`/`getCacheFileName` 决定磁盘文件名合法性 |
| `ExceptionHandler`(纯函数) | **0%** | 异常类型推断与文案映射无验证 |
| `ConfigDiagnosticTool` | **0%** | 配置目录/文件诊断与重置逻辑无验证 |
| `ReaderModeSwitcher` | **0%** | 模式切换的初始化/释放/项目分发无验证 |

这些类**无需大规模重构**即可测:静态纯函数可直接调用;依赖容器的部分抽出包级重载注入路径即可。

## 二、改动内容

### 2.1 生产代码:最小可测性开口(行为不变)

| 文件 | 改动 |
|------|------|
| `exception/ExceptionHandler.java` | `determineExceptionType`/`getErrorTitle`/`getErrorMessage` 由 `private` 放宽为**包级可见**(纯函数,无副作用) |
| `util/ConfigDiagnosticTool.java` | 新增 `configDir()` 与包级重载 `checkConfigDirectory(result, dir)`、`checkConfigFiles(result, dir)`、`resetConfigs(dir)`,公开方法委托之(路径可注入) |

其余类**未改一行生产代码**:`StorageManager` 的静态方法本就是公开纯函数;`PrivateReaderConfig` 与 `ReaderModeSwitcher` 已有公开的构造器注入。

### 2.2 新增测试

| 测试类 | 数量 | 覆盖 |
|--------|------|------|
| `PrivateReaderConfigTest` | 3 | 全依赖存在时聚合取值、通知模式→`NOTIFICATION`、全依赖为 null 时全部默认值与 `PANEL` |
| `StorageManagerTest` | 10 | 非法字符替换、连续下划线合并、中文字符保留、首尾点/下划线裁剪、空名回退、超长截断+hash、协议前缀剥离、长 URL hash、不同 URL 名字唯一、无协议分隔符 |
| `ExceptionHandlerTest` | 12 | 六种异常→`ExceptionType` 推断、标题分组(网络/解析/存储/其余)、文案分支、`UNKNOWN` 回退原文 |
| `ConfigDiagnosticToolTest` | 6 | `configDir` 路径、目录存在/可写报告、缺失目录自动创建、既有文件元数据、缺失目录安全处理、重置删除全部已知文件 / 空目录无操作 |
| `ReaderModeSwitcherTest` | 7 | 初始化订阅 TOPIC、无项目时安全终结、`modeChanged` 前后置守卫、`applyInitialModeForProject` 未初始化跳过、面板模式关闭通知、通知模式展示信息、`dispose` 幂等 |

> 隔离:静态纯函数直接调用;`PrivateReaderConfig`/`ReaderModeSwitcher` 用 mock 依赖 + `mockStatic` 拦截
> `ApplicationManager`/`ProjectManager`/`ToolWindowManager`;文件诊断用 `@TempDir`。
> **仅覆盖非 `LOG.error` 分支**(测试环境 `LOG.error` 会断言失败),故部分错误路径仍留空。

### 2.3 覆盖率护栏上调 0.42 → 0.45

实测 49.34%,新阈值留约 4.3pp 余量。

## 三、结果

| 指标 | V22 | V23 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 46.84% | **49.34%**(3907/7918) | **+2.50pp** |
| 测试数 | 389 | **428** | +39 |
| `PrivateReaderConfig` | 0% | **74.1%** | +74.1pp |
| `ReaderModeSwitcher` | 0% | **56.8%** | +56.8pp |
| `ExceptionHandler` | 0% | 24.4% | +24.4pp |
| `StorageManager` | 0% | 21.3% | +21.3pp |
| `ConfigDiagnosticTool` | 0% | 22.3% | +22.3pp |
| 覆盖率护栏 | 0.42 | **0.45** | +0.03 |

## 四、验证结果

- `./gradlew test` — ✅ 428 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **49.34%** > 阈值 0.45

## 五、遗留事项

- [ ] `ConfigDiagnosticTool` 22.3% / `ExceptionHandler` 24.4% / `StorageManager` 21.3%:剩余为
      容器的服务查询分支与 `LOG.error` 错误分支,受测试环境断言限制
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,需真实 IDE/UI 测试框架
- [ ] `NotificationServiceImpl` ~694 行,剩余为接口契约 API 与 `dispose`,优先级低

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `exception/ExceptionHandler.java` | 修改 | 三个纯函数放宽为包级可见 |
| `util/ConfigDiagnosticTool.java` | 修改 | 新增 `configDir()` 与目录可注入的包级重载 |
| `config/PrivateReaderConfigTest.java` | **新增** | 3 个聚合/默认值测试 |
| `storage/StorageManagerTest.java` | **新增** | 10 个文件命名纯函数测试 |
| `exception/ExceptionHandlerTest.java` | **新增** | 12 个类型推断/文案测试 |
| `util/ConfigDiagnosticToolTest.java` | **新增** | 6 个文件系统诊断测试 |
| `service/ReaderModeSwitcherTest.java` | **新增** | 7 个模式切换测试 |
| `build.gradle` | 修改 | 覆盖率阈值 0.42 → 0.45 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V23 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V23 |