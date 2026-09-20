# Private Reader 项目优化方案 V12 —— 构造器注入改造

> 创建日期: 2026-09-18
> 项目版本: 3.0.0
> 配套: [OPTIMIZATION_V11.md](../OPTIMIZATION_V11.md)(V11 拆分通知服务/不可变状态/正则预编译)
> 实施状态: 已完成 ✅(2026-09-18)
> 目标: 消除核心服务类的散落 `ApplicationManager.getApplication().getService(...)` 硬编码,
>       改为 IntelliJ Platform 官方构造器注入,提升可测试性与依赖可见性

---

## 一、背景

扫描发现 **41 个文件**存在 `getService` 硬编码调用(其中核心服务类构造器内取依赖、
方法内按需取用混存)。`service` 接口层 0% 覆盖的根因之一就是服务通过容器硬编码获取,
无法在测试中注入 mock。

IntelliJ Platform 自 2020.1 起支持**构造器注入**:服务实现类若存在带参构造器,
容器自动选择**参数最多的 public 构造器**,并从服务容器解析每个参数类型。这是官方
推荐的服务依赖管理方式。

## 二、改造范围与决策

### 2.1 已改造(8 个类,消除构造器/字段级 getService)

| 类 | 注入依赖 | 改造方式 |
|----|---------|---------|
| `BookServiceImpl` | BookRepository, ReadingProgressRepository | 无参构造委托给有参构造 |
| `ChapterServiceImpl` | ReactiveChapterCacheRepository, BookRepository | 无参委托 + 删除 `ensureServicesInitialized`(9 处调用)与惰性初始化方法 |
| `NotificationServiceImpl` | BookService, ChapterService, NotificationReaderSettings, ReactiveChapterPreloader, ChapterChangeManager | 无参委托 + 5 字段 final + 删除 `ensureServicesInitialized`(13 处调用与实现) |
| `NotificationBarModeService` | ReaderModeSettings, NotificationService, ChapterService, BookService, ReadingProgressRepository, NotificationReaderSettings | 无参委托,lambd 内按需获取改为字段引用 |
| `ReaderModeSwitcher` | NotificationService, ReaderModeSettings, NotificationReaderSettings | 无参委托,保留防御性初始化逻辑 |
| `PrivateReaderConfig` | 5 个 Settings 类 | 无参委托 |
| `FileBookRepository` | StorageRepository | 无参委托(保留 SwingUtilities 后台清理) |
| `ReactiveChapterCacheRepositoryImpl` | CacheSettings | 无参委托,新增包级测试构造器 |

**模式**:无参构造(供容器/框架调用)委托给带参构造;字段全部 `final`;
`ensureServicesInitialized` 惰性机制整体删除 —— 依赖关系在构造时即确定,不可能出现
"字段此时为 null、方法内部才补取"的隐蔽时序。

### 2.2 保留 getService 的场景(设计上正确,不强行改造)

1. **静态工具类**:`getInstance()` 单例访问(`DatabaseManager`/`SettingsStorage`/
   `StorageManager`/`NetworkPerformanceMonitor`)——工具类本就不该持有字段。
2. **UI 框架实例化类**:`StartupActivity`/`AnAction`/`DialogWrapper`/`Configurable`
   由 IntelliJ 反射实例化,不能提供构造参数(或仅在 `actionPerformed` 中按需取用)。
3. **方法内惰性获取**:`NotificationServiceImpl` 中 `SqliteReadingProgressRepository`
   的 4 处——其中 `createRepositoryIfNeeded ? getService(...) : getServiceIfCreated(...)`
   是**刻意惰性语义**(不强制初始化数据库,未创建则跳过保存),改为构造注入会改变启动行为。
4. **静态辅助方法**:`ReactiveChapterPreloader`/`ConfigDiagnosticTool`/`PluginUtil`/
   `ExceptionHandler` 在方法内取用——均为一次性诊断/工具逻辑,无状态可注入。

## 三、循环依赖分析

构造器注入的最大风险是循环依赖。全库服务依赖图核对:

```
NotificationServiceImpl ──> BookService, ChapterService, ReactiveChapterPreloader, ...
BookServiceImpl ──> BookRepository, ReadingProgressRepository
ChapterServiceImpl ──> ReactiveChapterCacheRepository, BookRepository
ReactiveChapterCacheRepositoryImpl ──> CacheSettings, (方法内) StorageRepository
FileBookRepository ──> StorageRepository, (方法内) ChapterService
NotificationBarModeService ──> NotificationService(impl), ReaderModeSettings, ...
ReaderModeSwitcher ──> NotificationService, ReaderModeSettings, ...
```

- 构造器注入仅覆盖**构造期**依赖,`FileBookRepository → ChapterService`(方法内获取)
  `BookServiceImpl → ChapterService`(方法内兜底)均为运行时依赖,不参与构造循环,安全。
- 无 `A 构造 → B 构造 → A 构造` 的环,全部可注入。

## 四、验证结果

- `./gradlew clean build` — ✅ 全链路通过(249 测试 + JaCoCo)
- LINE 覆盖率 **31.48%**(2414/7692),与 V11 持平(重构不改变行为)
- 测试 249(与改造前一致,`BookServiceImplTest`/`ChapterServiceImplTest` 等
  既有有参构造测试天然验证注入构造器)
- 变更:8 个文件,152 insertions / 141 deletions

## 五、收益

1. **service 层可测性**:核心服务字段 final + 可注入,为后续补测
   `service/impl`(现 14.4%)扫清障碍——测试不需要 mock 服务容器。
2. **依赖显式化**:构造器签名即依赖清单,`privateChapterService` 这类"要不要、
   何时取"的隐式决策消失。
3. **消除时序隐患**:`ensureServicesInitialized` 的"惰性半初始化"状态机整个移除。
4. **启动更快**:避免多处重复 `getService` 容器查找(虽开销小,但消除了冷启动
   在多个服务间相互触发的级联初始化)。

## 六、遗留事项

- [ ] `service/impl` 补测:现在可构造器注入 mock,优先覆盖 `ChapterServiceImpl`
      (缓存过期/网络失败回退/无回退抛错)与 `NotificationServiceImpl` 导航分支
- [ ] UI 层(action/dialog)仍为框架实例化,可考虑用 `AnAction.getEventProject()`
      懒注入或保持现状;建议先保 service 层
- [ ] `RepositoryModule`(仓库聚合)仍是 getService 风格,可后续评估是否保留——
      它本身是"服务的服务",作为聚合点是合理设计
- [ ] 若覆盖率达到 35%+,阈值上调至 0.32(延续 V9/V11 建议)