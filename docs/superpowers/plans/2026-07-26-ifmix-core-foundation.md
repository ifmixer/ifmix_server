# ifmix core-api 地基 + todo 切片 实现计划（Kotlin + Gradle）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 从零搭建 `ifmix_server` Gradle 多模块仓库与 `core-api` 服务（Kotlin），打通"HTTP 信封 + 请求头/上下文 + 继承式通用 Mongo 抽象（自动租户 + 软删 + 游标分页）+ 集群路由/事务接缝 + todo 模块端到端 + OpenAPI"，为后续模块（antique/iap/auth）提供地基。

**架构：** Spring MVC（阻塞式）+ 虚拟线程；控制器返回裸 DTO，由 `EnvelopeResponseAdvice` 自动包信封，`GlobalExceptionHandler` 统一错误；`RequestContext`（null 安全，`appId` 非空）由参数解析器注入并显式下传；数据层 `BaseRepository → BaseAppRepository（强制 appId）→ BaseAppService`，模块 service 继承复用；文档 `_id` 用 `ObjectId`（对外 hex），时间 `Instant`↔epoch 毫秒；游标分页按 `_id`；集群路由/事务经 `MongoClusterResolver`/`TxRunner` 两个接缝（当前单集群 + Spring 默认事务）。

**技术栈：** Kotlin 2.4.10、Gradle 9.6.1（Kotlin DSL）、JDK 25（jvmToolchain）、Spring Boot 4.1、Spring Data MongoDB、Jakarta Bean Validation、springdoc-openapi 3.0.x、JUnit 5 + AssertJ + Testcontainers(MongoDB)。

---

## 实现修订说明（以代码为准，后于计划正文）

实现后经实测迭代，以下几处相对下方任务正文有更新，**以此为准**：

1. **Jackson 3 Kotlin 模块**（任务 1，已在上方 build 依赖修正）：Spring Boot 4 用 Jackson 3，必须依赖 `tools.jackson.module:jackson-module-kotlin`，否则请求 DTO 的 Kotlin 默认值不生效（缺失的非空默认字段会 400 malformed）。
2. **`CursorQuery` → `CursorQueryInput`**（任务 6）：字段为 `cursor` / `sortBy`(默认 `"_id"`) / `order`(默认 `DESC`) / `limit`；不含 filter/readOptions。测试类为 `CursorQueryInputTest`。
3. **`findMany` → `findByCursor`**（任务 9/11/14）：签名 `findByCursor(ctx, input: CursorQueryInput = CursorQueryInput(), readOptions: ReadOptions = DEFAULT)`（**无 `Query` 参数**）。
   - 本方法自建查询，强制注入 `appId` + 软删，并接管排序/游标/limit/读偏好；不接收外部过滤条件。
   - 支持任意 `sortBy` 字段：非 `_id` 排序用 `(sortBy, _id)` 复合 keyset，游标**自包含**(base64 编码 sortBy 值 + id + 类型标记)，无需回库查锚点。
   - `readOptions` 为服务端参数（不进客户端 DTO）。
4. **删除 `QueryFilter.kt` / `buildFilter`**：过滤改由 `Query` 承载，DSL 不再需要。
5. HTTP action `.../query/todo/findMany` → `.../query/todo/findByCursor`。
6. **`findByCursor` 去掉 `query` 参数**：签名 `findByCursor(ctx, input = CursorQueryInput())`，内部自建查询，不接收外部过滤。
7. **命名 `Base*` → `CRUD*`**（任务 7/9/10/11/13/14 及其测试）：`CRUDDocument`/`CRUDAppDocument`/`CRUDRepository`/`CRUDAppRepository`/`CRUDAppService`。
8. **继承改组合**：`TodoService` 组合持有 `CRUDAppService<TodoDocument>` 并委托，不继承。
9. **读偏好进 `RequestContext`**：新增 `readPreference: ReadPreference = primaryPreferred()`，删除 `ReadOptions`；读方法统一用 `ctx.readPreference`，事务内强制主库。
10. **`updateById(ctx, id, patch: Any)` 自动生成 `$set`**（反射非空属性或 Map），无需手写字段。
11. **DTO 映射改用 Konvert**（`@Konverter interface TodoMapper`，KSP 生成，`Konverter.get()` 取实现），删除手写 mapper；**Kotlin 降至 2.3.10** + KSP 2.3.10 + konvert 4.5.0（任务 1 build 依赖）。

下方任务正文中出现的 `CursorQuery`/`findMany`/`buildFilter`/`filter: Criteria`/`Base*`/`ReadOptions`/Kotlin 2.4.10 字样均以本节为准替换。

## 前置条件（工程师环境）

- **JDK 25** 已安装（`java -version` 显示 25）。Gradle 用 `jvmToolchain(25)` 编译。
- **Gradle 9.6.1**（`gradle -v`）——任务 1 会生成 wrapper，之后统一用 `./gradlew`。
- **Docker 正在运行**（集成测试用 Testcontainers 启动真实 MongoDB；MongoDBContainer 默认起单节点副本集，支持事务）。
- 本地无需手动装 MongoDB——运行/调试可选 `docker run -p 27017:27017 mongo:8.0`，测试由 Testcontainers 自动管理。

## 约定（贯穿全计划）

- **包根**：`com.ifmix.api.core`。主代码在 `core-api/src/main/kotlin/com/ifmix/api/core/...`，测试在 `core-api/src/test/kotlin/com/ifmix/api/core/...`。
- **测试命名**：单元测试与集成测试统一 `*Test` 后缀，`./gradlew :core-api:test` 一次跑完（集成测试内部用 Testcontainers）。抽象基类不带 `Test` 后缀（`AbstractMongoTest`）。
- **Kotlin 校验注解**：请求 DTO 用 `data class`，**必填字段声明为可空 + 默认 null + `@field:NotBlank`**——这样缺字段时 Jackson 能构造对象、再由 Bean Validation 产出字段级 400（而非解析失败）。响应/领域字段按语义决定可空性。
- **常用命令**：
  - 全量测试：`./gradlew :core-api:test`
  - 单个测试类：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.http.EnvelopeTest"`
  - 单个测试方法：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.http.EnvelopeTest.okWrapsData"`
  - 编译主/测试代码：`./gradlew :core-api:compileKotlin` / `:core-api:compileTestKotlin`
  - 启动冒烟：`./gradlew :core-api:bootRun`（用完 Ctrl-C）
- **每个任务结束都 commit**（Conventional Commits，中文正文可）。

## 文件结构（本计划将创建的文件与职责）

```
ifmix_server/
  settings.gradle.kts                                 # rootProject.name + include("core-api")
  build.gradle.kts                                    # 根：插件 apply false + allprojects 公共配置
  gradle/ + gradlew + gradlew.bat                     # wrapper（任务 1 生成）
  core-api/
    build.gradle.kts                                  # 插件 + 依赖 + jvmToolchain(25) + jsr305=strict
    src/main/resources/application.yml                # 虚拟线程、Mongo URI、端口、swagger
    src/main/kotlin/com/ifmix/api/core/
      CoreApplication.kt                              # @SpringBootApplication + main
      common/http/
        Envelope.kt                                   # data class {code,msg,data} + ok()/error()
        ErrorCode.kt                                  # enum：语义码 → 外部码 + HTTP 状态
        ApiError.kt                                   # 携带 ErrorCode 的异常
        RequestContext.kt                             # data class（appId 非空，其余可空）
        ClientPlatform.kt                             # enum android|ios|web + fromHeader
        RequestHeaders.kt                             # object 常量
        GlobalExceptionHandler.kt                     # @RestControllerAdvice 错误→信封
        EnvelopeResponseAdvice.kt                     # ResponseBodyAdvice 成功→信封
        RequestContextArgumentResolver.kt             # 请求头 → RequestContext
        HeaderValidationInterceptor.kt                # 校验 x-app-id 等
      common/db/
        BaseDocument.kt                               # id/createdAt/updatedAt/deletedAt
        BaseAppDocument.kt                            # 追加 appId
        Page.kt / CursorQuery.kt / ReadOptions.kt     # 分页值对象
        QueryFilter.kt                                # findMany 过滤 Criteria 的 DSL 构建器
        BaseRepository.kt                             # MongoTemplate CRUD + 软删 + 游标分页 + 读写分离
        BaseAppRepository.kt                          # 覆写 extraCriteria 注入 appId
        MongoClusterResolver.kt                       # 集群路由接缝（接口）
        DefaultMongoClusterResolver.kt                # 单集群默认实现
      common/service/
        BaseAppService.kt                             # 盖章 appId/时间戳 + 委托
      common/tx/
        TxRunner.kt                                   # withTx 事务边界
      common/config/
        WebConfig.kt                                  # 注册拦截器 + 参数解析器
        MongoConfig.kt                                # 去掉 _class 提示
        TransactionConfig.kt                          # MongoTransactionManager bean
        OpenApiConfig.kt                              # 每 BFF GroupedOpenApi + apiKey
      modules/todo/
        TodoDocument.kt / TodoItem.kt                 # todos 文档 + 内嵌子项
        TodoDtos.kt                                   # 请求/响应 data class
        TodoMapper.kt                                 # 文档 → 响应
        TodoService.kt                                # 继承 BaseAppService + 定制
        TodoConfig.kt                                 # bean 装配（经 resolver 取 template）
      bff/customer/
        CustomerTodoController.kt                     # customer BFF todo 路由
    src/test/kotlin/com/ifmix/api/core/
      support/AbstractMongoTest.kt                    # Testcontainers Mongo 基类
      common/http/EnvelopeTest.kt / ErrorCodeTest.kt / ClientPlatformTest.kt
      common/http/WebLayerTest.kt / RequestContextResolutionTest.kt
      common/db/CursorQueryTest.kt / MongoSerializationTest.kt
      common/db/BaseRepositoryTest.kt / BaseAppRepositoryTest.kt / MongoClusterResolverTest.kt
      common/service/BaseAppServiceTest.kt
      common/tx/TxRunnerTest.kt
      modules/todo/TodoMapperTest.kt / TodoServiceTest.kt
      bff/customer/CustomerTodoControllerTest.kt
      common/config/OpenApiTest.kt
```

---
## 任务 1：Gradle 多模块骨架 + 启动冒烟

