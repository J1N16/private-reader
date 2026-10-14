# Private Reader 项目优化方案 V22 —— 数据库管理器与网络工具可测性改造

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V21.md](./OPTIMIZATION_V21.md)(V21 监控器与缓存仓储补测)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 承接 V21 遗留的两项「需重构后可测」清单——`DatabaseManager` 与 `NetworkUtils`,
> 消除依赖 IntelliJ 容器 / 全局静态状态的测试障碍,补测迁移与真实 HTTP 路径。

---

## 一、背景

V21 补测后 LINE 达 45.88%,但两处核心仍低覆盖且**无法直接测试**:

| 类 | V21 覆盖 | 障碍 |
|----|---------|------|
| `DatabaseManager` | 25.4% | 构造器从 `DatabaseConstants` 读**全局** dbUrl,并依赖由 IntelliJ 容器解析的 `StorageRepository`;初始化护栏为**静态** flag;迁移逻辑内联 `ApplicationManager.getService` 与 `PropertiesComponent.getInstance` |
| `NetworkUtils` | 28.4% | 既有测试仅覆盖非法/不可达输入的快速失败;`isUrlAccessible` 成功分支、`checkNetworkSpeed` 无确定性覆盖 |

二者均是「构造/方法内直接触达容器或全局状态」导致的不可测,而非逻辑复杂。

## 二、改动内容

### 2.1 `DatabaseManager`:注入式构造 + 实例级护栏 + 迁移逻辑外提

`storage/DatabaseManager.java`:

1. 新增包级构造器 `DatabaseManager(String dbUrl)`,生产默认构造器委托
   `DatabaseConstants.getDatabaseUrl()`(**生产行为不变**);
2. 初始化护栏 `databaseInitialized` 由 `static` 改为**实例字段**——APP 级服务通常单实例,
   语义等价,但允许测试注入不同 dbUrl 各自初始化;
3. 目录创建改由注入 URL 推导(`DatabaseConstants.pathFromUrl(dbUrl)`),不再解析容器服务;
4. 迁移核心逻辑外提为包级重载
   `runMigrationIfNeeded(PropertiesComponent, StorageRepository, ReadingProgressRepository)`,
   无参公开方法仅负责解析依赖后委托。

### 2.2 `DatabaseConstants.pathFromUrl`(纯函数)

`storage/DatabaseConstants.java` 新增 `public static Path pathFromUrl(String dbUrl)`,
剥离 `jdbc:sqlite:` 前缀得到文件路径,供目录创建与诊断使用,零外部依赖。

### 2.3 新增测试

| 测试类 | 数量 | 覆盖 |
|--------|------|------|
| `DatabaseManagerCoreTest` | 8 | 注入 URL 建库/建表、`getConnection` 可用、`dispose` 幂等;JSON→SQLite 迁移:有效条目写入并回填标记、目录名与 JSON id 不一致以 JSON 为准、无时间戳跳过但仍置标记、已完成标记短路、源路径缺失早退 |
| `DatabaseConstantsTest` | 2 | `pathFromUrl` 剥离前缀 / 裸路径 |
| `NetworkUtilsHttpTest` | 7 | 本地 `HttpServer`:200/302 视作可访问、404/500 不可访问、显式超时、`checkNetworkSpeed` 成功返回非负 / 非法 URL 返回 -1 |

> 隔离:`@TempDir` 独立 SQLite 文件;**仅覆盖非 `LOG.error` 分支**(测试环境 `LOG.error` 会断言失败);
> 网络路径用 JDK `HttpServer`,不依赖外网。

### 2.4 覆盖率护栏上调 0.40 → 0.42

实测 46.84%,新阈值留约 4.8pp 余量。

## 三、结果

| 指标 | V21 | V22 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 45.88% | **46.84%**(3709/7919) | **+0.96pp** |
| 测试数 | 372 | **389** | +17 |
| `DatabaseManager` | 25.4% | **71.2%** | +45.8pp |
| `NetworkUtils` | 28.4% | **39.8%** | +11.4pp |
| 覆盖率护栏 | 0.40 | **0.42** | +0.02 |

## 四、验证结果

- `./gradlew test` — ✅ 389 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **46.84%** > 阈值 0.42

## 五、遗留事项

- [ ] `DatabaseConstants` 15.4%:`getDatabasePath`/`getDatabaseUrl` 依赖 IntelliJ 容器与
      fallback 目录,需进一步注入 `StorageRepository` 工厂
- [ ] `NetworkUtils` 39.8%:`isNetworkAvailable` 并行探测与 `getBestHost` 依赖外网,
      需可替换的探测适配器
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,需真实 IDE/UI 测试框架
- [ ] `NotificationServiceImpl` ~694 行,剩余为接口契约 API 与 `dispose`,优先级低

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `storage/DatabaseManager.java` | 修改 | 包级注入构造器 + 实例级护栏 + URL 推导目录 + 迁移逻辑外提 |
| `storage/DatabaseConstants.java` | 修改 | 新增 `pathFromUrl` 纯函数 |
| `storage/DatabaseManagerCoreTest.java` | **新增** | 8 个建库/连接/迁移测试 |
| `storage/DatabaseConstantsTest.java` | **新增** | 2 个纯函数测试 |
| `util/NetworkUtilsHttpTest.java` | **新增** | 7 个本地 HTTP 确定性测试 |
| `build.gradle` | 修改 | 覆盖率阈值 0.40 → 0.42 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V22 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V22 |