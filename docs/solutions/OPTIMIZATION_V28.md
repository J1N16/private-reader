# Private Reader 优化方案 V28 —— 存储/异常恢复可测性改造与工具类补测

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V27.md](./OPTIMIZATION_V27.md)(V27 诊断工具解耦)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 对 `FileStorageRepository` 与 `ExceptionHandler` 做与 V24/V27 同构的
> 可注入化改造并补测(0%→81.2%、24.4%→47.9%),
> 同时补齐 `SafeHttpRequestExecutor` 统计 API 与 `NetworkUtils` 探测分支。

---

## 一、背景

V27 后 LINE 53.47%,继续排查「硬编码全局路径 / 容器查询」两类不可测模式:

| 类 | V27 覆盖 | 问题 |
|----|---------|------|
| `FileStorageRepository` | **0/78(0%)** | 构造器硬编码 `System.getProperty("user.home")`,实例化即写真实 `~/.private-reader` |
| `ExceptionHandler` | 24.4% | 恢复分支直接调用 `NetworkUtils`/`ApplicationManager.getService`/通知组,容器外不可达 |
| `SafeHttpRequestExecutor` | 59.7% | 性能统计 API(`getPerformanceStats`/`resetPerformanceStats`)与统计累加分支未测 |
| `NetworkUtils` | 39.8% | 并行探测缓存写入、异步探测、`getBestHost`/`checkNetworkSpeed` 未测 |

## 二、改动内容

### 2.1 `FileStorageRepository` 注入基础路径(生产行为不变)

- **`repository/impl/FileStorageRepository.java`(改)**:新增包级 `FileStorageRepository(Path baseStoragePath)`
  构造函数承载全部初始化;原默认构造器改为 `this(Path.of(user.home, ".private-reader"))` 委托之。
  目录结构、`getSafeFileName`、`getCacheFileName`、`clearAllStorage` 逻辑零改动。
- **`FileStorageRepositoryTest`(新,13)**:`@TempDir` 下覆盖目录结构创建、五个路径 getter、
  书籍目录创建幂等、不安全 ID 净化、文件名通配/超长哈希、缓存名编码稳定性、
  全量清理(含递归、保留 backup)。

### 2.2 `ExceptionHandler` 注入恢复宿主(生产行为不变)

- **`exception/ExceptionHandler.java`(改)**:新增包级接口 `RecoveryHost`
  (`isNetworkAvailable`/`storageBasePath`/`storageCachePath`/`showNotification`)与生产实现
  `DEFAULT_RECOVERY_HOST`(网络 → `NetworkUtils`、存储 → `StorageManager` 服务、通知 → IntelliJ 通知组)。
  `handleRecovery` 增加包级注入重载,原私有方法改为接收 Host。**恢复决策与文案与拆分前完全一致**。
- **`ExceptionHandlerRecoveryTest`(新,10)**:以 fake Host 覆盖网络恢复(可用/不可用/连接拒绝)、
  存储恢复(可写/不可写/缓存目录重建/服务缺失/缓存路径为空)、解析恢复、未知类型不触发。

> **踩坑**:`handle(...)` 首行即 `LOG.error`,测试环境断言错误日志;故本测试只调用包级
> `handleRecovery(...)`,不经 `handle(...)`。

### 2.3 工具类补测

- **`SafeHttpRequestExecutorTest`(改,+4)**:已验证请求后统计累加、失败统计、`resetPerformanceStats`、
  空统计文案。用例结束重置全局计数器避免相互污染。
- **`NetworkUtilsProbeTest`(新,11)**:缓存未命中完整探测路径、异步探测、本地 HttpServer 测速、
  `getBestHost` 契约、`isUrlAccessible` 双重重载、不可达主机快速失败、缓存过期/重置。

> 既有 `NetworkUtilsTest`/`NetworkUtilsHttpTest` 继续保留;`CHECK_HOSTS` 为私有常量,
> 并行探测结果依赖外网,故仅断言「缓存写入」这一确定性后果。

## 三、结果

| 指标 | V27 | V28 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 53.47% | **55.23%**(4430/8021) | **+1.76pp** |
| 测试数 | 501 | **541** | +40 |
| `FileStorageRepository` | 0% | **81.2%** | **+81.2pp** |
| `ExceptionHandler` | 24.4% | **47.9%** | +23.5pp |
| `NetworkUtils` | 39.8% | **81.8%** | +42.0pp |
| `SafeHttpRequestExecutor` | 59.7% | **71.2%** | +11.5pp |
| 覆盖率护栏 | 0.50 | **0.52** | +0.02 |

## 四、验证结果

- `./gradlew test` — ✅ 541 测试全通过(0 失败 0 错误)
- `./gradlew build` — ✅ JaCoCo LINE **55.23%** > 阈值 0.52

## 五、遗留事项

- [ ] `ExceptionHandler` 剩余:`handle(...)` 本体与通知失败分支因 `LOG.error` 不可测
- [ ] `SafeHttpRequestExecutor` 剩余:中断/超时/执行异常分支(需构造线程池级场景)
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,需真实 IDE/UI 测试框架
- [ ] `NotificationServiceImpl` 42.6%:接口契约 API 与 dispose,优先级低
- [ ] `UniversalParser` 66.8% / `FileBookRepository` 64.3%:异常分支与 HTML 解析边界

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `repository/impl/FileStorageRepository.java` | 修改 | 新增包级 `(Path)` 构造器,默认构造器委托 |
| `exception/ExceptionHandler.java` | 修改 | 新增 `RecoveryHost` 抽象与注入式恢复重载 |
| `util/SafeHttpRequestExecutorTest.java` | 修改 | +4 个性能统计测试 |
| `repository/impl/FileStorageRepositoryTest.java` | **新增** | 13 个路径/文件名/清理测试 |
| `exception/ExceptionHandlerRecoveryTest.java` | **新增** | 10 个恢复分支测试 |
| `util/NetworkUtilsProbeTest.java` | **新增** | 11 个探测/测速/异步测试 |
| `build.gradle` | 修改 | 覆盖率阈值 0.50 → 0.52 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V28 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V28 |