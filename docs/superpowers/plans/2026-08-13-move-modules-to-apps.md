# modules 从 core-common 移到各 app 模块

## 目标架构

| 模块 | 包含 |
|------|------|
| `core-common` | entity/ + infra/（数据模型 + 基础设施，无业务逻辑） |
| `core-customer-api` | modules/ (除 app/) + dto/ + bff/ |
| `core-admin-api` | modules/app/ + dto/ + bff/ |

## 当前 core-common/modules/ 归属

| 子模块 | 搬到 | 理由 |
|--------|------|------|
| `auth/` | core-customer-api | 登录/token 签发是客户端流程 |
| `scan/` | core-customer-api | 扫描是客户端功能 |
| `ai/` | core-customer-api | AI 扫描是客户端功能 |
| `iap/` | core-customer-api | 购买验证是客户端流程 |
| `todo/` | core-customer-api | 客户端功能 |
| `feedback/` | core-customer-api | 客户端功能 |
| `storage/` | core-customer-api | 客户端上传 |
| `app/` | core-admin-api | App 配置管理是管理端操作 |

## 步骤

### 1. 移动 modules 到 core-customer-api

```bash
# 在 core-customer-api 中创建 modules 目录
mkdir -p core-customer-api/src/main/kotlin/com/ifmix/api/core/customer/modules

# 移动除 app/ 外的所有 modules
mv core-common/src/main/kotlin/com/ifmix/api/core/common/modules/auth \
   core-customer-api/src/main/kotlin/com/ifmix/api/core/customer/modules/
mv core-common/src/main/kotlin/com/ifmix/api/core/common/modules/scan \
   core-customer-api/src/main/kotlin/com/ifmix/api/core/customer/modules/
mv core-common/src/main/kotlin/com/ifmix/api/core/common/modules/ai \
   core-customer-api/src/main/kotlin/com/ifmix/api/core/customer/modules/
mv core-common/src/main/kotlin/com/ifmix/api/core/common/modules/iap \
   core-customer-api/src/main/kotlin/com/ifmix/api/core/customer/modules/
mv core-common/src/main/kotlin/com/ifmix/api/core/common/modules/todo \
   core-customer-api/src/main/kotlin/com/ifmix/api/core/customer/modules/
mv core-common/src/main/kotlin/com/ifmix/api/core/common/modules/feedback \
   core-customer-api/src/main/kotlin/com/ifmix/api/core/customer/modules/
mv core-common/src/main/kotlin/com/ifmix/api/core/common/modules/storage \
   core-customer-api/src/main/kotlin/com/ifmix/api/core/customer/modules/
```

### 2. 移动 modules/app 到 core-admin-api

```bash
mkdir -p core-admin-api/src/main/kotlin/com/ifmix/api/core/admin/modules

mv core-common/src/main/kotlin/com/ifmix/api/core/common/modules/app \
   core-admin-api/src/main/kotlin/com/ifmix/api/core/admin/modules/
```

### 3. 删除 core-common 的空 modules 目录

```bash
rm -rf core-common/src/main/kotlin/com/ifmix/api/core/common/modules
```

### 4. 移动 dto 文件到 core-customer-api

```bash
mv core-common/src/main/dto core-customer-api/src/main/dto
```

注意：AppConfigRevision.dto 需要单独移到 core-admin-api：
```bash
mkdir -p core-admin-api/src/main/dto/com/ifmix/api/core/common/entity/appconfig
mv core-customer-api/src/main/dto/com/ifmix/api/core/common/entity/appconfig/AppConfigRevision.dto \
   core-admin-api/src/main/dto/com/ifmix/api/core/common/entity/appconfig/
```

### 5. 更新 package 声明

**core-customer-api modules：**
- `package com.ifmix.api.core.common.modules.auth` → `package com.ifmix.api.core.customer.modules.auth`
- `package com.ifmix.api.core.common.modules.scan` → `package com.ifmix.api.core.customer.modules.scan`
- `package com.ifmix.api.core.common.modules.ai` → `package com.ifmix.api.core.customer.modules.ai`
- `package com.ifmix.api.core.common.modules.iap` → `package com.ifmix.api.core.customer.modules.iap`
- `package com.ifmix.api.core.common.modules.todo` → `package com.ifmix.api.core.customer.modules.todo`
- `package com.ifmix.api.core.common.modules.feedback` → `package com.ifmix.api.core.customer.modules.feedback`
- `package com.ifmix.api.core.common.modules.storage` → `package com.ifmix.api.core.customer.modules.storage`

