# Private Reader 项目优化方案 V18 —— 删除无调用方的 Reactive 兼容路径

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V17.md](./OPTIMIZATION_V17.md)(V17 导航流水线拆分 `ChapterNavigator`)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 承接 V16/V17 遗留的「Reactive 兼容路径 `showChapterContent(Book,...)` 疑似无调用方」,确认后删除

---

## 一、背景与动机

V16/V17 拆分后,`NotificationServiceImpl` 由 1186 行降至 923 行。剩余代码中,
接口 `NotificationService` 声明了 4 个 Reactive 方法:

| 方法 | 调用方 | 结论 |
|------|--------|------|
| `Single<Notification> showError(String,String)` | 全项目 40+ 处(UI actions/dialog/settings/ReaderViewModel) | **保留** |
| `Single<Notification> showInfo(String,String)` | `ReaderModeSwitcher`、`ReaderPanel` 共 14 处 | **保留** |
| `Completable closeAllNotificationsReactive()` | 接口声明(生命周期/兼容) | **保留** |
| `Single<Notification> showChapterContent(Book,String,String)` | **无生产调用方,无测试调用方** | **删除** |

真实章节展示走的是 `showChapterContent(Project, bookId, chapterId, pageNumber, title, content)`
(`NotificationBarModeService` 两处调用)。`Book` 重载自通知栏模式切换为 Project 版本后即成为死代码,
仅带注释 `// Existing reactive methods (kept for compatibility if still used elsewhere)` 保留至今。

## 二、确认过程

- `grep -rn "showChapterContent" src/main/java src/test/java` — 仅命中:
  - 接口声明(1)+ 实现(1)
  - 6 参 Project 版本的生产调用(2)与测试断言(5)
- 交叉验证:`grep -rn "showChapterContent(.*Book\|showChapterContent(book"` 无任何命中
- 结论:**死代码,可安全删除**;不涉及任何插件扩展点或外部契约

## 三、改动内容

### 3.1 删除实现

`NotificationServiceImpl.java` 删除 `showChapterContent(Book,String,String)`
实现体(注释 + 方法,原文 608–737 行,约 130 行),包括其内部的:

- `chapterService.getChapterTitle(...)` 异步链
- 页码恢复 + 分页 + 页码区间校验
- `readingProgressRepository` / `bookService.saveReadingProgress` 双路进度保存
- 章节列表索引查找与 `triggerChapterPreload`
- 无 `Project` 时的 `NotificationGroupManager` 降级通知分支

### 3.2 删除接口声明

`NotificationService.java` 移除 `Single<Notification> showChapterContent(Book,...)`,
并清理随之不再使用的 `import Book`。

### 3.3 清理失效 import

`NotificationServiceImpl.java` 中因删除本方法而失效的 5 个 import:

| import | 原因 |
|--------|------|
| `NotificationGroupManager` | 仅降级通知分支使用 |
| `NotificationType` | 仅降级通知分支使用 |
| `ProjectManager` | 仅 `getOpenProjects()[0]` 获取 Project 使用 |
| `PrivateReaderConfig` | 仅降级通知分支的通知组 ID 使用 |
| `io.reactivex.rxjava3.schedulers.Schedulers` | 仅 `.subscribeOn(Schedulers.io())` 使用 |

## 四、结果

| 指标 | V17 | V18 | 变化 |
|------|-----|-----|------|
| `NotificationServiceImpl` 行数 | 923 | **788** | **−135** |
| LINE 覆盖率 | 41.08% | **41.45%** | +0.37pp |
| `NotificationServiceImpl` 自身覆盖 | 23.9% | **29.6%** | +5.7pp |
| 测试数 | 339 | 339 | 不变(全通过) |

> 注意:覆盖率**上升**——死代码原先计入「未覆盖行」,删除后分母与分子同时优化。

**拆分历史**:`1186 →(V16)1096 →(V17)923 →(V18)788` 行,累计 **−398 行(−33.6%)**。

## 五、验证结果

- `./gradlew compileJava compileTestJava` — ✅
- `./gradlew build` — ✅ 全量测试 339 通过,JaCoCo LINE **41.45%** > 阈值 0.35

## 六、遗留事项

- [ ] `NotificationServiceImpl` 仍有 ~788 行;可继续评估:
      - `dispose` 生命周期 + 进度保存/恢复(`restoreSavedPageNumber`、`saveNotificationModeProgress`)
      - `showChapterContent(Project,...)` 展示路径可再抽一层
- [ ] `NotificationDisplayManager.showReadingNotification` 真实通知路径未覆盖(32.8%)
- [ ] `ui/*`(actions/dialog/settings/ReaderPanel)依赖 EDT/Swing,需真实 IDE/UI 测试框架
- [ ] `storage` 主体、`initialization` 为低覆盖保留方向

## 七、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/NotificationService.java` | 修改 | 移除 `showChapterContent(Book,...)`,清理 `Book` import |
| `service/impl/NotificationServiceImpl.java` | 修改 | 删除方法与 5 个失效 import(−135 行) |
| `CHANGELOG.md` | 修改 | 3.1.1 补充死代码清理条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/文档清单更新至 V18 |