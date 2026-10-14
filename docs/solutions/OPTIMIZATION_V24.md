# Private Reader 优化方案 V24 —— 存储路径逻辑与书籍修复路径补测

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V23.md](./OPTIMIZATION_V23.md)(V23 零覆盖工具类补测)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 承接 V23 遗留的 `StorageManager`(21.3%)与 `FileBookRepository`(51.8%),
> 通过注入 basePath 打通存储路径逻辑,并补齐书籍阅读位置恢复/修复分支,把总覆盖率推过 50%。

---

## 一、背景

V23 后 LINE 达 49.34%,剩余较大缺口集中在两处「核心但低覆盖」的类:

| 类 | V23 覆盖 | 缺口 |
|----|---------|------|
| `StorageManager` | 21.3% | 实例方法(路径 getter、目录创建/获取、迁移)全部 0 覆盖——构造器硬编码 `user.home` 并调用 `PathManager` |
| `FileBookRepository` | 51.8% | `restoreLastReadingPosition` 的恢复/修正分支、`repairMissingReadingContent`、`getAllBooks(true)` 未覆盖 |

## 二、改动内容

### 2.1 `StorageManager`:注入 basePath 构造器(生产行为不变)

`storage/StorageManager.java`:

```java
public StorageManager() {
    this(Path.of(System.getProperty("user.home"), ".private-reader"));
}

StorageManager(Path baseStoragePath) { ... }  // 包级,测试注入
```

所有路径(books/cache/settings/backup)均由 basePath 解析,测试可在 `@TempDir` 下验证,
不再触碰真实用户目录。

### 2.2 新增测试

| 测试类 | 数量 | 覆盖 |
|--------|------|------|
| `StorageManagerPathTest` | 6 | 构造器创建完整目录结构、五类路径 getter、`createBookDirectory`、`getBookDirectory` 按需创建/复用已存在目录、静态 `getSafeFileName` |
| `FileBookRepositoryRepairTest` | 9 | `getBook` 对 null/空 id 短路;`restoreLastReadingPosition`:标题→章节 id、id→章节索引、负数位置归零、非法页码归 1、补全最后阅读时间、保留已有有效索引;`repairMissingReadingContent`:修复并回写 / 健康书籍返回 0 / 无书籍返回 0;`getAllBooks(true)` 加载详情 |

> 隔离:`@TempDir` 注入 basePath;复用 `FileBookRepository` 既有 `FakeStorageRepository` 模式。
> 详情文件 `url` 置空以规避需要 IntelliJ 容器的章节网络补充分支(避免触发 `LOG.error`)。

### 2.3 覆盖率护栏上调 0.45 → 0.46

实测 50.29%,新阈值留约 4.3pp 余量。

## 三、结果

| 指标 | V23 | V24 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 49.34% | **50.29%**(3983/7920) | **+0.95pp** |
| 测试数 | 428 | **443** | +15 |
| `StorageManager` | 21.3% | **44.2%** | +22.9pp |
| `FileBookRepository` | 51.8% | **64.3%** | +12.5pp |
| 覆盖率护栏 | 0.45 | **0.46** | +0.01 |

## 四、验证结果

- `./gradlew test` — ✅ 443 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **50.29%** > 阈值 0.46

## 五、遗留事项

- [ ] `StorageManager` 44.2%:剩余为 `clearAllStorage`/`getReactiveChapterCacheRepository`/
      `migrateFromOldLocation`(依赖 `PathManager` 与 IntelliJ 容器),需进一步注入依赖
- [ ] `FileBookRepository` 64.3%:剩余为章节网络补充与损坏-恢复的重试计数分支(需 mockStatic 容器)
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,需真实 IDE/UI 测试框架
- [ ] `NotificationServiceImpl` ~694 行,剩余为接口契约 API 与 `dispose`,优先级低

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `storage/StorageManager.java` | 修改 | 新增包级注入 basePath 构造器(默认委托 user.home) |
| `storage/StorageManagerPathTest.java` | **新增** | 6 个路径/目录管理测试 |
| `repository/impl/FileBookRepositoryRepairTest.java` | **新增** | 9 个阅读位置恢复/修复测试 |
| `build.gradle` | 修改 | 覆盖率阈值 0.45 → 0.46 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V24 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V24 |