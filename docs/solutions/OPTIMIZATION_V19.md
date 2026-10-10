# Private Reader 项目优化方案 V19 —— 阅读进度保存/恢复拆分 `NotificationProgressManager`

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V18.md](./OPTIMIZATION_V18.md)(V18 删除无调用方的 Reactive 兼容路径)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 承接 V18 遗留的「拆分 `dispose` 生命周期/进度保存恢复」中的进度部分

---

## 一、背景

V16–V18 后,`NotificationServiceImpl` 由 1186 行降至 788 行。剩余职责中,
**阅读进度的恢复与保存**是一块与通知展示解耦、且逻辑可独立测试的单元:

| 方法 | 职责 |
|------|------|
| `restoreSavedPageNumber(bookId, chapterId, defaultPage)` | 从仓库恢复与当前章节匹配的页码 |
| `saveNotificationModeProgress()` / `(notifyWhenInactive, createRepositoryIfNeeded)` | 保存当前会话页码(翻页/加载/事件回调/销毁多处调用) |

两处逻辑仅依赖「阅读状态快照」与「阅读进度仓库」,天然适合继续下沉。

## 二、改动内容

### 2.1 新增 `NotificationProgressManager`(118 行)

`service/impl/notification/NotificationProgressManager.java`:

- `restoreSavedPageNumber(bookId, chapterId, defaultPage)` — 同章节恢复,不匹配/无记录/仓库异常均安全回落默认值
- `saveProgress()` / `saveProgress(notifyWhenInactive)` — 有活动会话时以 `pageIndex + 1` 写入(1 基页码),
  无会话时按参数决定是否提示并短路
- **窄接口 `Host`(2 方法)**:`getViewState()` 读取快照、`isReadingActive(notifyWhenInactive)` 判断会话

### 2.2 `NotificationServiceImpl` 瘦身

- 删除 `restoreSavedPageNumber`、`saveNotificationModeProgress` 共 2 个方法体
- 新增 `progressManager` 字段 + `progressHost` 匿名实现
- `ChapterEventProcessor.Host.saveNotificationModeProgress()` 改为委托 `progressManager.saveProgress()`
- 三处调用点改为 `progressManager.saveProgress()` / `saveProgress(false)`
- **删除已不再使用的 `readingProgressRepository` 字段**(仅用于向两个子组件传参,改为构造器局部变量)
- 清理失效 import(`BookProgressData`、`Optional`)与一处孤儿未闭合注释
- 净变化:**788 → 741 行(—47)**

## 三、测试

### 新增 `NotificationProgressManagerTest`(7 测试)

| 测试 | 断言 |
|------|------|
| `restoreReturnsSavedPageWhenChapterMatches` | 同章节恢复已保存页码 |
| `restoreFallsBackWhenChapterDiffers` | 章节不匹配回落默认值 |
| `restoreFallsBackWhenNoRecord` | 无进度记录回落默认值 |
| `restoreFallsBackWhenRepositoryNull` | 仓库为 null 安全回落 |
| `saveWritesOneBasedPageForActiveSession` | 活动会话写入 `pageIndex+1`(1 基) |
| `saveSkipsWhenNoActiveSession` | 无会话时不写库 |
| `saveSkipsWhenRepositoryNull` | 仓库为 null 安全短路 |

隔离:窄接口 `Host` 用可断言内存实现(复用真实 `ReaderViewState`);仓库为 mock,验证写入参数。

> 注:仓库抛异常分支因 IntelliJ `Logger.error` 在测试环境抛 `AssertionError`,未纳入测试(与既有约定一致)。

## 四、结果

| 指标 | V18 | V19 | 变化 |
|------|-----|-----|------|
| `NotificationServiceImpl` 行数 | 788 | **741** | **−47** |
| LINE 覆盖率 | 41.45% | **41.89%** | +0.44pp |
| `NotificationServiceImpl` 自身覆盖 | 29.6% | **33.8%** | +4.2pp |
| `NotificationProgressManager`(新) | — | 118 行(**88.1%**) | — |
| 测试数 | 339 | **346** | +7 |

**拆分历史**:`1186 →(V16)1096 →(V17)923 →(V18)788 →(V19)741` 行,累计 **−445 行(−37.5%)**。

## 五、验证结果

- `./gradlew test` — ✅ 346 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **41.89%** > 阈值 0.35

## 六、遗留事项

- [ ] `NotificationServiceImpl` 仍有 ~741 行;剩余主要职责:
      - `showChapterContent(Project,...)` 展示路径(异步取书 → 分页 → 展示 → 预加载 → 保存)
      - `dispose` 生命周期(消息总线断开 + 通知关闭 + 模式判定)
      - 分页/状态读取的公开 API(接口契约,保留)
- [ ] `NotificationDisplayManager.showReadingNotification` 真实通知路径未覆盖(32.8%)
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,需真实 IDE/UI 测试框架
- [ ] `storage` 主体、`initialization` 为低覆盖保留方向

## 七、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/impl/notification/NotificationProgressManager.java` | **新增** | 进度保存/恢复 + 窄接口 `Host` |
| `service/impl/NotificationServiceImpl.java` | 修改 | 删除 2 个方法 + 无用字段/import(−47 行) |
| `service/impl/notification/NotificationProgressManagerTest.java` | **新增** | 7 个恢复/保存测试 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充进度拆分条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V19 |