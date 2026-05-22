# Private Reader 项目优化计划

**创建日期**: 2026-05-22
**当前版本**: 2.4.1
**目标**: 简化架构、提升性能、消除技术债务

---

## 优化总览

| 优先级 | 任务 | 状态 | 预计影响 |
|--------|------|------|----------|
| P0-1 | 统一响应式框架 | ✅ 已完成 | 全项目 |
| P0-2 | DatabaseManager 连接池 | ✅ 已完成 | 数据库操作 |
| P0-3 | NotificationServiceImpl 拆分 | ✅ 已完成 | 代码量 -99行 |
| P1-1 | 删除 ServiceLocator/ServiceModule | ✅ 已完成 | 架构简化 |
| P1-2 | 删除重复 ReactiveTaskManager | ✅ 已完成 | 消除歧义 |
| P1-3 | 统一 BookStorage | ⏭️ 跳过 | 数据一致性（使用范围小） |
| P1-4 | 修复 UI 调度器 | ✅ 已完成 | 线程安全 |
| P1-5 | 构造器注入改造 | ⏭️ 跳过 | 初始化稳定（风险较大） |
| P2-1 | 简化监控基础设施 | ✅ 已完成 | 减少开销 |
| P2-2 | SettingsStorage 日志降级 | ✅ 已完成 | 启动性能 |
| P2-3 | 统一缓存管理 | ⏭️ 跳过 | 可维护性（CacheManager未被使用） |
| P2-4 | ReaderViewModel 线程安全 | ⏭️ 跳过 | UI 稳定（需要更复杂方案） |

---

## P0-1: 统一响应式框架

**目标**: 移除 Project Reactor，统一使用 RxJava3

**原因**:
- 项目同时依赖 `reactor-core` 和 `rxjava3`
- 需要 `RxJava3Adapter` 桥接两套 API
- RxJava3 在桌面/Android 社区更普及，文档更丰富

**改动范围**:
- `build.gradle`: 移除 `reactor-core` 和 `reactor-test` 依赖
- Repository 层: `Mono<T>` → `Single<T>`, `Flux<T>` → `Observable<T>`
- Service 层: 更新所有响应式链
- 删除 `RxJava3Adapter.java`

**涉及文件**:
- `repository/ReactiveChapterCacheRepository.java`
- `repository/impl/ReactiveChapterCacheRepositoryImpl.java`
- `repository/impl/ReactiveFileChapterCacheRepository.java`
- `service/ChapterService.java`
- `service/impl/ChapterServiceImpl.java`
- `storage/cache/ReactiveChapterPreloader.java`
- `async/RxJava3Adapter.java`

---

## P0-2: DatabaseManager 连接池

**目标**: 使用连接池替代每次新建连接

**当前问题**:
```java
// 每次调用都重新加载驱动 + 新建连接
Class.forName("org.sqlite.JDBC");
newConnection = DriverManager.getConnection(dbUrl);
```

**解决方案**:
- 使用 HikariCP 连接池
- 静态初始化块中加载驱动
- 配置合理的连接池参数（SQLite 单写入者特性）

**涉及文件**:
- `storage/DatabaseManager.java`
- `build.gradle` (添加 HikariCP 依赖)

---

## P0-3: NotificationServiceImpl 拆分

**目标**: 将 1596 行的臃肿类拆分为多个职责单一的类

**当前问题**:
- 4 组几乎完全相同的导航方法
- 保存进度逻辑重复 6 次
- 混合了通知显示、章节导航、进度管理等职责

**拆分方案**:
```
NotificationServiceImpl (精简到 ~400 行)
├── NotificationDisplayManager    // 通知显示逻辑
├── ChapterNavigationManager      // 章节导航逻辑
└── ReadingProgressManager        // 进度保存逻辑
```

**涉及文件**:
- `service/impl/NotificationServiceImpl.java` (重构)
- 新建 `service/impl/notification/NotificationDisplayManager.java`
- 新建 `service/impl/notification/ChapterNavigationManager.java`
- 新建 `service/impl/notification/ReadingProgressManager.java`

---

## P1-1: 删除 ServiceLocator/ServiceModule

**目标**: 移除未使用的自定义服务容器

**原因**:
- IntelliJ 已有成熟的服务机制
- 项目中 159 处直接使用 `ApplicationManager`
- `ServiceLocator` 几乎没有被调用

