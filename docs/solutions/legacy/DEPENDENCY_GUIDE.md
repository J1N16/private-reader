# 响应式编程依赖配置指南

## 必需依赖

要在项目中使用响应式编程，需要添加以下核心依赖：

### Gradle 配置

```gradle
dependencies {
    // Project Reactor 核心库
    implementation 'io.projectreactor:reactor-core:3.5.11'
    
    // Project Reactor 额外工具库（可选但推荐）
    implementation 'io.projectreactor.addons:reactor-extra:3.5.1'
    
    // Project Reactor 测试支持（用于单元测试）
    testImplementation 'io.projectreactor:reactor-test:3.5.11'
}
```

### Maven 配置

```xml
<dependencies>
    <!-- Project Reactor 核心库 -->
    <dependency>
        <groupId>io.projectreactor</groupId>
        <artifactId>reactor-core</artifactId>
        <version>3.5.11</version>
    </dependency>
    
    <!-- Project Reactor 额外工具库（可选但推荐） -->
    <dependency>
        <groupId>io.projectreactor.addons</groupId>
        <artifactId>reactor-extra</artifactId>
        <version>3.5.1</version>
    </dependency>
    
    <!-- Project Reactor 测试支持（用于单元测试） -->
    <dependency>
        <groupId>io.projectreactor</groupId>
        <artifactId>reactor-test</artifactId>
        <version>3.5.11</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## 可选依赖

根据项目需求，可以考虑添加以下可选依赖：

### 响应式 Web 支持

如果需要构建响应式 Web 应用，可以添加：

#### Gradle

```gradle
dependencies {
    // Spring WebFlux（Spring 响应式 Web 框架）
    implementation 'org.springframework:spring-webflux:6.0.13'
    
    // Netty（非阻塞网络框架，WebFlux 默认使用）
    implementation 'io.netty:netty-all:4.1.100.Final'
}
```

#### Maven

```xml
<dependencies>
    <!-- Spring WebFlux（Spring 响应式 Web 框架） -->
    <dependency>
        <groupId>org.springframework</groupId>
        <artifactId>spring-webflux</artifactId>
        <version>6.0.13</version>
    </dependency>
    
    <!-- Netty（非阻塞网络框架，WebFlux 默认使用） -->
    <dependency>
        <groupId>io.netty</groupId>
        <artifactId>netty-all</artifactId>
        <version>4.1.100.Final</version>
    </dependency>
</dependencies>
```

### 响应式数据库支持

如果需要使用响应式数据库访问，可以添加：

#### Gradle

```gradle
dependencies {
    // R2DBC（响应式关系型数据库连接）
    implementation 'io.r2dbc:r2dbc-pool:1.0.0.RELEASE'
    implementation 'io.r2dbc:r2dbc-h2:1.0.0.RELEASE' // H2 数据库
    // 或者使用其他数据库
    // implementation 'io.r2dbc:r2dbc-postgresql:0.8.13.RELEASE' // PostgreSQL
    // implementation 'dev.miku:r2dbc-mysql:0.8.2.RELEASE' // MySQL
    
    // Spring Data R2DBC（Spring 响应式数据访问）
    implementation 'org.springframework.data:spring-data-r2dbc:3.1.5'
    
    // MongoDB 响应式驱动
    implementation 'org.mongodb:mongodb-driver-reactivestreams:4.11.0'
}
```

#### Maven

```xml
<dependencies>
    <!-- R2DBC（响应式关系型数据库连接） -->
    <dependency>
        <groupId>io.r2dbc</groupId>
        <artifactId>r2dbc-pool</artifactId>
        <version>1.0.0.RELEASE</version>
    </dependency>
    <dependency>
        <groupId>io.r2dbc</groupId>
        <artifactId>r2dbc-h2</artifactId>
        <version>1.0.0.RELEASE</version>
    </dependency>
    <!-- 或者使用其他数据库 -->
    <!-- 
    <dependency>
        <groupId>io.r2dbc</groupId>
        <artifactId>r2dbc-postgresql</artifactId>
        <version>0.8.13.RELEASE</version>
    </dependency>
    <dependency>
        <groupId>dev.miku</groupId>
        <artifactId>r2dbc-mysql</artifactId>
        <version>0.8.2.RELEASE</version>
    </dependency>
    -->
    
    <!-- Spring Data R2DBC（Spring 响应式数据访问） -->
    <dependency>
        <groupId>org.springframework.data</groupId>
        <artifactId>spring-data-r2dbc</artifactId>
        <version>3.1.5</version>
    </dependency>
    
    <!-- MongoDB 响应式驱动 -->
    <dependency>
        <groupId>org.mongodb</groupId>
        <artifactId>mongodb-driver-reactivestreams</artifactId>
        <version>4.11.0</version>
    </dependency>
