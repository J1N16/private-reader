# Private Reader 项目优化方案 V11 —— 通知服务职责拆分 / 不可变阅读状态 / 正则预编译

> 创建日期: 2026-09-18
> 项目版本: 3.0.0
> 配套: [OPTIMIZATION_V10.md](../OPTIMIZATION_V10.md)(V10 补测与覆盖率护栏)
> 实施状态: 已完成 ✅(2026-09-18)
> 目标: 落实"短期优化第 1 项"——拆分 NotificationServiceImpl 臃肿类、多字段状态封装、
>       TextFormatter 正则预编译

---

## 一、背景

在完成 V1~V10 的架构梳理与测试补全后,项目遗留三项高优先级的短期问题(见
优化方向分析):

1. **NotificationServiceImpl 过于臃肿(1305 行)**:混合通知显示、导航、事件处理、
   进度保存等职责,仍有大量可直接委托工具类的展示逻辑。
2. **分页状态多 volatile 字段一致性风险**:`currentBook`/`currentChapterId`/
   `currentChapterTitle`/`currentPages`/`currentPageIndex` 五个松散字段,跨线程
   读写时可能读到"书籍已更新但章节/页码仍是旧值"的混合视图。
3. **TextFormatter 正则重复编译**:`formatParagraphs` 的段落分割正则含 18 个
   指示词交替分支,每次调用都重新编译,长章节高频调用时造成无谓开销。

V11 针对这三项实施短期优化。

## 二、改动内容

### 2.1 通知展示逻辑拆分 → NotificationDisplayManager

**新建** `service/impl/notification/NotificationDisplayManager.java`:

| 原 NotificationServiceImpl 私有方法 | 迁移到 | 说明 |
|------|------|------|
| `showCurrentPageInternal` 的通知创建/按钮逻辑 | `showReadingNotification` | 纯展示:创建通知、加页码标题、按可用性加导航按钮 |
| `addNotificationActions` | `addReadingActions`(私有) | 上一页/下一页(按页码可用性)、上一章/下一章、返回阅读器 |
| `showLoadingNotification` 的通知创建 | `showLoadingNotification` | 加载中通知 |
| `showError`/`showInfo` 的通知创建 | `showError`/`showInfo` | 错误/信息通知 |
| `cleanHtmlTags` | `cleanHtmlTags` | HTML 实体解码 + 标签剥离 + 连续换行折叠 |

`NotificationServiceImpl` 现仅保留:状态编排、业务流(加载/导航/事件处理)、
进度保存,以及`currentNotificationRef` 的引用维护(供更新/关闭)。

**行为等价性保障**:
- 按钮回调仍调用 `NotificationService` 接口方法(`showPrevPage`/`showNextPage`/
  `navigateChapter`),与拆分前一致;
- `cleanHtmlTags` 保留 null 防护(拆分时一度丢失,测试捕获后修复)。

### 2.2 多 volatile 字段 → ReaderViewState 不可变快照

**新建** `service/impl/notification/ReaderViewState.java`:

```
ReaderViewState {
    Book book; String chapterId; String chapterTitle;
    List<String> pages; int pageIndex;
}
```

- 不可变:所有字段 final,更新通过 `withBook`/`withChapter`/`withPages`/
  `withPageIndex` 拷贝构造生成新实例。
- `NotificationServiceImpl` 以 `AtomicReference<ReaderViewState> viewStateRef`
  承载,任何状态变更通过 `updateViewState(UnaryOperator)` 原子更新;
  读取通过一次引用获取一致快照。
- 空状态 `empty()` 供未开始阅读时使用,`isReadingActive()` 收敛了"三字段
  是否齐全"的散落判断。

**消除的隐患**:原 5 个 volatile 字段的跨帧不一致(如事件回调中
`currentBook` 已换新书、`currentChapterId` 仍是旧章节)现在不可能出现——
整组状态要么全旧要么全新。