**涉及文件**:
- 删除 `config/ServiceLocator.java`
- 删除 `config/ServiceModule.java`
- 删除 `config/PrivateReaderConfig.java` (如仅用于聚合配置)
- 更新 `plugin.xml` 移除服务注册

---

## P1-2: 删除重复 ReactiveTaskManager

**目标**: 统一为 `async.ReactiveTaskManager`

**当前问题**:
- `task.ReactiveTaskManager` 委托给 `async.ReactiveTaskManager`
- 使用不同的调度器配置
- 造成使用混淆

**涉及文件**:
- 删除 `task/ReactiveTaskManager.java`
- 更新所有引用

---

## P1-3: 统一 BookStorage

**目标**: 合并 `BookStorage` 和 `BookFileStorage`

**当前问题**:
- `BookStorage` 使用 `PathManager.getSystemPath()/private-reader/books/`
- `BookFileStorage` 使用 `PathManager.getConfigPath()/PrivateReader/books.json`
- 可能导致数据不一致

**涉及文件**:
- 合并为 `storage/BookStorage.java`
- 删除 `storage/BookFileStorage.java`
- 更新所有引用

---

## P1-4: 修复 UI 调度器

**目标**: 移除自定义 UI 调度器，统一使用 IntelliJ EDT

**当前问题**:
```java
// 这不是 IntelliJ 的 EDT 线程！
this.uiScheduler = Schedulers.fromExecutorService(
    Executors.newSingleThreadExecutor(...)
);
```

**解决方案**:
- 删除 `ReactiveSchedulers.uiScheduler`
- 所有 UI 操作使用 `ApplicationManager.getApplication().invokeLater()`

**涉及文件**:
- `async/ReactiveSchedulers.java`
- 所有使用 `.subscribeOn(reactiveSchedulers.ui())` 的文件

---

## P1-5: 构造器注入改造

**目标**: 替代 `ensureServicesInitialized()` 延迟初始化模式

**当前问题**:
- 23 处 `ensureServicesInitialized()` 调用
- 每个方法调用都需要检查服务是否初始化
- 初始化失败的降级逻辑散布各处

**解决方案**:
```java
// 之前
public class NotificationServiceImpl {
    private BookService bookService;
    private void ensureServicesInitialized() {
        if (bookService == null) {
            bookService = ApplicationManager.getApplication().getService(BookService.class);
        }
    }
}

// 之后
public class NotificationServiceImpl {
    private final BookService bookService;
    public NotificationServiceImpl(BookService bookService) {
        this.bookService = bookService;
    }
}
```

---

## P2-1: 简化监控基础设施

**目标**: 移除不必要的常驻监控线程

**涉及文件**:
- `monitor/PerformanceMonitor.java` - 简化或移除
- `util/NetworkPerformanceMonitor.java` - 移除常驻线程
- `util/DiagnosticTool.java` - 保留但简化
- `async/ReactiveSchedulers.java` - 移除 5 分钟定时日志

---

## P2-2: SettingsStorage 日志降级

**目标**: 将诊断日志从 INFO 降级为 DEBUG

**涉及文件**:
- `storage/SettingsStorage.java`
- `settings/BaseSettings.java`

---

## P2-3: 统一缓存管理

**目标**: 清理未使用的缓存，统一缓存策略

**当前问题**:
- `CacheManager` 存在但未被使用
- 4 套独立的缓存机制缺乏协调

**涉及文件**:
- 删除 `cache/CacheManager.java` (如确认未使用)
- 简化 `ChapterServiceImpl` 的多层缓存

---

## P2-4: ReaderViewModel 线程安全

**目标**: 修复 BehaviorSubject 的竞态条件

**解决方案**:
```java
// 使用 toSerialized() 保证线程安全
private final BehaviorSubject<ReaderUiState> uiState = 
    BehaviorSubject.createDefault(ReaderUiState.initial()).toSerialized();
```

**涉及文件**:
- `ui/mvi/ReaderViewModel.java`

---

## 执行顺序

1. **P0-1** 统一响应式框架（影响最大，优先处理）
2. **P0-2** DatabaseManager 连接池
3. **P0-3** NotificationServiceImpl 拆分
4. **P1-1 ~ P1-5** 架构清理（可并行处理）
5. **P2-1 ~ P2-4** 细节优化

---

## 验证标准

每个优化完成后需要验证：
- [ ] `./gradlew build` 成功
- [ ] 所有测试通过
- [ ] 插件可正常启动
- [ ] 核心功能正常（添加书籍、阅读、翻页、进度保存）
