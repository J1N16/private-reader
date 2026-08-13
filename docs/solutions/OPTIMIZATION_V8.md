# Private Reader CI 与覆盖率集成

> 创建日期: 2026-08-13
> 项目版本: 2.5.1
> 配套: [OPTIMIZATION_V7.md](./OPTIMIZATION_V7.md)

---

## 一、背景

V1-V7 七轮优化完成后，项目缺少 CI 自动化和覆盖率量化手段。本轮引入：
- **JaCoCo 覆盖率**（报告 + 阈值护栏）
- **GitHub Actions CI**（构建/测试/打包/二进制兼容性检查）

## 二、JaCoCo 覆盖率集成

### 配置要点（`build.gradle`）

```groovy
plugins {
    id 'jacoco'
}

test {
    jacoco {
        // 关键：IntelliJ 平台用 PathClassLoader 加载插件类（无 location），须显式纳入
        includeNoLocationClasses = true
        excludes = ['jdk.internal.*']
    }
    finalizedBy jacocoTestReport
}

jacocoTestReport {
    classDirectories.setFrom(sourceSets.main.output.classesDirs)
}

jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = 'LINE'
                value = 'COVEREDRATIO'
                minimum = 0.15
            }
        }
    }
}

check {
    dependsOn jacocoTestCoverageVerification
}
```

### 踩坑记录：为何默认配置会得到 0% 覆盖率

**现象**：默认 JaCoCo 配置下报告全为 0%，但测试明明执行了插件逻辑。

**根因**（官方 FAQ「JaCoCo Reports 0% Coverage」）：
1. IntelliJ Platform 2022.1+ 的测试任务通过 `PathClassLoader`（`-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader`）加载插件类
2. 这些类**无 location**，JaCoCo 默认 `includeNoLocationClasses=false`，因此被排除在录制之外
3. 修复：`test { jacoco { includeNoLocationClasses = true } }`

**验证方法**：用 `org.jacoco.core` 的 `ExecFileLoader` 解析 `build/jacoco/test.exec`，
确认其中是否含 `com.lv.tool.privatereader.*` 类。

### 覆盖率基线（2026-08-13 首次测量）

| 指标 | 覆盖率 | 数值 |
|------|--------|------|
| LINE | 18.24% | 1383/7581 行 |
| CLASS | 28.36% | 38/134 类 |
| INSTRUCTION | 19.16% | 6071/31686 |
| BRANCH | 17.14% | 461/2689 |
| METHOD | 21.13% | 265/1254 |

**高覆盖包**：`service/impl/notification` 85.4%、`model` 75.8%、`ui/mvi` 61.3%、
`parser/site` 55.8%、`repository/impl` 43.4%

**零覆盖包（后续补测重点）**：`settings`、`ui/dialog`、`ui/settings`、`ui/actions`、
`service`（接口层）、`storage/cache`、`initialization`、`config`

### 阈值说明

- 阈值 `minimum = 0.15`（LINE 15%）为**回归护栏**，低于基线 18.24%
- 目的：防止新改动显著降低覆盖率，而非当前目标值
- 后续补测可逐步上调

## 三、GitHub Actions CI

工作流：`.github/workflows/ci.yml`

### 触发条件
- push 到 `master` / `release/*` 分支
- 任意 pull request

### Job 1: Build & Test（主流水线）
1. checkout → JDK 21 → Gradle setup
2. `./gradlew build`（含测试 + 覆盖率阈值）
3. `./gradlew buildPlugin`（打包 zip）
4. 上传测试报告 / 覆盖率报告 / 插件产物

### Job 2: Verify plugin（二进制兼容性，独立并行）
- `./gradlew verifyPlugin`（验证 2026.1/2026.2 兼容性，耗时较长）
- 上传 verifier 报告

### 产物上传
- `build/reports/tests/`、`build/test-results/` — 测试结果
- `build/reports/jacoco/` — 覆盖率 HTML+XML
- `build/libs/*.jar`、`build/distributions/*.zip` — 插件包（仅 master）

## 四、本地验证结果

- `./gradlew clean build buildPlugin` — ✅ 全链路通过
- `./gradlew jacocoTestCoverageVerification` — ✅ 阈值通过
- 插件包 `private-reader-2.5.1.zip`（18MB）— ✅
- 覆盖率 HTML/XML 报告 — ✅ 生成于 `build/reports/jacoco/`