### 2.3 TextFormatter 正则预编译

- 所有 `replaceAll(String, String)` / `split(String)` 的字符串正则改为
  `static final Pattern` 常量,类加载时编译一次。
- 段落分割正则(含 `String.join("|", PARAGRAPH_INDICATORS)` 的 18 项交替)
  编译为 `PARAGRAPH_SPLIT_PATTERN`。
- 后处理的标点/对话标记正则(`\s*'x'\s*` 形态)种类有限,用
  `ConcurrentHashMap` 惰性缓存按需编译,避免每次调用重复编译。
- 因 `PARAGRAPH_INDICATORS` 被 pattern 静态初始化引用,将其声明移动到
  pattern 之前(避免非法前向引用编译错误)。
- 行为与原先字符串正则完全一致,`TextFormatterTest` 9 个用例全部通过。

## 三、测试补充

| 测试文件 | 测试数 | 覆盖要点 |
|----------|--------|----------|
| `ReaderViewStateTest` | 7 | 空状态、withChapter/withPages/withPageIndex 拷贝语义、页码重置开关、不可变性 |
| `NotificationDisplayManagerTest` | 5 | cleanHtmlTags 的 null/空输入、实体解码、标签剥离、连续换行折叠、单换行保留 |

**合计**:2 个测试文件,**12 个新增测试方法**(原 236 → 现 248)。

踩坑记录:
1. **lambda 内变量非 final**:`showChapterContent`(Reactive)分支对
   `pageIndex` 先赋值再在 `updateViewState(s -> ...)` 中引用,编译报
   "从 lambda 表达式引用的本地变量必须是最终变量"。改为算完用 final 副本
   传入 lambda。
2. **cleanHtmlTags null 防护丢失**:拆分到 `NotificationDisplayManager` 时
   参数标注 `@NotNull` 且缺 null 检查,测试捕获 NPE。补 `@Nullable` 入参
   与 null 防护,与原实现一致。
3. **TextFormatter 非法前向引用**:pattern 静态初始化引用后声明的
   `PARAGRAPH_INDICATORS` 数组编译报错,将数组声明移到 pattern 常量之前。
4. **实体解码先于标签剥离的既有语义**:`&lt;c&gt;` 解为 `<c>` 后作为标签
   被剥离,测试断言按既有行为(= 原实现行为)调整。

## 四、覆盖率

| 指标 | V10 | V11 | 变化 |
|------|-----|-----|------|
| LINE | 31.02%(2352/7581) | **31.49%(2422/7692)** | +0.47pp |
| 总行数 | 7581 | 7692 | +111(新增 3 个主类/2 个测试类) |

- 新增主代码因部分依赖 IntelliJ 运行时(通知创建)仅 `cleanHtmlTags` 等纯逻辑
  被覆盖;`ReaderViewState` 核心拷贝语义已被 `ReaderViewStateTest` 覆盖。
- `notification` 包覆盖率因新类拆分而分布更细,但拆出的逻辑本身与原先
  等价的私有方法是同一套执行路径。

## 五、验证结果

- `./gradlew clean build` — ✅ 全链路通过(248 测试 + JaCoCo 报告 + 0.27 阈值)
- 单元测试总数 236 → **248**
- LINE 覆盖率 31.02% → **31.49%**

## 六、遗留事项

- [ ] NotificationServiceImpl 仍有 ~780 行(导航/事件处理/进度保存),若需
      进一步拆分可考虑将事件处理链独立为 `ChapterEventProcessor`
- [ ] `ReaderViewState` 已具备不可变语义,后续可改为 record 进一步减样板
- [ ] `ui/*`(dialog/actions/settings)依赖 EDT/Swing,仍为 0 覆盖,需真实
      IDE 或 UI 测试框架
- [ ] 若后续覆盖率达 35%+,可再次上调阈值至 0.32(与 V10 建议一致)