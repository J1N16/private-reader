# 响应式编程 vs CompletableFuture 对比

## 概述对比

| 特性 | CompletableFuture | 响应式编程 (Reactor) |
|-----|------------------|-------------------|
| **Java 版本** | Java 8+ 标准库 | 第三方库 |
| **数据模型** | 单一结果 (0-1) | 单一结果 (Mono, 0-1) 或流式结果 (Flux, 0-N) |
| **背压支持** | 无 | 内置支持 |
| **操作符丰富度** | 中等 | 非常丰富 |
| **学习曲线** | 中等 | 较陡峭 |
| **内存效率** | 中等 | 高 |
| **调试难度** | 中等 | 较高 |
| **适用场景** | 简单异步操作 | 复杂异步流程、流处理 |

## 代码示例对比

### 1. 简单异步操作

**CompletableFuture:**
```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    return "Hello World";
});

future.thenAccept(System.out::println);
```

**Reactor:**
```java
Mono<String> mono = Mono.fromSupplier(() -> {
    return "Hello World";
});

mono.subscribe(System.out::println);
```

### 2. 转换结果

**CompletableFuture:**
```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "Hello")
    .thenApply(s -> s + " World");

future.thenAccept(System.out::println);
```

**Reactor:**
```java
Mono<String> mono = Mono.just("Hello")
    .map(s -> s + " World");

mono.subscribe(System.out::println);
```

### 3. 组合多个异步操作

**CompletableFuture:**
```java
CompletableFuture<String> future1 = CompletableFuture.supplyAsync(() -> "Hello");
CompletableFuture<String> future2 = CompletableFuture.supplyAsync(() -> "World");

CompletableFuture<String> combined = future1.thenCombine(future2, (s1, s2) -> s1 + " " + s2);
combined.thenAccept(System.out::println);
```

**Reactor:**
```java
Mono<String> mono1 = Mono.just("Hello");
Mono<String> mono2 = Mono.just("World");

Mono<String> combined = Mono.zip(mono1, mono2)
    .map(tuple -> tuple.getT1() + " " + tuple.getT2());
combined.subscribe(System.out::println);
```

### 4. 错误处理

**CompletableFuture:**
```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    if (Math.random() > 0.5) {
        throw new RuntimeException("操作失败");
    }
    return "成功";
})
.exceptionally(ex -> {
    System.err.println("错误: " + ex.getMessage());
    return "失败";
});

future.thenAccept(System.out::println);
```

**Reactor:**
```java
Mono<String> mono = Mono.fromSupplier(() -> {
    if (Math.random() > 0.5) {
        throw new RuntimeException("操作失败");
    }
    return "成功";
})
.onErrorResume(ex -> {
    System.err.println("错误: " + ex.getMessage());
    return Mono.just("失败");
});

mono.subscribe(System.out::println);
```

### 5. 顺序执行多个异步操作

**CompletableFuture:**
```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "步骤1")
    .thenCompose(s -> {
        System.out.println(s);
        return CompletableFuture.supplyAsync(() -> s + ", 步骤2");
    })
    .thenCompose(s -> {
        System.out.println(s);
        return CompletableFuture.supplyAsync(() -> s + ", 步骤3");
    });

future.thenAccept(System.out::println);
```

**Reactor:**
```java
Mono<String> mono = Mono.just("步骤1")
    .flatMap(s -> {
        System.out.println(s);
        return Mono.just(s + ", 步骤2");
    })
    .flatMap(s -> {
        System.out.println(s);
        return Mono.just(s + ", 步骤3");
    });

mono.subscribe(System.out::println);
```

### 6. 并行执行多个异步操作

**CompletableFuture:**
```java
List<CompletableFuture<String>> futures = Arrays.asList(
    CompletableFuture.supplyAsync(() -> "任务1"),
    CompletableFuture.supplyAsync(() -> "任务2"),
    CompletableFuture.supplyAsync(() -> "任务3")
);

CompletableFuture<Void> allFutures = CompletableFuture.allOf(
    futures.toArray(new CompletableFuture[0])
);

CompletableFuture<List<String>> results = allFutures.thenApply(v ->
    futures.stream()
        .map(CompletableFuture::join)
        .collect(Collectors.toList())
);

results.thenAccept(System.out::println);
```

