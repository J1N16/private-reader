# Private Reader 项目优化方案 V15 —— 缓存过期设置失效修复与启动清理落地

> 创建日期: 2026-10-09
> 项目版本: 3.0.0
> 配套: [OPTIMIZATION_V14.md](./OPTIMIZATION_V14.md)(V14 通知栏补测/注入收尾/展示层回调化)
> 实施状态: 已完成 ✅(2026-10-09)
> 目标: 修复用户反馈的「缓存的章节没有按时清除」——定位为缓存设置字段双份 + 启动清理未执行两类缺陷

---

## 一、问题现象

用户反馈:**缓存的章节没有按时清除**。在设置界面把「缓存过期时间」调小、或保持默认 7 天,过期章节仍然长期驻留磁盘。

## 二、根因分析

排查后确认是**两个独立缺陷叠加**,任一都会导致清理失效:

### 2.1 缺陷一:缓存设置存在两组互不相干的字段

`CacheSettings` 同时维护了两套语义重复的字段:

| 字段组 | 声明处 | 写入方 | 读取方 |
|--------|--------|--------|--------|
| `maxCacheAge`(天)/ `maxCacheSize`(MB) | `CacheSettings` | `CacheConfigurable`(设置界面) | `PrivateReaderConfig`、`ConfigDiagnosticTool` |
| `cacheExpiryHours`(小时)/ `maxCacheSizeMB`(MB) | `CacheSettings` | **无人写入** | `ReactiveChapterCacheRepositoryImpl`(过期判断/容量清理) |

也就是说:设置界面保存的是 `maxCacheAge`,而缓存仓库实际读取的是 `cacheExpiryHours`。
两者初始默认值恰好相同(7 天 / 100MB),所以开箱时"看起来正常";
一旦用户在设置里修改,**写入的字段与读取的字段不是同一个**,设置悄无声息地失效。
这正是「设置改了但缓存没按时清除」的直接原因。

### 2.2 缺陷二:`cleanupOnStartup` 从未被读取

`ReactiveChapterCacheRepositoryImpl` 的构造器只注册了一个 `Observable.interval(6, TimeUnit.HOURS)`
定时清理任务。RxJava 的 `interval` **第一次触发要等一个周期(6 小时)**,加上用户会话通常远短于 6 小时、
或频繁重启 IDE,定时任务永远等不到第一次执行。

同时 `CacheSettings.isCleanupOnStartup()`(默认 `true`)在整个源码中**没有任何读取点**——
设置界面上标注的"启动时清理过期缓存"是一句空头承诺。

## 三、改动内容

### 3.1 合并双份字段(缺陷一)

`CacheSettings` 保留 **`maxCacheAge` / `maxCacheSize` 作为唯一事实源**,删除 `cacheExpiryHours` / `maxCacheSizeMB`
两个独立字段;兼容 getter/setter 改为在唯一字段上换算:

- `getCacheExpiryHours()` → `maxCacheAge * 24`
- `setCacheExpiryHours(h)` → `maxCacheAge = max(1, round(h / 24))`(以天为最小粒度)
- `getMaxCacheSizeMB()` → `maxCacheSize`
- `setMaxCacheSizeMB(mb)` → `maxCacheSize = mb`

缓存仓库(`ReactiveChapterCacheRepositoryImpl`)继续调用原有 API,
无需感知字段合并,即自动读到 UI 设置的值。

> 兼容性:旧版本 JSON 里遗留的 `cacheExpiryHours`/`maxCacheSizeMB` 字段在反序列化时被 Gson 忽略,
> 但用户已持久化的 `maxCacheAge`/`maxCacheSize`(设置界面一直写入的字段)会正确加载,故存量配置不丢失。

### 3.2 启动阶段补一次立即清理(缺陷二)

`ReactiveChapterCacheRepositoryImpl` 构造器中,在调度 6 小时定时任务之前,
按 `cacheSettings.isCleanupOnStartup()` 判断是否**立即异步执行一次** `cleanupCacheReactive()`:

