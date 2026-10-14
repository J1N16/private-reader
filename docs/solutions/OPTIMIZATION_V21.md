# Private Reader 项目优化方案 V21 —— 监控器可测性改造与缓存仓储主干补测

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V20.md](./OPTIMIZATION_V20.md)(V20 章节内容展示流水线拆分)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 承接 V11–V20 遗留的「高风险核心覆盖偏低」,把重心从「拆分已拆组件」转向
> **可测性改造 + 数据正确性路径补测**,并把覆盖率护栏上调到真正具备回归约束力的水平。

---

## 一、背景

V16–V20 连续五轮拆分 `NotificationServiceImpl`(1186 → 694 行)后,该服务剩余部分基本为
接口契约 API(分页/状态读取/通知展示)与 `dispose` 生命周期,**继续拆分的边际收益在下降**。

同时,JaCoCo 报告显示真正的风险集中在数据正确性所在的核心类,且长期零/低覆盖:

| 类 | V20 覆盖 | 问题 |
|----|---------|------|
| `NetworkPerformanceMonitor` | **0.8%**(1/123) | 构造器无条件向**全局**调度器注册 5 分钟周期任务,测试难以安全实例化 |
| `ReactiveChapterCacheRepositoryImpl` | **30.7%**(85/277) | 读写/清理主干(CRUD)几乎未测 |
| `DatabaseManager` | 25.4%(33/130) | 迁移/连接路径依赖静态 flag 与 IntelliJ 容器 |
| `NetworkUtils` | 28.4%(25/88) | 已通过包级辅助方法覆盖缓存语义,增量空间有限 |

覆盖率护栏停留在 **0.35**,而实测已达 42.44%,余量 7.44pp——护栏形同虚设,无法阻止回退。

## 二、改动内容

### 2.1 P0-1:可测性改造 `NetworkPerformanceMonitor`(无行为变更)

`util/NetworkPerformanceMonitor.java`:

1. 新增包级可见构造器 `NetworkPerformanceMonitor(ScheduledExecutorService scheduler)`,
   生产默认构造器委托 `AppExecutorUtil.getAppScheduledExecutorService()`;
2. `scheduler == null` 时不注册周期任务(测试可零副作用实例化);
3. 周期参数抽为具名常量 `REPORT_INITIAL_DELAY_MINUTES` / `REPORT_PERIOD_MINUTES`(各 5 分钟);
4. `logPerformanceReport()` 由 `private` 放宽为**包级可见**,便于直接触发任务体。

> 生产路径行为完全不变:默认构造器仍向全局调度器注册同一周期任务。

### 2.2 P0-2:补齐 `ReactiveChapterCacheRepositoryImpl` 读写/清理主干

新增 `ReactiveChapterCacheRepositoryImplCrudTest`(12 测试),覆盖此前完全未测的路径:

- 写入 → 内存命中读取、落盘校验;空内容不写入
- 缺文件 → `empty`;过期 → 主读取 `empty`,**备用读取忽略过期**
- 按书籍清理、全量清理(并重建根目录)
- 过期缓存清理(**连带删除空目录**)、按书籍过期清理(保留未过期文件)
- 容量超限 → 按最旧优先删除
- `getCacheDirPath`、`dispose`(清内存缓存,文件仍可读)

隔离方式沿用 V15 既有测试:io 调度器替换为 trampoline、`mockStatic(ApplicationManager)`
拦截 `StorageRepository` 缓存路径。

### 2.3 新增 `NetworkPerformanceMonitorTest`(11 测试)

覆盖:构造器调度注册/`null` 跳过、开始/成功/失败/超时四类记录累加、报告(空/非空)、
简化统计、错误类型与域名维度聚合、最小/最大响应时间边界、`resetStats`、
`dispose` 取消任务且**幂等**、`logPerformanceReport` 静默分支。

> 通过包级构造器注入 mock 调度器,避免测试污染 `AppExecutorUtil` 全局定时器。

### 2.4 P1:覆盖率护栏上调 0.35 → 0.40

实测 45.88%,新阈值留约 5.8pp 余量,恢复回归约束力。

## 三、结果

| 指标 | V20 | V21 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 42.44% | **45.88%**(3628/7907) | **+3.44pp** |
| 测试数 | 349 | **372** | +23 |
| `NetworkPerformanceMonitor` | 0.8% | **93.0%** | +92.2pp |
| `ReactiveChapterCacheRepositoryImpl` | 30.7% | **76.5%** | +45.8pp |
| 覆盖率护栏 | 0.35 | **0.40** | +0.05 |

## 四、验证结果

- `./gradlew test` — ✅ 372 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **45.88%** > 阈值 0.40
- `./gradlew buildPlugin` — ✅ 产出 `private-reader-3.1.1.zip`

## 五、遗留事项

- [ ] `DatabaseManager` 仍 25.4%:迁移/初始化路径依赖静态 `databaseInitialized` flag 与
      IntelliJ `PropertiesComponent`,需重构为可注入依赖后再补测
- [ ] `NetworkUtils` 28.4%:真实并行探测依赖外网,`getBestHost`/`isNetworkAvailableAsync`
      需网络打桩框架才能确定性覆盖
- [ ] `NotificationServiceImpl` 仍有 ~694 行,剩余为接口契约 API 与 `dispose`,优先级低
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,需真实 IDE/UI 测试框架
- [ ] `storage` 主体、`initialization` 为低覆盖保留方向

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `util/NetworkPerformanceMonitor.java` | 修改 | 注入式调度器构造器 + 具名常量 + 任务体包级可见 |
| `util/NetworkPerformanceMonitorTest.java` | **新增** | 11 个记录/报告/生命周期测试 |
| `repository/impl/ReactiveChapterCacheRepositoryImplCrudTest.java` | **新增** | 12 个缓存 CRUD/清理测试 |
| `build.gradle` | 修改 | 覆盖率阈值 0.35 → 0.40 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V21 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V21 |