**文件：**
- 创建：`settings.gradle.kts`
- 创建：`build.gradle.kts`（根）
- 创建：`core-api/build.gradle.kts`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/CoreApplication.kt`
- 创建：`core-api/src/main/resources/application.yml`
- 创建：`.gitignore`
- 生成：Gradle wrapper（`gradlew` 等）

- [ ] **步骤 1：创建 settings 与根 build 脚本**

创建 `settings.gradle.kts`：

```kotlin
rootProject.name = "ifmix-server"

include("core-api")
```

创建 `build.gradle.kts`：

```kotlin
plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.spring") version "2.4.10" apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.ifmix"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
```

- [ ] **步骤 2：创建 core-api 模块 build 脚本**

创建 `core-api/build.gradle.kts`：

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    // Spring Boot 4 用 Jackson 3（tools.jackson）；必须用 Jackson 3 的 Kotlin 模块，
    // 否则 data class 的 Kotlin 默认值（请求缺失字段）不生效、非空默认字段反序列化失败。
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:mongodb")
    testImplementation("org.testcontainers:junit-jupiter")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

> 说明：`io.spring.dependency-management` 引入 Spring Boot BOM，故 starter 无需写版本。`jackson-module-kotlin` 让 data class 正确 (反)序列化。`kotlin("plugin.spring")` 自动 all-open `@Component`/`@Configuration`/`@RestControllerAdvice`/`@Transactional` 等类。

- [ ] **步骤 3：创建入口**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/CoreApplication.kt`：

```kotlin
package com.ifmix.api.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CoreApplication

fun main(args: Array<String>) {
    runApplication<CoreApplication>(*args)
}
```

- [ ] **步骤 4：创建 application.yml**

创建 `core-api/src/main/resources/application.yml`：

```yaml
spring:
  application:
    name: core-api
  threads:
    virtual:
      enabled: true
  data:
    mongodb:
      uri: ${MONGODB_URI:mongodb://localhost:27017/ifmix_core}
      auto-index-creation: true

server:
  port: ${PORT:3000}

springdoc:
  swagger-ui:
    persist-authorization: true

app:
  expose-errors: ${APP_EXPOSE_ERRORS:true}
```

- [ ] **步骤 5：创建 .gitignore**

创建 `.gitignore`：

```gitignore
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
.idea/
*.iml
.DS_Store
```

- [ ] **步骤 6：生成 Gradle wrapper**

运行：`gradle wrapper --gradle-version 9.6.1`
预期：生成 `gradlew`、`gradlew.bat`、`gradle/wrapper/`。

- [ ] **步骤 7：验证编译**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 8：Commit**

```bash
git add settings.gradle.kts build.gradle.kts core-api/build.gradle.kts core-api/src .gitignore gradlew gradlew.bat gradle
git commit -m "feat: 初始化 ifmix_server Gradle 多模块骨架与 core-api（Kotlin）"
```

---

## 任务 2：信封 Envelope + 错误码 ErrorCode + 异常 ApiError

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/Envelope.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/ErrorCode.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/ApiError.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/http/EnvelopeTest.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/http/ErrorCodeTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/http/EnvelopeTest.kt`：

```kotlin
package com.ifmix.api.core.common.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EnvelopeTest {

    @Test
    fun okWrapsData() {
        val env = Envelope.ok("hello")
        assertThat(env.code).isEqualTo("200000")
        assertThat(env.msg).isEqualTo("success")
        assertThat(env.data).isEqualTo("hello")
    }

    @Test
    fun errorHasNullData() {
        val env = Envelope.error("404000", "not found")
        assertThat(env.code).isEqualTo("404000")
        assertThat(env.msg).isEqualTo("not found")
        assertThat(env.data).isNull()
    }
}
```

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/http/ErrorCodeTest.kt`：

```kotlin
package com.ifmix.api.core.common.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ErrorCodeTest {

    @Test
    fun notFoundMapsTo404() {
        assertThat(ErrorCode.NOT_FOUND.externalCode).isEqualTo("404000")
        assertThat(ErrorCode.NOT_FOUND.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun invalidRequestMapsTo400() {
        assertThat(ErrorCode.INVALID_REQUEST.externalCode).isEqualTo("400000")
        assertThat(ErrorCode.INVALID_REQUEST.status).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.http.EnvelopeTest" --tests "com.ifmix.api.core.common.http.ErrorCodeTest"`
预期：编译失败（`Envelope`/`ErrorCode` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/http/Envelope.kt`：

```kotlin
package com.ifmix.api.core.common.http

/** 统一响应信封：{ code, msg, data }。code 为字符串（如 "200000"）。 */
data class Envelope<out T>(val code: String, val msg: String, val data: T?) {
    companion object {
        fun <T> ok(data: T): Envelope<T> = Envelope("200000", "success", data)
        fun error(code: String, msg: String): Envelope<Nothing> = Envelope(code, msg, null)
    }
}
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/http/ErrorCode.kt`：

```kotlin
package com.ifmix.api.core.common.http

import org.springframework.http.HttpStatus