**core-admin-api modules：**
- `package com.ifmix.api.core.common.modules.app` → `package com.ifmix.api.core.admin.modules.app`

### 6. 更新 import 语句

全局替换（所有三个模块）：
- `import com.ifmix.api.core.common.modules.auth` → `import com.ifmix.api.core.customer.modules.auth`
- `import com.ifmix.api.core.common.modules.scan` → `import com.ifmix.api.core.customer.modules.scan`
- `import com.ifmix.api.core.common.modules.ai` → `import com.ifmix.api.core.customer.modules.ai`
- `import com.ifmix.api.core.common.modules.iap` → `import com.ifmix.api.core.customer.modules.iap`
- `import com.ifmix.api.core.common.modules.todo` → `import com.ifmix.api.core.customer.modules.todo`
- `import com.ifmix.api.core.common.modules.feedback` → `import com.ifmix.api.core.customer.modules.feedback`
- `import com.ifmix.api.core.common.modules.storage` → `import com.ifmix.api.core.customer.modules.storage`
- `import com.ifmix.api.core.common.modules.app` → `import com.ifmix.api.core.admin.modules.app`

### 7. 处理跨模块依赖

**core-admin-api 的 WebhookController 引用 IapService（现在在 customer 模块）：**

选项 A（推荐）：admin 依赖 customer — 不好，循环风险。
选项 B：把 IapService 中 webhook 需要的方法抽成接口放 core-common，customer 实现。
选项 C：把 webhook 相关的 IapService 代码搬回 core-common。

**实际最简单的做法：** Webhook 本来就要留在 core-customer-api（上面已确认），所以 WebhookController 和 IapService 在同一个模块，无跨模块问题。

确认 WebhookController 当前在哪：
- 如果在 core-admin-api → 移回 core-customer-api
- 如果已在 core-customer-api → 无需处理

**core-admin-api 的 AppConfigController 引用 AppConfigService + AppConfigRevisionRepository：**
这些会随 modules/app 一起移到 core-admin-api，没问题。

**但 AuthService (customer) 调用 appConfigRepo.mustFindCurrentRevision：**
这个跨模块了。解决：将 `AppConfigRevisionRepository` 中被 auth 需要的方法（`mustFindCurrentRevision`, `findByBundleId`, `findByAndroidPackage`）抽为一个接口放 core-common/infra/，admin 提供实现。

或者更简单：把 `AppConfigRevisionRepository`（只是 repo，不含业务逻辑）保留在 core-common。只把 `AppConfigService`（管理端的创建/切换逻辑）放 admin。

**推荐方案：**
- `core-common`：保留 `AppConfigRevisionRepository`（纯数据访问，被多方引用）
- `core-admin-api`：放 `AppConfigService`（管理端业务编排）

### 8. 修正的移动方案

```
core-common/modules/ 中保留：
  app/repo/AppConfigRevisionRepository.kt  ← 被 auth, webhook 引用
  app/repo/AppInfoRepository.kt            ← 被 webhook 引用

core-admin-api/modules/ 中放：
  app/service/AppConfigService.kt          ← 管理端业务
```

### 9. KSP 配置

core-customer-api 的 `build.gradle.kts` 需要开启 dto 处理：
```kotlin
ksp {
    arg("jimmer.language", "kotlin")
    arg("jimmer.dto.dirs", "src/main/dto")
    arg("jimmer.dto.defaultNullableInputModifier", "fuzzy")
}
```

core-admin-api 同理（如果有 admin 专属 .dto 文件）。

core-common 如果没有 .dto 文件了，可以去掉 `jimmer.dto.dirs` 配置。

### 10. 验证

```bash
./gradlew compileKotlin
./gradlew test
```