**Reactor:**
```java
List<Mono<String>> monos = Arrays.asList(
    Mono.just("任务1"),
    Mono.just("任务2"),
    Mono.just("任务3")
);

Flux<String> flux = Flux.merge(monos);
// 或者保持顺序: Flux<String> flux = Flux.concat(monos);

flux.collectList().subscribe(System.out::println);
```

### 7. 超时处理

**CompletableFuture:**
```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    try {
        Thread.sleep(2000);
        return "操作完成";
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
    }
});

try {
    String result = future.get(1, TimeUnit.SECONDS);
    System.out.println(result);
} catch (TimeoutException e) {
    System.out.println("操作超时");
    future.cancel(true);
} catch (Exception e) {
    System.out.println("其他错误: " + e.getMessage());
}
```

**Reactor:**
```java
Mono<String> mono = Mono.fromSupplier(() -> {
    try {
        Thread.sleep(2000);
        return "操作完成";
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
    }
})
.timeout(Duration.ofSeconds(1))
.onErrorResume(TimeoutException.class, e -> Mono.just("操作超时"));

mono.subscribe(System.out::println);
```

### 8. 重试机制

**CompletableFuture:**
```java
// 需要自己实现重试逻辑
public <T> CompletableFuture<T> retryCompletableFuture(
        Supplier<CompletableFuture<T>> futureSupplier,
        int maxRetries) {
    
    CompletableFuture<T> result = new CompletableFuture<>();
    
    retryHelper(futureSupplier, maxRetries, 0, result);
    
    return result;
}

private <T> void retryHelper(
        Supplier<CompletableFuture<T>> futureSupplier,
        int maxRetries,
        int currentRetry,
        CompletableFuture<T> result) {
    
    if (currentRetry > maxRetries) {
        result.completeExceptionally(new RuntimeException("最大重试次数已达到"));
        return;
    }
    
    futureSupplier.get().whenComplete((value, exception) -> {
        if (exception == null) {
            result.complete(value);
        } else {
            System.out.println("重试 " + currentRetry + " 失败: " + exception.getMessage());
            retryHelper(futureSupplier, maxRetries, currentRetry + 1, result);
        }
    });
}

// 使用
retryCompletableFuture(() -> CompletableFuture.supplyAsync(() -> {
    if (Math.random() > 0.7) {
        return "成功";
    }
    throw new RuntimeException("随机失败");
}), 3).thenAccept(System.out::println);
```

**Reactor:**
```java
Mono<String> mono = Mono.fromSupplier(() -> {
    if (Math.random() > 0.7) {
        return "成功";
    }
    throw new RuntimeException("随机失败");
})
.retry(3)
.doOnError(e -> System.out.println("所有重试都失败: " + e.getMessage()));

mono.subscribe(System.out::println);
```

### 9. 条件执行

**CompletableFuture:**
```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    return "数据";
})
.thenCompose(data -> {
    if (data.length() > 5) {
        return CompletableFuture.supplyAsync(() -> "长数据: " + data);
    } else {
        return CompletableFuture.supplyAsync(() -> "短数据: " + data);
    }
});

future.thenAccept(System.out::println);
```

**Reactor:**
```java
Mono<String> mono = Mono.just("数据")
    .flatMap(data -> {
        if (data.length() > 5) {
            return Mono.just("长数据: " + data);
        } else {
            return Mono.just("短数据: " + data);
        }
    });

mono.subscribe(System.out::println);
```

### 10. 流处理 (多个元素)

**CompletableFuture:**
```java
// CompletableFuture 不适合流处理，需要结合 Stream API
List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5);

List<CompletableFuture<Integer>> futures = numbers.stream()
    .map(n -> CompletableFuture.supplyAsync(() -> n * 2))
    .collect(Collectors.toList());

CompletableFuture<Void> allFutures = CompletableFuture.allOf(
    futures.toArray(new CompletableFuture[0])
);

CompletableFuture<List<Integer>> results = allFutures.thenApply(v ->
    futures.stream()
        .map(CompletableFuture::join)
        .collect(Collectors.toList())
);

results.thenAccept(System.out::println);
```

**Reactor:**
```java
// Reactor 原生支持流处理
Flux<Integer> flux = Flux.just(1, 2, 3, 4, 5)
    .map(n -> n * 2);

flux.collectList().subscribe(System.out::println);
```