```java
if (cacheSettings != null && cacheSettings.isCleanupOnStartup()) {
    runStartupCleanup();   // cleanupCacheReactive().subscribe(),IO 线程,不阻塞 UI
}
scheduleCleanupTask();     // 保留原 6 小时定时补偿
```

这样短会话用户也能在每次启动时清理一次过期缓存,`cleanupOnStartup` 终于名副其实。

## 四、测试

### 新增 `ReactiveChapterCacheRepositoryImplTest`(4 测试)

用 `@TempDir` 搭真实缓存目录、`MockedStatic(ApplicationManager)` 提供 mock `StorageRepository`、
`RxJavaPlugins.setIoSchedulerHandler(trampoline)` 让构造器内的清理同步可断言:

| 测试 | 断言 |
|------|------|
| `startupCleanupRemovesExpiredCacheFilesWhenEnabled` | `cleanupOnStartup=true`,30 天前的章节文件被删除 |
| `startupCleanupKeepsExpiredFilesWhenDisabled` | `cleanupOnStartup=false`,过期文件保留 |
| `startupCleanupKeepsFreshCacheFiles` | 未过期文件不被误删 |
| `expiryUsesConfiguredHours` | 过期阈值随配置小时数变化(7 天阈值下 2 天前文件保留) |

### 调整既有测试

- `CacheSettingsTest`:新增 `expiryDaysAndHoursStayInSync`、`maxCacheSizeAndMBStayInSync`
  两个回归测试,锁定「UI 写入方与仓储读取方指向同一变量」;`settersMutateValuesAndMarkDirty`
  改为断言共享字段的最终值。
- `SettingsStorageTest`:序列化字段名断言由 `cacheExpiryHours`/`maxCacheSizeMB` 改为
  `maxCacheAge`/`maxCacheSize`,与合并后的唯一事实源一致。

## 五、覆盖率

| 指标 | V14 | V15 | 变化 |
|------|-----|-----|------|
| LINE | 36.35%(2782/7653) | **38.34%(2988/7793)** | +1.99pp |
| 测试数 | 299 | **311** | +12 |

关键文件:

| 文件 | 说明 |
|------|------|
| `CacheSettings` | 字段合并,getter/setter 换算 |
| `ReactiveChapterCacheRepositoryImpl` | 启动清理 |
| `ReactiveChapterCacheRepositoryImplTest`(新) | 4 测试 |
| `CacheSettingsTest` | +2 回归测试 |
| `SettingsStorageTest` | 字段名断言对齐 |

## 六、验证结果

- `JAVA_HOME=<jdk21> ./gradlew test` — ✅ **311 测试全通过**(0 失败/0 错误)
- `JAVA_HOME=<jdk21> ./gradlew build` — ✅ 打包 + JaCoCo LINE **38.34%** > 阈值 0.30
- 存量配置兼容:用户 `~/.private-reader/settings/CacheSettings.json` 中 `maxCacheAge`/`maxCacheSize`
  正常加载(旧 `cacheExpiryHours`/`maxCacheSizeMB` 被忽略)

## 七、遗留事项

- [ ] 覆盖率实测 38.34%,阈值可上调至 0.35(余量 3.34pp),建议下一轮补测后执行
- [ ] 定时清理仍固定 6 小时,可考虑改为「启动清理 + 可配置周期」,并与设置界面联动
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,仍需真实 IDE 或 UI 测试框架
- [ ] `storage` 主体、`initialization` 为低覆盖保留方向

## 八、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `settings/CacheSettings.java` | 修改 | 删除双份字段,兼容 getter/setter 换算到唯一事实源 |
| `repository/impl/ReactiveChapterCacheRepositoryImpl.java` | 修改 | 新增 `runStartupCleanup()` 并在构造器按设置执行 |
| `repository/impl/ReactiveChapterCacheRepositoryImplTest.java` | **新增** | 4 个启动清理/过期阈值测试 |
| `settings/CacheSettingsTest.java` | 修改 | +2 字段同步回归测试,既有断言对齐 |
| `storage/SettingsStorageTest.java` | 修改 | 序列化字段名断言对齐 |
| `CHANGELOG.md` | 修改 | 3.0.0 补充缓存清理修复条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/踩坑/文档清单更新至 V15 |