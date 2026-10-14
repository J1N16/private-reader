# Private Reader 优化方案 V26 —— ViewModel 意图与书籍 IO 辅助类补测

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V25.md](./OPTIMIZATION_V25.md)(V25 通知展示/书籍服务补测)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 补齐 `ReaderViewModel` 的意图分发分支,并直测 V13 拆分出的
> `BookIndexStore`/`BookDetailsIo` 两个包级 IO 辅助类。

---

## 一、背景

V25 后 LINE 达 51.11%,仍有可确定性测试的缺口:

| 类 | V25 覆盖 | 缺口 |
|----|---------|------|
| `ReaderViewModel` | 63.5% | `SelectBook`/`SearchBook` 等意图分支未测 |
| `BookIndexStore` | 69.1% | `updateAndSave`/`removeAndSave`/`clear` 未直测 |
| `BookDetailsIo` | 64.6% | 保存替换/损坏备份/整目录删除未测 |

三者均为纯逻辑/文件 IO,可在无 IDE 环境下确定性测试。

## 二、改动内容

### 2.1 新增 `ReaderViewModelIntentTest`(4 测试)

沿用既有 `awaitState` 模式,以内存 mock 驱动:

- `SelectBook`:重载该书目录并定位章节
- `SearchBook`:标题命中 / 作者命中(标题不匹配)/ 空关键字保留全部

> **发现(未改代码)**:`IReaderIntent.NavigateChapter` 意图已声明,但
> `ReaderViewModel.handleIntent` 未处理它(上一/下一章的导航另有通知栏路径)。
> 属既有的「未接线意图」,记录于遗留事项。

### 2.2 新增 `BookIndexStoreTest`(6 测试)

直测包级 `BookIndexStore`:缺文件读空、新增后读回、同书替换不重复、
`removeAndSave` 删除、`clear` 清空、多条往返。

### 2.3 新增 `BookDetailsIoTest`(6 测试)

直测包级 `BookDetailsIo`:缺文件返回 null、保存/读回往返、重复保存替换、
`backupCorrupted` 生成时间戳副本、缺失文件备份为 no-op、`deleteBookDirectory` 递归删除。

> 未改一行生产代码。

### 2.4 覆盖率护栏上调 0.47 → 0.48

实测 51.38%,新阈值留约 3.4pp 余量。

## 三、结果

| 指标 | V25 | V26 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 51.11% | **51.38%**(4069/7920) | +0.27pp |
| 测试数 | 458 | **474** | +16 |
| `ReaderViewModel` | 63.5% | **68.4%** | +4.9pp |
| `BookIndexStore` | 69.1% | 69.1% | —(已含直测,行数统计口径不变) |
| `BookDetailsIo` | 64.6% | 65.8% | +1.2pp |
| 覆盖率护栏 | 0.47 | **0.48** | +0.01 |

## 四、验证结果

- `./gradlew test` — ✅ 474 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **51.38%** > 阈值 0.48

## 五、遗留事项

- [ ] **`IReaderIntent.NavigateChapter` 未被 `handleIntent` 处理**(未接线意图)— 需确认是否应接线或移除
- [ ] `ReaderViewModel` 68.4%:剩余为 `AddBook`(经 `UniversalParser` 真实网络抓取,难确定性测试)与错误分支
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,需真实 IDE/UI 测试框架
- [ ] `NotificationServiceImpl` ~694 行,剩余为接口契约 API 与 `dispose`,优先级低

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `ui/mvi/ReaderViewModelIntentTest.java` | **新增** | 4 个意图分发/搜索测试 |
| `repository/impl/BookIndexStoreTest.java` | **新增** | 6 个索引 IO 测试 |
| `repository/impl/BookDetailsIoTest.java` | **新增** | 6 个详情 IO 测试 |
| `build.gradle` | 修改 | 覆盖率阈值 0.47 → 0.48 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V26 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V26 |