/** 语义错误码 → 对外字符串码 + HTTP 状态。 */
enum class ErrorCode(val externalCode: String, val status: HttpStatus) {
    INVALID_REQUEST("400000", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("401000", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("403000", HttpStatus.FORBIDDEN),
    NOT_FOUND("404000", HttpStatus.NOT_FOUND),
    RATE_LIMITED("429000", HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL("500000", HttpStatus.INTERNAL_SERVER_ERROR),
}
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/http/ApiError.kt`：

```kotlin
package com.ifmix.api.core.common.http

/** 业务异常：携带 ErrorCode，由 GlobalExceptionHandler 映射为信封。 */
class ApiError(
    val errorCode: ErrorCode,
    message: String = errorCode.name,
) : RuntimeException(message)
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.http.EnvelopeTest" --tests "com.ifmix.api.core.common.http.ErrorCodeTest"`
预期：PASS（4 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/http core-api/src/test/kotlin/com/ifmix/api/core/common/http
git commit -m "feat: 新增 Envelope/ErrorCode/ApiError 基础 HTTP 类型"
```

---
## 任务 3：RequestContext + ClientPlatform + RequestHeaders

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/ClientPlatform.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/RequestContext.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/RequestHeaders.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/http/ClientPlatformTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/http/ClientPlatformTest.kt`：

```kotlin
package com.ifmix.api.core.common.http

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClientPlatformTest {

    @Test
    fun parsesValueCaseInsensitively() {
        assertThat(ClientPlatform.fromHeader("ios")).isEqualTo(ClientPlatform.IOS)
        assertThat(ClientPlatform.fromHeader("ANDROID")).isEqualTo(ClientPlatform.ANDROID)
    }

    @Test
    fun nullOrBlankReturnsNull() {
        assertThat(ClientPlatform.fromHeader(null)).isNull()
        assertThat(ClientPlatform.fromHeader("  ")).isNull()
    }

    @Test
    fun invalidValueThrows() {
        assertThatThrownBy { ClientPlatform.fromHeader("windows") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.http.ClientPlatformTest"`
预期：编译失败（`ClientPlatform` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/http/ClientPlatform.kt`：

```kotlin
package com.ifmix.api.core.common.http

/** 客户端平台枚举，对应请求头 x-client-platform。 */
enum class ClientPlatform {
    ANDROID,
    IOS,
    WEB;

    companion object {
        /** 空/空白返回 null；非法值抛 IllegalArgumentException（调用方转成 400）。 */
        fun fromHeader(raw: String?): ClientPlatform? {
            if (raw.isNullOrBlank()) return null
            return valueOf(raw.trim().uppercase())
        }
    }
}
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/http/RequestContext.kt`：

```kotlin
package com.ifmix.api.core.common.http

/**
 * 不可变请求上下文，显式作为方法参数在 controller → service → repo 之间传递。
 * appId 非空（由请求头校验保证）；其余可空。userId 预留给未来 auth 模块。
 */
data class RequestContext(
    val appId: String,
    val installId: String? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val userId: String? = null,
)
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/http/RequestHeaders.kt`：

```kotlin
package com.ifmix.api.core.common.http

/** 请求头名常量。 */
object RequestHeaders {
    const val APP_ID = "x-app-id"
    const val INSTALL_ID = "x-install-id"
    const val LANG = "x-lang"
    const val CURRENCY = "x-currency"
    const val COUNTRY = "x-country"
    const val NATIVE_VERSION = "x-native-version"
    const val JS_VERSION = "x-js-version"
    const val CLIENT_PLATFORM = "x-client-platform"
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.http.ClientPlatformTest"`
预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/http core-api/src/test/kotlin/com/ifmix/api/core/common/http/ClientPlatformTest.kt
git commit -m "feat: 新增 RequestContext/ClientPlatform/RequestHeaders"
```

---

## 任务 4：全局异常处理 + 信封自动包装

说明：用 `MockMvcBuilders.standaloneSetup` 独立测试（不启动 Spring 上下文、不连 Mongo）。控制器返回裸 DTO，由 `EnvelopeResponseAdvice` 自动包信封；`ApiError`/校验错误由 `GlobalExceptionHandler` 转信封。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/GlobalExceptionHandler.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/EnvelopeResponseAdvice.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/http/WebLayerTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/http/WebLayerTest.kt`：

```kotlin
package com.ifmix.api.core.common.http

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

class WebLayerTest {

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.standaloneSetup(TestController())
            .setControllerAdvice(GlobalExceptionHandler(true), EnvelopeResponseAdvice())
            .build()
    }

    @Test
    fun successResponseIsWrappedInEnvelope() {
        mvc.perform(get("/_test/ok"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("200000"))
            .andExpect(jsonPath("$.msg").value("success"))
            .andExpect(jsonPath("$.data.k").value("v"))
    }

    @Test
    fun apiErrorMapsToEnvelopeWithStatus() {
        mvc.perform(get("/_test/boom"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("404000"))
            .andExpect(jsonPath("$.msg").value("gone"))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    fun validationErrorHasFieldMessage() {
        mvc.perform(
            post("/_test/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("400000"))
            .andExpect(jsonPath("$.msg", containsString("name:")))
    }

    @Test
    fun genericExceptionMapsTo500() {
        mvc.perform(get("/_test/rte"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("500000"))
            .andExpect(jsonPath("$.msg").value("kaboom"))
    }

    @RestController
    class TestController {
        @GetMapping("/_test/ok")
        fun ok(): Map<String, String> = mapOf("k" to "v")

        @GetMapping("/_test/boom")
        fun boom(): Map<String, String> = throw ApiError(ErrorCode.NOT_FOUND, "gone")

        @GetMapping("/_test/rte")
        fun rte(): Map<String, String> = throw RuntimeException("kaboom")

        @PostMapping("/_test/validate")
        fun validate(@Valid @RequestBody p: Payload): Map<String, String> = mapOf("ok" to (p.name ?: ""))

        data class Payload(@field:NotBlank val name: String? = null)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.http.WebLayerTest"`
预期：编译失败（`GlobalExceptionHandler`/`EnvelopeResponseAdvice` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/http/GlobalExceptionHandler.kt`：

```kotlin
package com.ifmix.api.core.common.http

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 统一异常处理：把异常映射为信封响应。 */
@RestControllerAdvice
class GlobalExceptionHandler(
    @param:Value("\${app.expose-errors:true}") private val exposeErrors: Boolean,
) {

    @ExceptionHandler(ApiError::class)
    fun handleApiError(ex: ApiError): ResponseEntity<Envelope<Nothing>> {
        val code = ex.errorCode
        return ResponseEntity.status(code.status)
            .body(Envelope.error(code.externalCode, ex.message ?: code.name))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<Envelope<Nothing>> {
        val msg = ex.bindingResult.fieldErrors
            .map { "${it.field}: ${it.defaultMessage}" }
            .sorted()
            .joinToString("; ")
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Envelope.error(ErrorCode.INVALID_REQUEST.externalCode, msg.ifEmpty { "invalid request" }))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<Envelope<Nothing>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Envelope.error(ErrorCode.INVALID_REQUEST.externalCode, "malformed request body"))

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<Envelope<Nothing>> {
        val msg = if (exposeErrors) (ex.message ?: "error") else "internal error"
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Envelope.error(ErrorCode.INTERNAL.externalCode, msg))
    }
}
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/http/EnvelopeResponseAdvice.kt`：

```kotlin
package com.ifmix.api.core.common.http

import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * 把控制器返回的裸 DTO 自动包成 Envelope。
 * 跳过 String 返回、框架自身控制器（springframework/springdoc）、已是 Envelope 的返回。
 */
@RestControllerAdvice
class EnvelopeResponseAdvice : ResponseBodyAdvice<Any> {

    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>,
    ): Boolean {
        if (StringHttpMessageConverter::class.java.isAssignableFrom(converterType)) return false
        val pkg = returnType.containingClass.packageName
        return !pkg.startsWith("org.springframework") && !pkg.startsWith("org.springdoc")
    }

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? = if (body is Envelope<*>) body else Envelope.ok(body)
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.http.WebLayerTest"`
预期：PASS（4 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/http core-api/src/test/kotlin/com/ifmix/api/core/common/http/WebLayerTest.kt
git commit -m "feat: 全局异常处理与信封自动包装"
```

---
## 任务 5：请求头校验拦截器 + RequestContext 参数解析器 + WebConfig

说明：`HeaderValidationInterceptor` 校验 `x-app-id`（必填 + 合法 ObjectId）与 `x-client-platform`（枚举）；`RequestContextArgumentResolver` 把请求头组装成 `RequestContext` 注入控制器参数。用 standalone MockMvc 同时测两者。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/HeaderValidationInterceptor.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/RequestContextArgumentResolver.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/config/WebConfig.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/http/RequestContextResolutionTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/http/RequestContextResolutionTest.kt`：

```kotlin
package com.ifmix.api.core.common.http

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class RequestContextResolutionTest {

    private val validAppId = "0123456789abcdef01234567"
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.standaloneSetup(CtxController())
            .setCustomArgumentResolvers(RequestContextArgumentResolver())
            .addInterceptors(HeaderValidationInterceptor())
            .setControllerAdvice(GlobalExceptionHandler(true), EnvelopeResponseAdvice())
            .build()
    }

    @Test
    fun resolvesContextFromHeaders() {
        mvc.perform(
            get("/customer/core/query/ctx/echo")
                .header(RequestHeaders.APP_ID, validAppId)
                .header(RequestHeaders.LANG, "en")
                .header(RequestHeaders.CLIENT_PLATFORM, "ios")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.appId").value(validAppId))
            .andExpect(jsonPath("$.data.lang").value("en"))
            .andExpect(jsonPath("$.data.platform").value("IOS"))
    }

    @Test
    fun missingAppIdReturns400() {
        mvc.perform(get("/customer/core/query/ctx/echo"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("400000"))
            .andExpect(jsonPath("$.msg", containsString("x-app-id")))
    }

    @Test
    fun invalidAppIdReturns400() {
        mvc.perform(
            get("/customer/core/query/ctx/echo")
                .header(RequestHeaders.APP_ID, "not-an-objectid")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.msg", containsString("x-app-id")))
    }

    @Test
    fun invalidPlatformReturns400() {
        mvc.perform(
            get("/customer/core/query/ctx/echo")
                .header(RequestHeaders.APP_ID, validAppId)
                .header(RequestHeaders.CLIENT_PLATFORM, "windows")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.msg", containsString("x-client-platform")))
    }

    @RestController
    class CtxController {
        @GetMapping("/customer/core/query/ctx/echo")
        fun echo(ctx: RequestContext): Map<String, Any?> = mapOf(
            "appId" to ctx.appId,
            "lang" to ctx.lang,
            "platform" to ctx.clientPlatform?.name,
        )
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.http.RequestContextResolutionTest"`
预期：编译失败（相关类不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/http/HeaderValidationInterceptor.kt`：

```kotlin
package com.ifmix.api.core.common.http

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/** 校验请求头：x-app-id 必填且为合法 ObjectId；x-client-platform 若存在须为合法枚举。 */
@Component
class HeaderValidationInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val appId = request.getHeader(RequestHeaders.APP_ID)
        if (appId.isNullOrBlank()) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.APP_ID}: required")
        }
        if (!ObjectId.isValid(appId)) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.APP_ID}: invalid input")
        }
        val platform = request.getHeader(RequestHeaders.CLIENT_PLATFORM)
        if (!platform.isNullOrBlank()) {
            try {
                ClientPlatform.fromHeader(platform)
            } catch (e: IllegalArgumentException) {
                throw ApiError(ErrorCode.INVALID_REQUEST, "${RequestHeaders.CLIENT_PLATFORM}: invalid input")
            }
        }
        return true
    }
}
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/http/RequestContextArgumentResolver.kt`：

```kotlin
package com.ifmix.api.core.common.http

import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/** 把校验后的请求头组装成 RequestContext，注入到控制器方法参数。 */
class RequestContextArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == RequestContext::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any = RequestContext(
        appId = header(webRequest, RequestHeaders.APP_ID) ?: "",
        installId = header(webRequest, RequestHeaders.INSTALL_ID),
        lang = header(webRequest, RequestHeaders.LANG),
        currency = header(webRequest, RequestHeaders.CURRENCY),
        country = header(webRequest, RequestHeaders.COUNTRY),
        clientPlatform = ClientPlatform.fromHeader(header(webRequest, RequestHeaders.CLIENT_PLATFORM)),
    )

    private fun header(request: NativeWebRequest, name: String): String? =
        request.getHeader(name)?.takeIf { it.isNotBlank() }
}
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/config/WebConfig.kt`：

```kotlin
package com.ifmix.api.core.common.config

