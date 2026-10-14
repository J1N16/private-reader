# Private Reader 优化方案 V31 —— 存储迁移可测性改造与模块/面板补测

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V30.md](./OPTIMIZATION_V30.md)(V30 UI 动作类与会话状态机)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 把 V24 的 `StorageManager` 改造再推进一步(注入旧版本目录以覆盖数据迁移),
> 并补测两个高价值小类(`PluginConfigurable`、`RepositoryModule`)。

---

## 一、背景

V30 后 LINE 63.70%,剩余可确定性覆盖的缺口:

| 类 | V30 覆盖 | 问题 |
|----|---------|------|
| `StorageManager` | 44.2% | V24 已能注入基础目录,但 `migrateFromOldLocation()` 内联调用 `PathManager.getConfigPath()`,迁移全流程仍不可测;`clearAllStorage` 亦未覆盖 |
| `PluginConfigurable` | 0/34(0%) | 与 V29 三个面板同类,但当时遗漏 |
| `RepositoryModule` | 1/33(3.0%) | 惰性服务解析与缓存逻辑未测 |

## 二、改动内容

### 2.1 `StorageManager` 注入旧版本目录(生产行为不变)

- **`storage/StorageManager.java`(改)**:新增包级构造器
  `StorageManager(Path baseStoragePath, Path legacyBasePath)` 承载全部初始化;
  原有 `StorageManager(Path)` 与默认构造器均改为委托之(默认仍取
  `PathManager.getConfigPath()`,生产行为不变)。
  `migrateFromOldLocation()` 中的 `Path.of(PathManager.getConfigPath(), "private-reader")`
  改为读取注入字段 `legacyBasePath`。
- **`StorageManagerLifecycleTest`(新,11)**:三参构造器让「备份 → 复制
  books/cache/settings」的迁移全流程在 `@TempDir` 下确定性可测,另覆盖
  「目标已有 index.json 时跳过迁移」「旧 cache/settings 缺失时跳过对应复制」
  「时间戳备份目录」以及 `getSettingsStorage`/`getReactiveChapterCacheRepository`
  的服务委派与 `clearAllStorage` 的清理+目录重建。

### 2.2 `PluginConfigurableTest`(新,7)

面板 34 行 → 94.1%:`getDisplayName`、`createComponent` 初值播种、`isModified`
匹配/变更、`apply` 状态变化时发布事件 / 无变化时跳过、`reset` 还原。

### 2.3 `RepositoryModuleTest`(新,6)

模块 33 行 → 90.9%:四类 Repository 的解析、服务缺失时返回 null、
**惰性初始化只查询一次服务容器**(缓存于字段)、`getInstance()` 的
「服务可用 / 缺失时新建」两条分支、部分服务可用时各自独立解析。

## 三、结果

| 指标 | V30 | V31 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 63.70% | **65.18%**(5231/8025) | **+1.48pp** |
| 测试数 | 630 | **655** | +25 |
| `StorageManager` | 44.2% | **85.2%** | **+41.0pp** |
| `PluginConfigurable` | 0% | **94.1%** | **+94.1pp** |
| `RepositoryModule` | 3.0% | **90.9%** | **+87.9pp** |
| 覆盖率护栏 | 0.60 | **0.61** | +0.01 |

## 四、验证结果

- `./gradlew test` — ✅ 655 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **65.18%** > 阈值 0.61

## 五、遗留事项

- [ ] `StorageManager` 剩余 21 行:IO 失败/服务缺失等 `LOG.error` 分支
- [ ] `ReaderModeSwitcher` 56.8%:模式切换编排,剩余为多分支与通知路径
- [ ] 三个 Dialog(`BookshelfDialog` 234 / `ChapterListDialog` 298 / `AddBookDialog` 76)、
      `ReaderPanel`(314)、`DiagnosticAction$DiagnosticDialog`(128):构造期深层平台服务
      (`ExperimentalUI`)`@NotNull` 断言,当前 mock 环境不可测
- [ ] `ProjectInitializationActivity`(66)/`PrivateReaderStartupActivity`(55):启动活动需平台上下文
- [ ] `FileBookRepository`(64.3%)/`UniversalParser`(66.8%)/`ExceptionHandler`(47.9%):异常与解析边界

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `storage/StorageManager.java` | 修改 | 新增三参构造器注入旧版本目录 |
| `storage/StorageManagerLifecycleTest.java` | **新增** | 11 个迁移/清理/服务委派测试 |
| `ui/settings/PluginConfigurableTest.java` | **新增** | 7 个面板测试(→94.1%) |
| `repository/RepositoryModuleTest.java` | **新增** | 6 个模块解析测试(→90.9%) |
| `build.gradle` | 修改 | 覆盖率阈值 0.60 → 0.61 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V31 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/踩坑/文档清单更新至 V31 |