## 功能对比

### 1. 操作符丰富度

**CompletableFuture:**
- 基本操作符: `thenApply`, `thenCompose`, `thenCombine`, `thenAccept`
- 组合操作符: `allOf`, `anyOf`
- 异常处理: `exceptionally`, `handle`
- 超时控制: 需要通过 `get(timeout, unit)` 实现

**Reactor:**
- 转换操作符: `map`, `flatMap`, `concatMap`, `switchMap` 等
- 过滤操作符: `filter`, `take`, `skip`, `distinct` 等
- 组合操作符: `zip`, `merge`, `concat`, `combineLatest` 等
- 条件操作符: `defaultIfEmpty`, `switchIfEmpty`, `takeUntil` 等
- 错误处理: `onErrorResume`, `onErrorReturn`, `onErrorMap`, `retry` 等
- 调度操作符: `publishOn`, `subscribeOn`
- 时间操作符: `timeout`, `delay`, `delayElements` 等
- 窗口操作符: `window`, `buffer`, `groupBy` 等

### 2. 资源管理

**CompletableFuture:**
- 没有内置的资源管理机制
- 需要手动管理资源的获取和释放
- 取消操作通过 `cancel(boolean)` 方法

**Reactor:**
- 提供 `using` 和 `usingWhen` 操作符管理资源
- 支持 `Disposable` 接口进行资源清理
- 提供 `doFinally` 操作符确保资源释放

### 3. 背压支持

**CompletableFuture:**
- 不支持背压机制
- 生产者可能产生过多数据导致消费者过载

**Reactor:**
- 内置背压支持
- 消费者可以控制生产者的生产速率
- 提供多种背压策略: 缓冲、丢弃、错误等

### 4. 调试支持

**CompletableFuture:**
- 异常栈通常包含完整的调用链信息
- 调试相对简单

**Reactor:**
- 异常栈可能不包含完整的调用链信息
- 提供 `checkpoint()` 和 `log()` 操作符辅助调试
- 支持开启调试模式 (`Hooks.onOperatorDebug()`)

### 5. 测试支持

**CompletableFuture:**
- 没有专门的测试工具
- 通常使用 `get()` 或 `join()` 获取结果进行断言

**Reactor:**
- 提供 `StepVerifier` 进行声明式测试
- 支持虚拟时间测试
- 提供 `TestPublisher` 创建测试数据源

## 适用场景对比

### CompletableFuture 更适合:

1. **简单的异步操作**
   - 单一结果的异步计算
   - 简单的异步任务链

2. **与现有代码集成**
   - Java 标准库的一部分，无需额外依赖
   - 与 Stream API 等标准库更容易集成

3. **团队熟悉度**
   - 学习曲线相对平缓
   - 更接近传统的命令式编程模型

4. **简单应用**
   - 小型应用或并发需求不高的场景
   - 不需要复杂的操作符和背压机制

### 响应式编程更适合:

1. **复杂的异步流程**
   - 涉及多个异步操作的复杂流程
   - 需要丰富操作符的场景

2. **流处理**
   - 处理无限或大量数据流
   - 需要对流进行转换、过滤、组合等操作

3. **高并发场景**
   - 需要处理大量并发请求
   - 资源受限环境下的高效处理

4. **背压要求**
   - 生产者和消费者速率不匹配的场景
   - 需要控制资源使用的场景

5. **响应式系统**
   - 构建完全响应式的系统
   - 与其他响应式组件集成

## 迁移策略

从 CompletableFuture 迁移到响应式编程时，可以采用以下策略:

1. **渐进式迁移**
   - 保留现有 CompletableFuture 接口
   - 添加新的响应式接口
   - 内部实现逐步迁移到响应式

2. **适配器模式**
   - 创建适配器在 CompletableFuture 和 Mono/Flux 之间转换
   - 使用 `Mono.fromFuture()` 和 `mono.toFuture()`

3. **边界隔离**
   - 在系统边界使用适配器
   - 内部组件完全使用响应式编程

4. **优先迁移关键路径**
   - 先迁移性能关键路径
   - 逐步扩展到其他部分

5. **混合架构**
   - 对于某些简单场景保留 CompletableFuture
   - 复杂场景使用响应式编程 