import com.ifmix.api.core.common.http.HeaderValidationInterceptor
import com.ifmix.api.core.common.http.RequestContextArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** 注册请求头校验拦截器（仅 customer/app-admin）与 RequestContext 参数解析器。 */
@Configuration
class WebConfig(
    private val headerValidationInterceptor: HeaderValidationInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(headerValidationInterceptor)
            .addPathPatterns("/customer/**", "/app/**")
            // openapi/swagger 端点无需 appId（本就不在 /customer、/app 下，显式排除以自文档化）
            .excludePathPatterns("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(RequestContextArgumentResolver())
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.http.RequestContextResolutionTest"`
预期：PASS（4 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common core-api/src/test/kotlin/com/ifmix/api/core/common/http/RequestContextResolutionTest.kt
git commit -m "feat: 请求头校验拦截器与 RequestContext 参数解析器"
```

---

## 任务 6：分页值对象 Page + CursorQuery + ReadOptions

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/db/Page.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/db/CursorQuery.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/db/ReadOptions.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/db/CursorQueryTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/db/CursorQueryTest.kt`：

```kotlin
package com.ifmix.api.core.common.db

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CursorQueryTest {

    @Test
    fun nullLimitDefaultsTo20() {
        assertThat(CursorQuery().effectiveLimit()).isEqualTo(20)
    }

    @Test
    fun limitCappedAtMax() {
        assertThat(CursorQuery(limit = 500).effectiveLimit()).isEqualTo(100)
    }

    @Test
    fun limitFlooredAtOne() {
        assertThat(CursorQuery(limit = 0).effectiveLimit()).isEqualTo(1)
        assertThat(CursorQuery(limit = -3).effectiveLimit()).isEqualTo(1)
    }

    @Test
    fun nullOrderDefaultsToDesc() {
        assertThat(CursorQuery().effectiveOrder()).isEqualTo(CursorQuery.Order.DESC)
    }

    @Test
    fun explicitOrderKept() {
        assertThat(CursorQuery(order = CursorQuery.Order.ASC).effectiveOrder())
            .isEqualTo(CursorQuery.Order.ASC)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.db.CursorQueryTest"`
预期：编译失败（`CursorQuery` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/db/Page.kt`：

```kotlin
package com.ifmix.api.core.common.db

/** 游标分页结果。nextCursor 为最后一条的 id（hex），无更多则 null。 */
data class Page<T>(val items: List<T>, val nextCursor: String?, val hasMore: Boolean)
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/db/CursorQuery.kt`：

```kotlin
package com.ifmix.api.core.common.db

/** 游标分页查询参数（对外请求体）。cursor 为上一页最后一条 id。 */
data class CursorQuery(
    val cursor: String? = null,
    val order: Order? = null,
    val limit: Int? = null,
) {
    enum class Order { ASC, DESC }

    fun effectiveLimit(): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    fun effectiveOrder(): Order = order ?: Order.DESC

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
    }
}
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/db/ReadOptions.kt`：

```kotlin
package com.ifmix.api.core.common.db

/**
 * 读选项。
 * preferPrimary：是否强制走主库（写后回读避免副本延迟，read-your-writes）。
 * 未命中语义由方法决定：getById 抛 NOT_FOUND、findById 返回 null（Kotlin null 安全）。
 */
data class ReadOptions(val preferPrimary: Boolean) {
    companion object {
        val DEFAULT = ReadOptions(preferPrimary = false)
        val PRIMARY = ReadOptions(preferPrimary = true)
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.db.CursorQueryTest"`
预期：PASS（5 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/db core-api/src/test/kotlin/com/ifmix/api/core/common/db/CursorQueryTest.kt
git commit -m "feat: 新增 Page/CursorQuery/ReadOptions 分页值对象"
```

---
## 任务 7：文档基类 BaseDocument + BaseAppDocument + todo 文档

说明：文档用可变类（`var` + 默认值，Kotlin 自动生成无参构造，Spring Data 映射友好）。`@Id var id: String?` 由 Spring Data 自动以 `ObjectId` 存入 `_id`、读出转 hex 字符串。本任务纯类定义，用编译验证；行为在任务 8+ 覆盖。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/db/BaseDocument.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/db/BaseAppDocument.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoItem.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDocument.kt`

- [ ] **步骤 1：创建 BaseDocument**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/db/BaseDocument.kt`：

```kotlin
package com.ifmix.api.core.common.db

import org.springframework.data.annotation.Id
import java.time.Instant

/** 所有文档的公共字段：id + 三个时间戳（含软删标记）。 */
abstract class BaseDocument {
    @Id
    var id: String? = null
    var createdAt: Instant? = null
    var updatedAt: Instant? = null
    var deletedAt: Instant? = null
}
```

- [ ] **步骤 2：创建 BaseAppDocument**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/db/BaseAppDocument.kt`：

```kotlin
package com.ifmix.api.core.common.db

/** 租户（app 级）文档基类：追加 appId。所有 app 级集合的文档继承它。 */
abstract class BaseAppDocument : BaseDocument() {
    var appId: String? = null
}
```

- [ ] **步骤 3：创建内嵌子项 TodoItem**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoItem.kt`：

```kotlin
package com.ifmix.api.core.modules.todo

/** 内嵌在 TodoDocument 里的子项。id 应用侧生成，供客户端引用。 */
data class TodoItem(
    var id: String? = null,
    var content: String? = null,
    var done: Boolean = false,
)
```

- [ ] **步骤 4：创建 TodoDocument**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDocument.kt`：

```kotlin
package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.BaseAppDocument
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

/** todos 集合。内嵌 items（聚合边界内、有界，单文档原子读写）。 */
@Document(collection = "todos")
@CompoundIndex(name = "todos_app_id_id_idx", def = "{'appId': 1, '_id': 1}")
class TodoDocument : BaseAppDocument() {
    var title: String? = null
    var done: Boolean = false
    var items: MutableList<TodoItem> = mutableListOf()
}
```

- [ ] **步骤 5：验证编译**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 6：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/db core-api/src/main/kotlin/com/ifmix/api/core/modules/todo
git commit -m "feat: 新增文档基类 BaseDocument/BaseAppDocument 与 todo 文档"
```

---

## 任务 8：Mongo 序列化配置 + Testcontainers 测试基类

说明：引入第一个真实 Mongo 集成测试（**需要 Docker**）。`AbstractMongoTest` 用 `@ServiceConnection` 自动注入容器连接（`@Container` 须在 `companion object` 且 `@JvmStatic`）。`MongoConfig` 去掉 `_class` 类型提示。

> 设计说明：无需自定义 `ObjectId ↔ String`、`Instant ↔ epoch ms` 转换器——`@Id String?` 由 Spring Data 自动以 `ObjectId` 存取，`Instant` 自动存为 BSON `Date`，对外 epoch 毫秒由 `TodoMapper`（任务 12）负责。故 `MongoConfig` 只清 `_class`（YAGNI）。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/config/MongoConfig.kt`
- 创建：`core-api/src/test/kotlin/com/ifmix/api/core/support/AbstractMongoTest.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/db/MongoSerializationTest.kt`

- [ ] **步骤 1：编写测试基类与失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/support/AbstractMongoTest.kt`：

```kotlin
package com.ifmix.api.core.support

import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** 集成测试基类：启动真实 MongoDB（单节点副本集），每个测试后清库。 */
@SpringBootTest
@Testcontainers
abstract class AbstractMongoTest {

    @Autowired
    protected lateinit var mongoTemplate: MongoTemplate

    @AfterEach
    fun dropDatabase() {
        mongoTemplate.db.drop()
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8.0")
    }
}
```

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/db/MongoSerializationTest.kt`：

```kotlin
package com.ifmix.api.core.common.db

import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date

class MongoSerializationTest : AbstractMongoTest() {

    @Test
    fun storesObjectIdAndDateWithoutClassHint() {
        val doc = TodoDocument().apply {
            title = "hello"
            appId = "app-1"
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }

        mongoTemplate.insert(doc)

        assertThat(doc.id).isNotNull().hasSize(24)

        val raw = mongoTemplate.getCollection("todos").find().first()
        assertThat(raw).isNotNull
        assertThat(raw!!["_id"]).isInstanceOf(ObjectId::class.java)
        assertThat(raw.containsKey("_class")).isFalse()
        assertThat(raw["createdAt"]).isInstanceOf(Date::class.java)

        val loaded = mongoTemplate.findById(doc.id!!, TodoDocument::class.java)
        assertThat(loaded?.title).isEqualTo("hello")
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

确保 Docker 正在运行。
运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.db.MongoSerializationTest"`
预期：FAIL——`raw.containsKey("_class")` 为 true，断言失败。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/config/MongoConfig.kt`：

```kotlin
package com.ifmix.api.core.common.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper
import org.springframework.data.mongodb.core.convert.MappingMongoConverter

/** 去掉文档里的 _class 类型提示，保持存储整洁。 */
@Configuration
class MongoConfig {

    @Autowired
    fun removeTypeHint(converter: MappingMongoConverter) {
        converter.setTypeMapper(DefaultMongoTypeMapper(null))
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.db.MongoSerializationTest"`
预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/config/MongoConfig.kt core-api/src/test/kotlin/com/ifmix/api/core/support core-api/src/test/kotlin/com/ifmix/api/core/common/db/MongoSerializationTest.kt
git commit -m "feat: Mongo 去除 _class 提示 + Testcontainers 集成测试基类"
```

---
## 任务 8A：集群路由接缝 MongoClusterResolver

说明：为未来"按 appId 路由到不同 MongoDB 集群"预留接缝。地基默认实现忽略 appId、返回唯一的自动配置 `MongoTemplate`。repo bean 经它取 template（任务 14）。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/db/MongoClusterResolver.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/db/DefaultMongoClusterResolver.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/db/MongoClusterResolverTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/db/MongoClusterResolverTest.kt`：

```kotlin
package com.ifmix.api.core.common.db

import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate

class MongoClusterResolverTest : AbstractMongoTest() {

    @Autowired
    lateinit var resolver: MongoClusterResolver

    @Autowired
    lateinit var autoConfiguredTemplate: MongoTemplate

    @Test
    fun defaultResolverReturnsSingleTemplateRegardlessOfAppId() {
        assertThat(resolver.primary()).isSameAs(autoConfiguredTemplate)
        assertThat(resolver.forAppId("app-1")).isSameAs(autoConfiguredTemplate)
        assertThat(resolver.forAppId("app-2")).isSameAs(autoConfiguredTemplate)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.db.MongoClusterResolverTest"`
预期：编译失败（`MongoClusterResolver` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/db/MongoClusterResolver.kt`：

```kotlin
package com.ifmix.api.core.common.db

import org.springframework.data.mongodb.core.MongoTemplate

/**
 * 集群路由接缝：未来按 appId 路由到不同 MongoDB 集群。
 * 地基默认实现忽略 appId。真要多集群时替换实现即可，业务层零改动。
 */
interface MongoClusterResolver {
    /** 按 appId 返回对应集群的 template。 */
    fun forAppId(appId: String): MongoTemplate

    /** 默认/主集群 template（用于无 appId 的 bean 装配场景）。 */
    fun primary(): MongoTemplate
}
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/db/DefaultMongoClusterResolver.kt`：

```kotlin
package com.ifmix.api.core.common.db

import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component

/** 单集群默认实现：忽略 appId，始终返回自动配置的唯一 MongoTemplate。 */
@Component
class DefaultMongoClusterResolver(
    private val template: MongoTemplate,
) : MongoClusterResolver {

    override fun forAppId(appId: String): MongoTemplate = template

    override fun primary(): MongoTemplate = template
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.db.MongoClusterResolverTest"`
预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/db/MongoClusterResolver.kt core-api/src/main/kotlin/com/ifmix/api/core/common/db/DefaultMongoClusterResolver.kt core-api/src/test/kotlin/com/ifmix/api/core/common/db/MongoClusterResolverTest.kt
git commit -m "feat: 集群路由接缝 MongoClusterResolver（单集群默认实现）"
```

---

## 任务 8B：事务接缝 TxRunner + MongoTransactionManager

说明：事务用 Spring 默认（`MongoTransactionManager` + `TransactionTemplate`，需副本集，Testcontainers 已满足）。业务经 `TxRunner.withTx(ctx) { ... }` 进入事务边界；跨函数事务让它们在同一边界内执行。`body` 接收 ctx（当前直通，未来多集群时在此注入 session），签名前瞻。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/config/TransactionConfig.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/tx/TxRunner.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/tx/TxRunnerTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/tx/TxRunnerTest.kt`：

```kotlin
package com.ifmix.api.core.common.tx

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

class TxRunnerTest : AbstractMongoTest() {

    private val ctx = RequestContext(appId = "app-tx")

    @Autowired
    lateinit var txRunner: TxRunner

    @BeforeEach
    fun ensureCollection() {
        // 事务中隐式建集合在部分版本会失败，测试前先确保集合存在
        if (!mongoTemplate.collectionExists(TodoDocument::class.java)) {
            mongoTemplate.createCollection(TodoDocument::class.java)
        }
    }

    private fun todo(t: String) = TodoDocument().apply {
        title = t
        appId = "app-tx"
        createdAt = Instant.now()
        updatedAt = Instant.now()
    }

    @Test
    fun commitPersistsAllWrites() {
        txRunner.withTx(ctx) {
            mongoTemplate.insert(todo("a"))
            mongoTemplate.insert(todo("b"))
        }
        assertThat(mongoTemplate.findAll(TodoDocument::class.java)).hasSize(2)
    }

    @Test
    fun rollbackDiscardsAllWrites() {
        assertThatThrownBy {
            txRunner.withTx<Unit>(ctx) {
                mongoTemplate.insert(todo("a"))
                throw RuntimeException("boom")
            }
        }.isInstanceOf(RuntimeException::class.java)

        assertThat(mongoTemplate.findAll(TodoDocument::class.java)).isEmpty()
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.tx.TxRunnerTest"`
预期：编译失败（`TxRunner` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/config/TransactionConfig.kt`：

```kotlin
package com.ifmix.api.core.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager

/** 注册 Mongo 事务管理器（需副本集）。 */
@Configuration
class TransactionConfig {

    @Bean
    fun mongoTransactionManager(factory: MongoDatabaseFactory): MongoTransactionManager =
        MongoTransactionManager(factory)
}
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/tx/TxRunner.kt`：

```kotlin
package com.ifmix.api.core.common.tx

import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * 事务边界接缝。业务通过 withTx 进入事务；跨多个函数的事务让它们在同一 withTx 内执行。
 * 当前 session 由 Spring 线程绑定管理，ctx 直通；未来多集群时改为在此注入手动 session。
 */
@Component
class TxRunner(txManager: MongoTransactionManager) {

    private val txTemplate = TransactionTemplate(txManager)

    fun <R> withTx(ctx: RequestContext, body: (RequestContext) -> R): R =
        txTemplate.execute { body(ctx) }!!
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.tx.TxRunnerTest"`
预期：PASS（2 个测试通过）。若因"事务中建集合"报错，确认 `@BeforeEach` 的 `createCollection` 已生效。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/config/TransactionConfig.kt core-api/src/main/kotlin/com/ifmix/api/core/common/tx/TxRunner.kt core-api/src/test/kotlin/com/ifmix/api/core/common/tx/TxRunnerTest.kt
git commit -m "feat: 事务接缝 TxRunner + MongoTransactionManager"
```

---
## 任务 9：通用仓储 BaseRepository（CRUD + 软删 + 游标分页）

说明：数据层核心，基于 `MongoTemplate`，不关注租户。Kotlin null 安全下拆两个读方法：`findById` 返回 `T?`（未命中/非法 id 返回 null，绝不抛），`getById` 返回非空 `T`（未命中抛 NOT_FOUND）。`updateById`/`deleteById` 对非法 id 返回 false。类与钩子声明 `open` 供 `BaseAppRepository` 继承覆写。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/db/BaseRepository.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/db/BaseRepositoryTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/db/BaseRepositoryTest.kt`：

```kotlin
package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class BaseRepositoryTest : AbstractMongoTest() {

    private val ctx = RequestContext(appId = "app-1")
    private lateinit var repo: BaseRepository<TodoDocument>

    @BeforeEach
    fun init() {
        repoCtx = BaseRepository(mongoTemplate, TodoDocument::class.java, softDelete = true)
    }

    private fun insertTodo(title: String): String {
        val d = TodoDocument().apply {
            this.title = title
            appId = "app-1"
            val now = Instant.now()
            createdAt = now
            updatedAt = now
        }
        repo.insertOne(ctx, d)
        return d.id!!
    }

    @Test
    fun insertThenGetById() {
        val id = insertTodo("hello")
        assertThat(repo.getById(ctx, id).title).isEqualTo("hello")
    }

    @Test
    fun getByIdMissingThrowsNotFound() {
        val missing = ObjectId().toHexString()
        assertThatThrownBy { repo.getById(ctx, missing) }.isInstanceOf(ApiError::class.java)
    }

    @Test
    fun findByIdMissingReturnsNull() {
        assertThat(repo.findById(ctx, ObjectId().toHexString())).isNull()
    }

    @Test
    fun invalidIdHandledGracefully() {
        assertThat(repo.findById(ctx, "not-an-objectid")).isNull()
        assertThat(repo.updateById(ctx, "not-an-objectid", mapOf("title" to "x"))).isFalse()
        assertThat(repo.deleteById(ctx, "not-an-objectid")).isFalse()
    }

    @Test
    fun updateByIdModifiesFields() {
        val id = insertTodo("old")
        assertThat(repo.updateById(ctx, id, mapOf("title" to "new"))).isTrue()
        assertThat(repo.getById(ctx, id).title).isEqualTo("new")
    }

    @Test
    fun updateByIdMissingReturnsFalse() {
        assertThat(repo.updateById(ctx, ObjectId().toHexString(), mapOf("title" to "x"))).isFalse()
    }

    @Test
    fun softDeleteHidesFromFindAndList() {
        val id = insertTodo("x")
        assertThat(repo.deleteById(ctx, id)).isTrue()
        assertThat(repo.findById(ctx, id)).isNull()
        assertThat(repo.findMany(ctx, CursorQuery(limit = 10)).items).isEmpty()
    }

    @Test
    fun findManyPaginatesDescendingById() {
        val id1 = insertTodo("a")
        val id2 = insertTodo("b")
        val id3 = insertTodo("c")

        val p1 = repo.findMany(ctx, CursorQuery(order = CursorQuery.Order.DESC, limit = 2))
        assertThat(p1.items.map { it.id }).containsExactly(id3, id2)
        assertThat(p1.hasMore).isTrue()
        assertThat(p1.nextCursor).isEqualTo(id2)

        val p2 = repo.findMany(ctx, CursorQuery(cursor = p1.nextCursor, order = CursorQuery.Order.DESC, limit = 2))
        assertThat(p2.items.map { it.id }).containsExactly(id1)
        assertThat(p2.hasMore).isFalse()
        assertThat(p2.nextCursor).isNull()
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.db.BaseRepositoryTest"`
预期：编译失败（`BaseRepository` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/db/BaseRepository.kt`：

```kotlin
package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.mongodb.ReadPreference
import org.bson.types.ObjectId
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

/** 基于 MongoTemplate 的通用 CRUD 基类，不关注租户。 */
open class BaseRepository<T : BaseDocument>(
    protected val mongo: MongoTemplate,
    protected val type: Class<T>,
    protected val softDelete: Boolean,
) {

    /** 子类覆写以注入额外过滤（如租户）。默认无。 */
    protected open fun extraCriteria(ctx: RequestContext): Criteria? = null

    fun insertOne(ctx: RequestContext, entity: T) {
        mongo.insert(entity)
    }

    fun insertMany(ctx: RequestContext, entities: Collection<T>) {
        if (entities.isNotEmpty()) mongo.insert(entities, type)
    }

    /** 未命中/非法 id 返回 null，绝不抛。 */
    fun findById(ctx: RequestContext, id: String, options: ReadOptions = ReadOptions.DEFAULT): T? {
        if (invalidId(id)) return null
        val query = buildQuery(idCriteria(ctx, id))
        applyReadPreference(query, options)
        return mongo.findOne(query, type)
    }

    /** 未命中抛 NOT_FOUND，返回非空。 */
    fun getById(ctx: RequestContext, id: String, options: ReadOptions = ReadOptions.DEFAULT): T =
        findById(ctx, id, options) ?: throw ApiError(ErrorCode.NOT_FOUND)

    fun updateById(ctx: RequestContext, id: String, patch: Map<String, Any?>): Boolean {
        if (invalidId(id)) return false
        val query = buildQuery(idCriteria(ctx, id))
        val update = Update()
        patch.forEach { (k, v) -> update.set(k, v) }
        update.set("updatedAt", Instant.now())
        return mongo.updateFirst(query, update, type).modifiedCount > 0
    }

    fun deleteById(ctx: RequestContext, id: String): Boolean {
        if (invalidId(id)) return false
        val query = buildQuery(idCriteria(ctx, id))
        return if (softDelete) {
            val update = Update().set("deletedAt", Instant.now()).set("updatedAt", Instant.now())
            mongo.updateFirst(query, update, type).modifiedCount > 0
        } else {
            mongo.remove(query, type).deletedCount > 0
        }
    }

    fun findMany(
        ctx: RequestContext,
        cursorQuery: CursorQuery,
        filter: Criteria? = null,
        options: ReadOptions = ReadOptions.DEFAULT,
    ): Page<T> {
        val limit = cursorQuery.effectiveLimit()
        val order = cursorQuery.effectiveOrder()

        val criteria = baseCriteria(ctx).toMutableList()
        filter?.let { criteria.add(it) }
        val cursor = cursorQuery.cursor
        if (!cursor.isNullOrBlank() && ObjectId.isValid(cursor)) {
            val cursorId = ObjectId(cursor)
            criteria.add(
                if (order == CursorQuery.Order.DESC) Criteria.where("_id").lt(cursorId)
                else Criteria.where("_id").gt(cursorId),
            )
        }

        val direction = if (order == CursorQuery.Order.DESC) Sort.Direction.DESC else Sort.Direction.ASC
        val query = buildQuery(criteria)
            .with(Sort.by(direction, "_id"))
            .limit(limit + 1)
        applyReadPreference(query, options)

        val rows = mongo.find(query, type)
        val hasMore = rows.size > limit
        val items = if (hasMore) rows.subList(0, limit) else rows
        val nextCursor = if (hasMore) items.last().id else null
        return Page(items.toList(), nextCursor, hasMore)
    }

    // ---- helpers ----

    /**
     * 读写分离：默认读走 secondaryPreferred（从库，无从库回落主库）。
     * 以下情况强制 primary：显式 preferPrimary（写后回读 read-your-writes）、或处于事务中（Mongo 事务要求主库读）。
     */
    private fun applyReadPreference(query: Query, options: ReadOptions) {
        val forcePrimary = options.preferPrimary || TransactionSynchronizationManager.isActualTransactionActive()
        query.withReadPreference(
            if (forcePrimary) ReadPreference.primary() else ReadPreference.secondaryPreferred(),
        )
    }

    /** 基础过滤：extraCriteria（如租户）+ 软删过滤。 */
    protected fun baseCriteria(ctx: RequestContext): List<Criteria> {
        val list = mutableListOf<Criteria>()
        extraCriteria(ctx)?.let { list.add(it) }
        if (softDelete) list.add(Criteria.where("deletedAt").`is`(null))
        return list
    }

    private fun idCriteria(ctx: RequestContext, id: String): List<Criteria> =
        baseCriteria(ctx) + Criteria.where("_id").`is`(ObjectId(id))

    private fun buildQuery(criteria: List<Criteria>): Query {
        val query = Query()
        when {
            criteria.size == 1 -> query.addCriteria(criteria[0])
            criteria.size > 1 -> query.addCriteria(Criteria().andOperator(*criteria.toTypedArray()))
        }
        return query
    }

    private fun invalidId(id: String?): Boolean = id == null || !ObjectId.isValid(id)
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.db.BaseRepositoryTest"`
预期：PASS（8 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/db/BaseRepository.kt core-api/src/test/kotlin/com/ifmix/api/core/common/db/BaseRepositoryTest.kt
git commit -m "feat: BaseRepository 通用 CRUD + 软删 + 游标分页"
```

---

## 任务 10：租户仓储 BaseAppRepository（自动注入 appId）

说明：覆写 `extraCriteria` 强制追加 `appId = ctx.appId`，实现"默认安全、无法绕过"的租户隔离。测试验证跨租户读/写/删被隔离。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/db/BaseAppRepository.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/db/BaseAppRepositoryTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/db/BaseAppRepositoryTest.kt`：

```kotlin
package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class BaseAppRepositoryTest : AbstractMongoTest() {

    private val app1 = RequestContext(appId = "app-1")
    private val app2 = RequestContext(appId = "app-2")
    private lateinit var repo: BaseAppRepository<TodoDocument>

    @BeforeEach
    fun init() {
        repoCtx = BaseAppRepository(mongoTemplate, TodoDocument::class.java, softDelete = true)
    }

    private fun insert(ctx: RequestContext, title: String): String {
        val d = TodoDocument().apply {
            this.title = title
            appId = ctx.appId
            val now = Instant.now()
            createdAt = now
            updatedAt = now
        }
        repo.insertOne(ctx, d)
        return d.id!!
    }

    @Test
    fun findByIdIsolatedByTenant() {
        val id = insert(app1, "secret")
        assertThat(repo.findById(app2, id)).isNull()
        assertThat(repo.getById(app1, id).title).isEqualTo("secret")
    }

    @Test
    fun findManyIsolatedByTenant() {
        insert(app1, "a1")
        insert(app2, "b1")
        insert(app2, "b2")
        val page = repo.findMany(app2, CursorQuery(limit = 10))
        assertThat(page.items.map { it.title }).containsExactlyInAnyOrder("b1", "b2")
    }

    @Test
    fun crossTenantUpdateAndDeleteAreNoop() {
        val id = insert(app1, "x")
        assertThat(repo.updateById(app2, id, mapOf("title" to "hacked"))).isFalse()
        assertThat(repo.deleteById(app2, id)).isFalse()
        assertThat(repo.getById(app1, id).title).isEqualTo("x")
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.db.BaseAppRepositoryTest"`
预期：编译失败（`BaseAppRepository` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/db/BaseAppRepository.kt`：

```kotlin
package com.ifmix.api.core.common.db

import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria

/** 租户仓储：每次查询强制注入 appId = ctx.appId。app 级集合一律用它。 */
open class BaseAppRepository<T : BaseAppDocument>(
    mongo: MongoTemplate,
    type: Class<T>,
    softDelete: Boolean,
) : BaseRepository<T>(mongo, type, softDelete) {

    override fun extraCriteria(ctx: RequestContext): Criteria =
        Criteria.where("appId").`is`(ctx.appId)
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.db.BaseAppRepositoryTest"`
预期：PASS（3 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/db/BaseAppRepository.kt core-api/src/test/kotlin/com/ifmix/api/core/common/db/BaseAppRepositoryTest.kt
git commit -m "feat: BaseAppRepository 强制 appId 租户隔离"
```

---
## 任务 11：通用服务 BaseAppService

说明：`createOne` 插入前盖章 `appId` + 时间戳，返回生成的 id；其余方法委托 `BaseAppRepository`。类声明 `open` 供模块 service 继承（任务 13）。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/service/BaseAppService.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/service/BaseAppServiceTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/service/BaseAppServiceTest.kt`：

```kotlin
package com.ifmix.api.core.common.service

import com.ifmix.api.core.common.db.BaseAppRepository
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BaseAppServiceTest : AbstractMongoTest() {

    private val ctx = RequestContext(appId = "app-9")
    private lateinit var service: BaseAppService<TodoDocument>

    @BeforeEach
    fun init() {
        service = BaseAppService(BaseAppRepository(mongoTemplate, TodoDocument::class.java, softDelete = true))
    }

    @Test
    fun createOneStampsTenantAndTimestamps() {
        val id = service.createOne(ctx, TodoDocument().apply { title = "t" })

        val saved = service.getById(ctx, id)
        assertThat(saved.appId).isEqualTo("app-9")
        assertThat(saved.createdAt).isNotNull
        assertThat(saved.updatedAt).isNotNull
        assertThat(saved.deletedAt).isNull()
    }

    @Test
    fun updateByIdDelegatesToRepo() {
        val id = service.createOne(ctx, TodoDocument().apply { title = "old" })
        assertThat(service.updateById(ctx, id, mapOf("title" to "new"))).isTrue()
        assertThat(service.getById(ctx, id).title).isEqualTo("new")
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.service.BaseAppServiceTest"`
预期：编译失败（`BaseAppService` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/service/BaseAppService.kt`：

```kotlin
package com.ifmix.api.core.common.service

import com.ifmix.api.core.common.db.BaseAppDocument
import com.ifmix.api.core.common.db.BaseAppRepository
import com.ifmix.api.core.common.db.CursorQuery
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.db.ReadOptions
import com.ifmix.api.core.common.http.RequestContext
import org.springframework.data.mongodb.core.query.Criteria
import java.time.Instant

/** 通用租户服务基类：模块 service 继承它复用 CRUD，仅覆写定制点。 */
open class BaseAppService<T : BaseAppDocument>(
    protected val repo: BaseAppRepository<T>,
) {

    /** 盖章 appId + 时间戳后插入，返回 Mongo 生成的 id。 */
    fun createOne(ctx: RequestContext, entity: T): String {
        val now = Instant.now()
        entity.appId = ctx.appId
        entity.createdAt = now
        entity.updatedAt = now
        entity.deletedAt = null
        repo.insertOne(ctx, entity)
        return entity.id!!
    }

    fun findById(ctx: RequestContext, id: String, options: ReadOptions = ReadOptions.DEFAULT): T? =
        repo.findById(ctx, id, options)

    fun getById(ctx: RequestContext, id: String, options: ReadOptions = ReadOptions.DEFAULT): T =
        repo.getById(ctx, id, options)

    fun updateById(ctx: RequestContext, id: String, patch: Map<String, Any?>): Boolean =
        repo.updateById(ctx, id, patch)

    fun deleteById(ctx: RequestContext, id: String): Boolean =
        repo.deleteById(ctx, id)

    fun findMany(
        ctx: RequestContext,
        cursorQuery: CursorQuery,
        filter: Criteria? = null,
        options: ReadOptions = ReadOptions.DEFAULT,
    ): Page<T> =
        repo.findMany(ctx, cursorQuery, filter, options)
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.service.BaseAppServiceTest"`
预期：PASS（2 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/service/BaseAppService.kt core-api/src/test/kotlin/com/ifmix/api/core/common/service/BaseAppServiceTest.kt
git commit -m "feat: BaseAppService 盖章与委托"
```

---

## 任务 12：Todo DTO + 文档→响应映射（TodoMapper）

说明：DTO 用 `data class`，Kotlin 允许单文件多顶层类。请求必填字段声明为**可空 + 默认 null + `@field:` 校验注解**（缺字段时能构造对象、再由 Bean Validation 产出字段级 400）。`TodoMapper` 把 `Instant` 转 epoch 毫秒。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDtos.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoMapper.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/modules/todo/TodoMapperTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/modules/todo/TodoMapperTest.kt`：

```kotlin
package com.ifmix.api.core.modules.todo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class TodoMapperTest {

    @Test
    fun mapsDocumentToResponseWithEpochMillis() {
        val doc = TodoDocument().apply {
            id = "aaaaaaaaaaaaaaaaaaaaaaaa"
            title = "t"
            done = true
            createdAt = Instant.ofEpochMilli(1000)
            updatedAt = Instant.ofEpochMilli(2000)
            items = mutableListOf(TodoItem(id = "i1", content = "c", done = false))
        }

        val r = TodoMapper.toResponse(doc)

        assertThat(r.id).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaa")
        assertThat(r.title).isEqualTo("t")
        assertThat(r.done).isTrue()
        assertThat(r.createdAt).isEqualTo(1000L)
        assertThat(r.updatedAt).isEqualTo(2000L)

        val item = r.items.single()
        assertThat(item.id).isEqualTo("i1")
        assertThat(item.content).isEqualTo("c")
        assertThat(item.done).isFalse()
    }

    @Test
    fun nullTimestampsMapToZero() {
        val doc = TodoDocument().apply {
            id = "bbbbbbbbbbbbbbbbbbbbbbbb"
            title = "t"
        }
        val r = TodoMapper.toResponse(doc)
        assertThat(r.createdAt).isZero()
        assertThat(r.updatedAt).isZero()
        assertThat(r.items).isEmpty()
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.modules.todo.TodoMapperTest"`
预期：编译失败（`TodoDtos`/`TodoMapper` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDtos.kt`：

```kotlin
package com.ifmix.api.core.modules.todo

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** todo 模块请求/响应 DTO。时间字段对外为 epoch 毫秒。 */

data class CreateTodoItem(
    @field:NotBlank @field:Size(max = 1000) val content: String? = null,
)

data class CreateTodoRequest(
    @field:NotBlank @field:Size(max = 255) val title: String? = null,
    @field:Valid val items: List<CreateTodoItem>? = null,
)

data class UpdateTodoRequest(
    @field:Size(max = 255) val title: String? = null,
    val done: Boolean? = null,
)

data class UpdateOneTodoRequest(
    @field:NotBlank val id: String? = null,
    @field:NotNull @field:Valid val patch: UpdateTodoRequest? = null,
)

data class ByIdRequest(
    @field:NotBlank val id: String? = null,
)

data class TodoItemResponse(val id: String?, val content: String?, val done: Boolean)

data class TodoResponse(
    val id: String?,
    val title: String?,
    val done: Boolean,
    val items: List<TodoItemResponse>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class DeleteResult(val deleted: Boolean)
```

创建 `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoMapper.kt`：

```kotlin
package com.ifmix.api.core.modules.todo

/** 文档 → 响应 DTO 映射（含 Instant → epoch 毫秒）。 */
object TodoMapper {

    fun toResponse(doc: TodoDocument): TodoResponse =
        TodoResponse(
            id = doc.id,
            title = doc.title,
            done = doc.done,
            items = doc.items.map { TodoItemResponse(it.id, it.content, it.done) },
            createdAt = doc.createdAt?.toEpochMilli() ?: 0L,
            updatedAt = doc.updatedAt?.toEpochMilli() ?: 0L,
        )
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.modules.todo.TodoMapperTest"`
预期：PASS（2 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDtos.kt core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoMapper.kt core-api/src/test/kotlin/com/ifmix/api/core/modules/todo/TodoMapperTest.kt
git commit -m "feat: todo DTO 与文档映射"
```

---
## 任务 13：TodoService（继承 BaseAppService，定制 create/update）

说明：`TodoService : BaseAppService<TodoDocument>`，直接继承 `getById/findById/findMany/deleteById/updateById`，仅新增带内嵌 items 的 `create` 与部分更新 `update`。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoService.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/modules/todo/TodoServiceTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/modules/todo/TodoServiceTest.kt`：

```kotlin
package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.BaseAppRepository
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.support.AbstractMongoTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TodoServiceTest : AbstractMongoTest() {

    private val ctx = RequestContext(appId = "app-7")
    private lateinit var service: TodoService

    @BeforeEach
    fun init() {
        service = TodoService(BaseAppRepository(mongoTemplate, TodoDocument::class.java, softDelete = true))
    }

    @Test
    fun createEmbedsItemsWithGeneratedIds() {
        val id = service.create(
            ctx,
            CreateTodoRequest("shopping", listOf(CreateTodoItem("milk"), CreateTodoItem("eggs"))),
        )

        val doc = service.getById(ctx, id)
        assertThat(doc.title).isEqualTo("shopping")
        assertThat(doc.done).isFalse()
        assertThat(doc.appId).isEqualTo("app-7")
        assertThat(doc.items).hasSize(2)
        assertThat(doc.items).allSatisfy { assertThat(it.id).isNotBlank() }
        assertThat(doc.items.map { it.content }).containsExactly("milk", "eggs")
    }

    @Test
    fun createWithNullItemsGivesEmptyList() {
        val id = service.create(ctx, CreateTodoRequest("t", null))
        assertThat(service.getById(ctx, id).items).isEmpty()
    }

    @Test
    fun updatePartialSetsOnlyProvidedFields() {
        val id = service.create(ctx, CreateTodoRequest("keep-title", null))

        assertThat(service.update(ctx, id, UpdateTodoRequest(done = true))).isTrue()

        val doc = service.getById(ctx, id)
        assertThat(doc.done).isTrue()
        assertThat(doc.title).isEqualTo("keep-title")
    }

    @Test
    fun inheritedDeleteSoftDeletes() {
        val id = service.create(ctx, CreateTodoRequest("t", null))
        assertThat(service.deleteById(ctx, id)).isTrue()
        assertThat(service.findById(ctx, id)).isNull()
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.modules.todo.TodoServiceTest"`
预期：编译失败（`TodoService` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoService.kt`：

```kotlin
package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.BaseAppRepository
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.service.BaseAppService
import org.bson.types.ObjectId

/** todo 业务逻辑：继承通用 CRUD，仅定制带内嵌 items 的创建与 patch 更新。 */
class TodoService(repo: BaseAppRepository<TodoDocument>) : BaseAppService<TodoDocument>(repo) {

    /** 创建 todo（内嵌 items 单文档原子写），返回新 id。 */
    fun create(ctx: RequestContext, req: CreateTodoRequest): String {
        val doc = TodoDocument().apply {
            title = req.title
            done = false
            items = (req.items ?: emptyList()).map {
                TodoItem(id = ObjectId().toHexString(), content = it.content, done = false)
            }.toMutableList()
        }
        return createOne(ctx, doc) // 继承自 BaseAppService：盖章 appId/时间戳 + 插入
    }

    /** 部分更新：仅设置提供的字段；空 patch 时校验存在性后视为命中。 */
    fun update(ctx: RequestContext, id: String, patch: UpdateTodoRequest): Boolean {
        val set = mutableMapOf<String, Any?>()
        patch.title?.let { set["title"] = it }
        patch.done?.let { set["done"] = it }
        if (set.isEmpty()) {
            getById(ctx, id) // 不存在则抛 NOT_FOUND
            return true
        }
        return updateById(ctx, id, set)
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.modules.todo.TodoServiceTest"`
预期：PASS（4 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoService.kt core-api/src/test/kotlin/com/ifmix/api/core/modules/todo/TodoServiceTest.kt
git commit -m "feat: TodoService 继承复用 + 内嵌 items 创建/更新"
```

---

## 任务 14：Todo bean 装配 + CustomerTodoController + 全栈端到端测试

说明：定义 `todoRepository`/`todoService` bean（template 经 `MongoClusterResolver.primary()` 取），编写 customer BFF 控制器（`PUT`=query、`POST`=mutation，URL 含 `query`/`mutation` 段）。用 `@SpringBootTest + @AutoConfigureMockMvc + Testcontainers` 打通信封、请求头校验、租户隔离、软删完整链路。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoConfig.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerTodoController.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/bff/customer/CustomerTodoControllerTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/bff/customer/CustomerTodoControllerTest.kt`：

```kotlin
package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.http.RequestHeaders
import com.ifmix.api.core.support.AbstractMongoTest
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class CustomerTodoControllerTest : AbstractMongoTest() {

    @Autowired
    private lateinit var mvc: MockMvc

    private fun createTodo(body: String): String {
        val resp = mvc.perform(
            post("/customer/core/mutation/todo/createOne")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("200000"))
            .andReturn().response.contentAsString
        return JsonPath.read(resp, "$.data.id")
    }

    @Test
    fun createThenGetByIdReturnsEnvelope() {
        val id = createTodo("""{"title":"shopping","items":[{"content":"milk"}]}""")
        mvc.perform(
            put("/customer/core/query/todo/getById")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"id":"$id"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.title").value("shopping"))
            .andExpect(jsonPath("$.data.items[0].content").value("milk"))
            .andExpect(jsonPath("$.data.items[0].id").isNotEmpty)
            .andExpect(jsonPath("$.data.createdAt").isNumber)
    }

    @Test
    fun missingAppIdHeaderRejected() {
        mvc.perform(
            post("/customer/core/mutation/todo/createOne")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"x"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.msg", containsString("x-app-id")))
    }

    @Test
    fun blankTitleFailsValidation() {
        mvc.perform(
            post("/customer/core/mutation/todo/createOne")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":""}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("400000"))
            .andExpect(jsonPath("$.msg", containsString("title")))
    }

    @Test
    fun tenantIsolationHidesOtherAppTodo() {
        val id = createTodo("""{"title":"secret"}""")
        mvc.perform(
            put("/customer/core/query/todo/getById")
                .header(RequestHeaders.APP_ID, OTHER_APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"id":"$id"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("404000"))
    }

    @Test
    fun deleteSoftDeletesAndSubsequentGetIs404() {
        val id = createTodo("""{"title":"t"}""")
        mvc.perform(
            post("/customer/core/mutation/todo/deleteById")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"id":"$id"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deleted").value(true))

        mvc.perform(
            put("/customer/core/query/todo/getById")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"id":"$id"}"""),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun findManyReturnsPageEnvelope() {
        createTodo("""{"title":"a"}""")
        createTodo("""{"title":"b"}""")
        mvc.perform(
            put("/customer/core/query/todo/findMany")
                .header(RequestHeaders.APP_ID, APP_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"limit":10}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.hasMore").value(false))
    }

    companion object {
        private const val APP_ID = "0123456789abcdef01234567"
        private const val OTHER_APP_ID = "ffffffffffffffffffffffff"
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.bff.customer.CustomerTodoControllerTest"`
预期：编译失败（`TodoConfig`/`CustomerTodoController` 不存在）。

- [ ] **步骤 3：编写实现（bean 装配）**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoConfig.kt`：

```kotlin
package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.BaseAppRepository
import com.ifmix.api.core.common.db.MongoClusterResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** todo 模块 bean 装配。todos 集合开启软删。template 经集群路由接缝获取（未来多集群可换实现）。 */
@Configuration
class TodoConfig {

    @Bean
    fun todoRepository(clusterResolver: MongoClusterResolver): BaseAppRepository<TodoDocument> =
        BaseAppRepository(clusterResolver.primary(), TodoDocument::class.java, softDelete = true)

    @Bean
    fun todoService(todoRepository: BaseAppRepository<TodoDocument>): TodoService =
        TodoService(todoRepository)
}
```

- [ ] **步骤 4：编写实现（控制器）**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerTodoController.kt`：

```kotlin
package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.db.CursorQuery
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.db.ReadOptions
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.ByIdRequest
import com.ifmix.api.core.modules.todo.CreateTodoRequest
import com.ifmix.api.core.modules.todo.DeleteResult
import com.ifmix.api.core.modules.todo.TodoMapper
import com.ifmix.api.core.modules.todo.TodoResponse
import com.ifmix.api.core.modules.todo.TodoService
import com.ifmix.api.core.modules.todo.UpdateOneTodoRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** customer BFF 的 todo 路由。PUT=query，POST=mutation。返回值由信封 advice 自动包装。 */
@RestController
@RequestMapping("/customer/core")
class CustomerTodoController(private val todoService: TodoService) {

    @PutMapping("/query/todo/findMany")
    fun findMany(ctx: RequestContext, @RequestBody(required = false) query: CursorQuery?): Page<TodoResponse> {
        val page = todoService.findMany(ctx, query ?: CursorQuery())
        return Page(page.items.map { TodoMapper.toResponse(it) }, page.nextCursor, page.hasMore)
    }

    @PutMapping("/query/todo/getById")
    fun getById(ctx: RequestContext, @Valid @RequestBody req: ByIdRequest): TodoResponse =
        TodoMapper.toResponse(todoService.getById(ctx, req.id!!))

    @PostMapping("/mutation/todo/createOne")
    fun createOne(ctx: RequestContext, @Valid @RequestBody req: CreateTodoRequest): TodoResponse {
        val id = todoService.create(ctx, req)
        return TodoMapper.toResponse(todoService.getById(ctx, id, ReadOptions.PRIMARY))
    }

    @PostMapping("/mutation/todo/updateOne")
    fun updateOne(ctx: RequestContext, @Valid @RequestBody req: UpdateOneTodoRequest): TodoResponse {
        todoService.update(ctx, req.id!!, req.patch!!)
        return TodoMapper.toResponse(todoService.getById(ctx, req.id, ReadOptions.PRIMARY))
    }

    @PostMapping("/mutation/todo/deleteById")
    fun deleteById(ctx: RequestContext, @Valid @RequestBody req: ByIdRequest): DeleteResult =
        DeleteResult(todoService.deleteById(ctx, req.id!!))
}
```

> 说明：`req.id!!`/`req.patch!!` 处的非空断言由 `@field:NotBlank`/`@field:NotNull` 在进入方法前保证（校验失败已被 `GlobalExceptionHandler` 转成 400）。

- [ ] **步骤 5：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.bff.customer.CustomerTodoControllerTest"`
预期：PASS（6 个测试通过）。

- [ ] **步骤 6：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoConfig.kt core-api/src/main/kotlin/com/ifmix/api/core/bff/customer core-api/src/test/kotlin/com/ifmix/api/core/bff/customer
git commit -m "feat: customer BFF todo 控制器与装配（端到端打通）"
```

---
## 任务 15：OpenAPI 分组 + 统一 apiKey 安全方案

说明：每个 BFF 一个 `GroupedOpenApi`（customer/app/platform），注册以 `x-app-id` 为 header 的 apiKey security scheme，形成统一 Authorize 弹窗。

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/config/OpenApiConfig.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/config/OpenApiTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/kotlin/com/ifmix/api/core/common/config/OpenApiTest.kt`：

```kotlin
package com.ifmix.api.core.common.config

import com.ifmix.api.core.support.AbstractMongoTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
class OpenApiTest : AbstractMongoTest() {

    @Autowired
    private lateinit var mvc: MockMvc

    @Test
    fun customerGroupDocExposesTodoPathAndSecurityScheme() {
        mvc.perform(get("/v3/api-docs/customer"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/customer/core/mutation/todo/createOne']").exists())
            .andExpect(jsonPath("$.components.securitySchemes.appId.name").value("x-app-id"))
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.config.OpenApiTest"`
预期：FAIL——`$.components.securitySchemes.appId` 不存在。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/kotlin/com/ifmix/api/core/common/config/OpenApiConfig.kt`：

```kotlin
package com.ifmix.api.core.common.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import com.ifmix.api.core.common.http.RequestContext
import org.springdoc.core.models.GroupedOpenApi
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 每 BFF 一个 OpenAPI 分组 + 以 x-app-id 为 header 的统一 apiKey 安全方案。 */
@Configuration
class OpenApiConfig {

    init {
        // ctx 由 RequestContextArgumentResolver 注入，不是真正的请求参数——让 springdoc 忽略它，
        // 否则每个接口都会多出一个必填的 ctx query 参数并污染生成的客户端与 swagger "Try it out"。
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(RequestContext::class.java)
    }

    @Bean
    fun customerApi(): GroupedOpenApi =
        GroupedOpenApi.builder().group("customer").pathsToMatch("/customer/**").build()

    @Bean
    fun appAdminApi(): GroupedOpenApi =
        GroupedOpenApi.builder().group("app").pathsToMatch("/app/**").build()

    @Bean
    fun platformAdminApi(): GroupedOpenApi =
        GroupedOpenApi.builder().group("platform").pathsToMatch("/platform/**").build()

    @Bean
    fun coreOpenApi(): OpenAPI =
        OpenAPI()
            .info(Info().title("ifmix core-api").version("v1"))
            .components(
                Components().addSecuritySchemes(
                    APP_ID_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("x-app-id"),
                ),
            )
            .addSecurityItem(SecurityRequirement().addList(APP_ID_SCHEME))

    companion object {
        private const val APP_ID_SCHEME = "appId"
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.config.OpenApiTest"`
预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/common/config/OpenApiConfig.kt core-api/src/test/kotlin/com/ifmix/api/core/common/config/OpenApiTest.kt
git commit -m "feat: OpenAPI 分组与统一 apiKey 安全方案"
```

---

## 任务 16：全量验证 + 冒烟

说明：跑全部测试确认地基完整可用，并手动冒烟一次 Swagger 与一个接口。

- [ ] **步骤 1：全量测试**

运行：`./gradlew :core-api:test`
预期：BUILD SUCCESSFUL，所有测试类通过（Envelope/ErrorCode/ClientPlatform/WebLayer/RequestContextResolution/CursorQuery/MongoSerialization/MongoClusterResolver/TxRunner/BaseRepository/BaseAppRepository/BaseAppService/TodoMapper/TodoService/CustomerTodoController/OpenApi）。

- [ ] **步骤 2：启动冒烟（需要本地 Mongo）**

先起本地 Mongo（若没有）：`docker run -d --name ifmix-mongo -p 27017:27017 mongo:8.0`
启动服务：`./gradlew :core-api:bootRun`
- 浏览器打开 `http://localhost:3000/swagger-ui/index.html`，确认能看到 customer 分组与 Authorize 弹窗。
- curl 冒烟创建（`x-app-id` 用合法 ObjectId）：
  ```bash
  curl -s -X POST http://localhost:3000/customer/core/mutation/todo/createOne \
    -H 'x-app-id: 0123456789abcdef01234567' \
    -H 'Content-Type: application/json' \
    -d '{"title":"smoke","items":[{"content":"first"}]}'
  ```
  预期返回 `{"code":"200000","msg":"success","data":{...}}`。
- 冒烟结束 Ctrl-C 停服务；`docker rm -f ifmix-mongo` 清理（可选）。

- [ ] **步骤 3：无代码变更则跳过 commit**

本任务仅验证。如冒烟中发现并修复了问题，按修复内容单独 commit。

---

## 自检结果

**1. 规格覆盖度**（对照设计文档 `2026-07-26-ifmix-core-foundation-design.md`）：

| 规格章节 | 对应任务 |
|---|---|
| 技术栈（Kotlin 2.4.10/Gradle 9.6.1/Boot 4.1/虚拟线程/JDK25） | 任务 1 |
| 仓库结构（Gradle 多模块） | 任务 1 |
| HTTP 契约（URL 含 query/mutation + PUT/POST 语义 + 信封） | 任务 4、14 |
| 错误处理（ApiError/校验/500 分级） | 任务 2、4 |
| 请求头与上下文（显式参数 + 拦截器 + 解析器，null 安全 appId 非空） | 任务 3、5 |
| 通用抽象（BaseRepository→BaseAppRepository→BaseAppService，继承复用） | 任务 9、10、11、13 |
| 软删除 | 任务 9（实现）、10/13/14（验证） |
| 游标分页（_id keyset） | 任务 6、9 |
| Mongo 与序列化（ObjectId/Instant↔epoch/去 _class/内嵌建模） | 任务 7、8、12 |
| 多租户 + 分片键（appId） | 任务 10（租户强制）；分片键 `{appId,_id}` 见下方说明 |
| 集群路由接缝（MongoClusterResolver） | 任务 8A、14 |
| 事务接缝（TxRunner + MongoTransactionManager） | 任务 8B |
| 首切片 todo（create/getById/findMany/updateOne/deleteById） | 任务 12、13、14 |
| OpenAPI（每 BFF 分组 + apiKey） | 任务 15 |
| 测试（JUnit5+AssertJ+Testcontainers 副本集，Kotlin） | 任务 8 起全部集成任务 |

> **分片键说明**：`{appId: 1, _id: 1}` 是**分片集群的运维配置**（`sh.shardCollection`），不属于应用代码；应用侧对应 `TodoDocument` 的 `(appId, _id)` 复合索引（任务 7）+ 租户强制过滤（任务 10）。分片启用属部署事项，本代码计划不含。

**2. 占位符扫描**：无 TODO/待定/"类似任务 N"/无代码测试步骤。每个代码步骤都含完整 Kotlin 代码。✅

**3. 类型一致性**（跨任务核对）：
- `RequestContext(appId 非空, 其余可空默认 null)` 全程一致；测试均用 `RequestContext(appId = "...")` 构造。
- `Envelope.ok/error`、`ErrorCode.externalCode/status`、`ApiError(errorCode, message)`。
- `CursorQuery(cursor,order,limit)` + `effectiveLimit/effectiveOrder` + `Order`；`Page(items,nextCursor,hasMore)`；`ReadOptions(preferPrimary)` + `DEFAULT/PRIMARY`。
- **读方法拆分一致**：`findById → T?`、`getById → T`（抛 NOT_FOUND）贯穿 BaseRepository/BaseAppService/TodoService/控制器。
- `BaseRepository.insertOne/insertMany/updateById/deleteById/findMany`、`BaseAppService.createOne`、`TodoService.create/update`。
- `MongoClusterResolver.forAppId/primary`（任务 8A 定义，任务 14 用 `primary()`）。
- `TxRunner.withTx(ctx) { ... }`（任务 8B）。
- `TodoMapper.toResponse`、DTO（`CreateTodoRequest/CreateTodoItem/UpdateTodoRequest/UpdateOneTodoRequest/ByIdRequest/TodoResponse/TodoItemResponse/DeleteResult`）为单文件多顶层 data class，控制器与测试引用一致。✅

**Kotlin 专项注意**（已在相关任务内联标注）：
- `@field:` 使用点目标把校验注解作用到 data class 属性。
- 请求 DTO 必填字段"可空 + 默认 null + `@field:NotBlank`"，缺字段产出字段级 400 而非解析失败。
- `Criteria.where("x").`is`(...)` / `SecurityScheme().`in`(...)`：`is`/`in` 是 Kotlin 关键字需反引号。
- `@Container` 须在 `companion object` + `@JvmStatic`。
- `kotlin("plugin.spring")` 自动 all-open Spring 注解类；`BaseRepository`/`BaseAppRepository`/`BaseAppService` 未被注解，显式声明 `open`。

---

## 执行交接

计划已完成并保存到 `docs/superpowers/plans/2026-07-26-ifmix-core-foundation.md`。两种执行方式：

**1. 子代理驱动（推荐）** —— 每个任务调度一个新的子代理，任务间进行审查，快速迭代。必需子技能：superpowers:subagent-driven-development。

**2. 内联执行** —— 在当前会话中使用 superpowers:executing-plans 执行任务，批量执行并设有检查点。

选哪种方式？