</dependencies>
```

### 响应式 HTTP 客户端

如果需要使用响应式 HTTP 客户端，可以添加：

#### Gradle

```gradle
dependencies {
    // WebClient（Spring 响应式 HTTP 客户端）
    implementation 'org.springframework:spring-webflux:6.0.13'
    
    // Reactor Netty HTTP（WebClient 的默认实现）
    implementation 'io.projectreactor.netty:reactor-netty-http:1.1.11'
    
    // 或者使用其他 HTTP 客户端
    // implementation 'org.eclipse.jetty:jetty-reactive-httpclient:3.0.8'
}
```

#### Maven

```xml
<dependencies>
    <!-- WebClient（Spring 响应式 HTTP 客户端） -->
    <dependency>
        <groupId>org.springframework</groupId>
        <artifactId>spring-webflux</artifactId>
        <version>6.0.13</version>
    </dependency>
    
    <!-- Reactor Netty HTTP（WebClient 的默认实现） -->
    <dependency>
        <groupId>io.projectreactor.netty</groupId>
        <artifactId>reactor-netty-http</artifactId>
        <version>1.1.11</version>
    </dependency>
    
    <!-- 或者使用其他 HTTP 客户端 -->
    <!-- 
    <dependency>
        <groupId>org.eclipse.jetty</groupId>
        <artifactId>jetty-reactive-httpclient</artifactId>
        <version>3.0.8</version>
    </dependency>
    -->
</dependencies>
```

## 版本兼容性

确保使用兼容的版本组合，特别是在使用 Spring 相关组件时：

| Spring Boot 版本 | Spring 版本 | Reactor 版本 | Java 版本 |
|-----------------|------------|-------------|----------|
| 3.2.x | 6.1.x | 3.6.x | 17+ |
| 3.1.x | 6.0.x | 3.5.x | 17+ |
| 3.0.x | 6.0.x | 3.5.x | 17+ |
| 2.7.x | 5.3.x | 3.4.x | 8+ |
| 2.6.x | 5.3.x | 3.4.x | 8+ |
| 2.5.x | 5.3.x | 3.4.x | 8+ |

## 日志配置

为了更好地调试响应式流，建议配置 Reactor 的日志：

### Logback 配置 (logback.xml)

```xml
<configuration>
    <!-- 其他配置... -->
    
    <!-- Reactor 日志配置 -->
    <logger name="reactor" level="INFO" />
    <logger name="reactor.Mono" level="INFO" />
    <logger name="reactor.Flux" level="INFO" />
    
    <!-- 开发时可以设置为 DEBUG 以获取更详细信息 -->
    <!-- <logger name="reactor" level="DEBUG" /> -->
    
    <!-- 其他配置... -->
</configuration>
```

### 代码中启用调试

在代码中，可以使用 `log()` 操作符查看响应式流的执行情况：

```java
Flux.range(1, 10)
    .map(i -> i * 2)
    .log("MyFlux") // 添加日志
    .subscribe();
```

## IDE 支持

为了更好地开发响应式应用，建议在 IDE 中安装相关插件：

### IntelliJ IDEA

- **Reactor Tools**: 提供 Reactor 相关的代码补全和检查
- **Java Stream Debugger**: 帮助可视化调试流操作

### Eclipse

- **Spring Tools 4**: 提供对 Spring 响应式编程的支持

## 常见问题解决

### 依赖冲突

如果遇到依赖冲突，可以使用以下方法解决：

#### Gradle

```gradle
configurations.all {
    resolutionStrategy {
        // 强制使用特定版本
        force 'io.projectreactor:reactor-core:3.5.11'
        force 'io.projectreactor.netty:reactor-netty-core:1.1.11'
    }
}
```

#### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-core</artifactId>
            <version>3.5.11</version>
        </dependency>
        <dependency>
            <groupId>io.projectreactor.netty</groupId>
            <artifactId>reactor-netty-core</artifactId>
            <version>1.1.11</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 类找不到错误

如果遇到 `ClassNotFoundException` 或 `NoClassDefFoundError`，检查是否添加了所有必要的依赖，特别是传递依赖可能被排除的情况。

### 版本不兼容

如果遇到版本不兼容问题，建议使用 BOM (Bill of Materials) 来管理版本：

#### Gradle

```gradle
dependencies {
    implementation platform('io.projectreactor:reactor-bom:2023.0.0')
    implementation 'io.projectreactor:reactor-core'
    implementation 'io.projectreactor.addons:reactor-extra'
}
```

#### Maven

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-bom</artifactId>
            <version>2023.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.projectreactor</groupId>
        <artifactId>reactor-core</artifactId>
    </dependency>
    <dependency>
        <groupId>io.projectreactor.addons</groupId>
        <artifactId>reactor-extra</artifactId>
    </dependency>
</dependencies>
``` 