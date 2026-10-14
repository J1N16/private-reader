# Private Reader 优化方案 V29 —— 设置面板 headless 测试(突破 UI 类零覆盖)

> 创建日期: 2026-10-10
> 项目版本: 3.1.1
> 配套: [OPTIMIZATION_V28.md](./OPTIMIZATION_V28.md)(V28 存储/恢复解耦)
> 实施状态: 已完成 ✅(2026-10-10)
> 目标: 验证「IntelliJ 设置面板可在 headless 单测环境下实例化」,
> 从而一次性拿下三个长期 0% 的配置面板(共 469 行)。

---

## 一、背景与关键发现

历轮把 `ui/*` 一律标为「依赖 EDT/Swing,需真实 IDE 测试框架」而搁置。V29 用探针测试
推翻了这一假设:

- **JDK headless 下 `JPanel`/`JSpinner`/`JCheckBox`/`JComboBox` 可正常创建与读写**;
- **IntelliJ 的 `FormBuilder`/`JBLabel`/`JBCheckBox`/`LinkLabel`/`ComboBox` 也能在无 IDE 容器时实例化**;
- 面板所需的 `ApplicationManager.getApplication().getService(...)` / `MessageBus` /
  `ProjectManager` 均可 `mockStatic` + mock 提供(与 V5–V28 一贯做法一致);
- 唯一需回避的是**会弹窗/阻塞的交互**(`ColorPicker.showDialog`、`Messages.showYesNoDialog`、
  `Desktop.getDesktop().open`),它们都只在**事件回调**里触发,测试不点击即可绕开。

这一发现使 UI 类从「不可测」变为「可测其构造、装配、绑定、isModified/apply/reset 逻辑」,
是当前投入产出比最高的方向。

## 二、改动内容(零生产代码改动,纯新增测试)

### 2.1 `NotificationReaderConfigurableTest`(新,8)

面板 92 行全部覆盖(100%):

- `getDisplayName`、`createComponent` 装配与初值播种
- 即时预览:`pageSizeSpinner` 变更 → `setPageSize` + `settingsChanged` 发布;同值不发布
- 复选框切换 → 写入 settings 并发布
- `apply` 写入全部 6 项 + `saveSettings` + 清除 modified
- `reset` 从 settings 还原控件

### 2.2 `ReaderConfigurableTest`(新,10)

132 行 → 89.4%:

- 面板装配、字体/字号/深色/动画初值播种
- `isModified` 匹配/字号变更/粗体变更三种情形
- `apply` 默认预设路径(字体+动画+预设名+保存+发布)
- `apply` 自定义预设路径(Construct custom `Theme` + `setThemePresets` + `setCurrentPresetName("Custom")`)
- 深色模式变化 → `toggleTheme()`;未变化 → 不调用
- 预设切换 Custom → 颜色控件启用
- `reset` 还原

> 未覆盖部分为 `ColorPicker.showDialog` 回调(需真实对话框)与 `createColorPanel` 的少量分支。

### 2.3 `CacheConfigurableTest`(新,11)

245 行 → 54.3%:

- 面板装配、控件初值播种、预加载开关联动控件启用态
- `isModified` 匹配/开关变更/数值变更
- `apply` 全量持久化 + 变化时发布 `cacheSettingsChanged`;无变化时跳过发布
- `reset` 还原并重新联动启用态
- 有打开项目时经 `Project.getService(StorageManager)` 取路径的分支

> 未覆盖部分为「清理缓存」按钮回调(依赖 `Messages.showYesNoDialog` 模态框)、
> `openDirectory`(依赖 `Desktop`)、`copyToClipboard`、`getShortPath`/`formatFileSize` 等
> 需交互或文件系统副作用的分支。

## 三、结果

| 指标 | V28 | V29 | 变化 |
|------|-----|-----|------|
| LINE 覆盖率 | 55.23% | **59.53%**(4775/8021) | **+4.30pp** |
| 测试数 | 541 | **571** | +30 |
| `NotificationReaderConfigurable` | 0% | **100.0%** | **+100pp** |
| `ReaderConfigurable` | 0% | **89.4%** | **+89.4pp** |
| `CacheConfigurable` | 0% | **54.3%** | **+54.3pp** |
| 覆盖率护栏 | 0.52 | **0.56** | +0.04 |

## 四、验证结果

- `./gradlew test` — ✅ 571 测试全通过(0 失败)
- `./gradlew build` — ✅ JaCoCo LINE **59.53%** > 阈值 0.56

## 五、遗留事项

- [ ] `CacheConfigurable` 剩余 91 行:清理缓存/打开目录/剪贴板等交互回调(需真实 UI 容器)
- [ ] 其余 UI 类可沿 V29 模式补测:`BookshelfDialog`(234)、`ChapterListDialog`(298)、
      `ReaderPanel`(314)、`AddBookDialog`(76)、`DiagnosticAction$DiagnosticDialog`(128)
- [ ] `ProjectInitializationActivity`(66)/`PrivateReaderStartupActivity`(55):启动活动,需平台上下文
- [ ] `NotificationServiceImpl` 42.6%:接口契约 API 与 dispose,优先级低
- [ ] `UniversalParser` 66.8% / `FileBookRepository` 64.3%:异常分支与 HTML 解析边界

## 六、变更文件清单

| 文件 | 类型 | 说明 |
|------|------|------|
| `ui/settings/NotificationReaderConfigurableTest.java` | **新增** | 8 个面板测试(→100%) |
| `ui/settings/ReaderConfigurableTest.java` | **新增** | 10 个面板测试(→89.4%) |
| `ui/settings/CacheConfigurableTest.java` | **新增** | 11 个面板测试(→54.3%) |
| `build.gradle` | 修改 | 覆盖率阈值 0.52 → 0.56 |
| `CHANGELOG.md` | 修改 | 3.1.1 补充 V29 条目 |
| `docs/solutions/README.md` | 修改 | 时间线/状态/指标/踩坑/文档清单更新至 V29 |