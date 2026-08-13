# 模块重命名 + 包名调整

## 目标

| 模块 | Gradle 名 | 目录 | 包名 |
|------|-----------|------|------|
| 共享库 | `core-common` | `core-common/` | `com.ifmix.api.core.common` |
| Customer API | `core-customer-api` | `core-customer-api/` | `com.ifmix.api.core.customer` |
| Admin API | `core-admin-api` | `core-admin-api/` | `com.ifmix.api.core.admin` |

Webhook 留在 core-customer-api（公网入口）。

## 步骤

### 1. settings.gradle.kts

```kotlin
rootProject.name = "ifmix-server"

include("core-common", "core-customer-api", "core-admin-api")
```

### 2. 目录重命名

```bash
mv core-api core-customer-api
```

### 3. core-common 包名变更

将源码从 `com/ifmix/api/core/{entity,infra,modules}` 移到 `com/ifmix/api/core/common/{entity,infra,modules}`：

```bash
cd core-common/src/main/kotlin/com/ifmix/api/core
mkdir common
mv entity infra modules common/
```

全部源文件 package 声明替换（core-common 内部）：
- `package com.ifmix.api.core.entity` → `package com.ifmix.api.core.common.entity`
- `package com.ifmix.api.core.infra` → `package com.ifmix.api.core.common.infra`
- `package com.ifmix.api.core.modules` → `package com.ifmix.api.core.common.modules`

全部 import 替换（**三个模块全局 sed**）：
- `import com.ifmix.api.core.entity` → `import com.ifmix.api.core.common.entity`
- `import com.ifmix.api.core.infra` → `import com.ifmix.api.core.common.infra`
- `import com.ifmix.api.core.modules` → `import com.ifmix.api.core.common.modules`

### 4. core-customer-api 包名变更

源码从 `com/ifmix/api/core/{bff,config,CoreApplication.kt}` 移到 `com/ifmix/api/core/customer/`：

```bash
cd core-customer-api/src/main/kotlin/com/ifmix/api/core
mkdir customer
mv bff config CoreApplication.kt customer/
mv customer/CoreApplication.kt customer/CustomerApplication.kt
```

测试从 `com/ifmix/api/core/{common,modules,e2e}` 移到 `com/ifmix/api/core/customer/`：

```bash
cd core-customer-api/src/test/kotlin/com/ifmix/api/core
mkdir customer
mv common modules e2e customer/
```

main 源文件 package 替换：
- `package com.ifmix.api.core.bff` → `package com.ifmix.api.core.customer.bff`
- `package com.ifmix.api.core.config` → `package com.ifmix.api.core.customer.config`
- `package com.ifmix.api.core` (仅 Application 文件) → `package com.ifmix.api.core.customer`

test 源文件 package 替换：
- `package com.ifmix.api.core.common` (test 目录下) → `package com.ifmix.api.core.customer.common`
- `package com.ifmix.api.core.modules` (test 目录下) → `package com.ifmix.api.core.customer.modules`
- `package com.ifmix.api.core.e2e` → `package com.ifmix.api.core.customer.e2e`

### 5. core-admin-api 包名变更

当前已是 `com.ifmix.api.admin`，改为 `com.ifmix.api.core.admin`：

```bash
cd core-admin-api/src/main/kotlin/com/ifmix/api
mkdir -p core/admin
mv admin/* core/admin/
rmdir admin
```

- `package com.ifmix.api.admin` → `package com.ifmix.api.core.admin`
- `import com.ifmix.api.admin` → `import com.ifmix.api.core.admin`

### 6. CustomerApplication.kt

```kotlin
package com.ifmix.api.core.customer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.ifmix.api.core.common", "com.ifmix.api.core.customer"])
class CustomerApplication

fun main(args: Array<String>) {
    runApplication<CustomerApplication>(*args)
}
```

### 7. AdminApplication.kt

```kotlin
package com.ifmix.api.core.admin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.ifmix.api.core.common", "com.ifmix.api.core.admin"])
class AdminApplication

fun main(args: Array<String>) {
    runApplication<AdminApplication>(*args)
}
```

### 8. core-customer-api/build.gradle.kts

模块依赖名更新：
```kotlin
dependencies {
    implementation(project(":core-common"))
    // ...
}
```
（这个不变，只是 Gradle 模块名从 `core-api` 变成了 `core-customer-api`，在 settings 里已处理）

### 9. Webhook 归属

Webhook 保留在 core-customer-api（公网入口）。如果当前 webhook controller 在 core-admin-api 中，需要挪回：

```bash
mv core-admin-api/src/main/kotlin/com/ifmix/api/core/admin/bff/webhooks \
   core-customer-api/src/main/kotlin/com/ifmix/api/core/customer/bff/
```

更新 package：`package com.ifmix.api.core.customer.bff.webhooks`

### 10. DTO 文件

`core-customer-api/src/main/dto/` 下的 `.dto` 文件引用 Entity 全限定名需更新：
- Entity 路径从 `com.ifmix.api.core.entity.xxx` → `com.ifmix.api.core.common.entity.xxx`

（.dto 文件的目录结构也对应调整）

### 11. Flyway migration 路径

如果 migration 文件中有 Kotlin 类引用（通常没有），无需改动。`classpath:db/migration` 不受包名变化影响。

### 12. 验证

```bash
./gradlew compileKotlin
./gradlew test
```

## 快速检查清单

全局 sed 完成后做这些 grep 确认无遗漏：

```bash
# 不应该存在旧包名（排除 .gradle/ .idea/ build/）
grep -r "package com.ifmix.api.core.entity" --include="*.kt" core-common/ core-customer-api/ core-admin-api/
grep -r "package com.ifmix.api.core.infra" --include="*.kt" core-common/ core-customer-api/ core-admin-api/
grep -r "package com.ifmix.api.core.modules" --include="*.kt" core-common/ core-customer-api/ core-admin-api/
grep -r "package com.ifmix.api.core.bff" --include="*.kt" core-customer-api/
grep -r "package com.ifmix.api.core.config" --include="*.kt" core-customer-api/
grep -r "package com.ifmix.api.admin" --include="*.kt" core-admin-api/
# 上面的都应该返回空
```
