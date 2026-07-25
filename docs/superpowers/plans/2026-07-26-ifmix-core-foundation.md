# ifmix core-api 地基 + todo 切片 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 从零搭建 `ifmix_server` Maven 多模块仓库与 `core-api` 服务，打通"HTTP 信封 + 请求头/上下文 + 继承式通用 Mongo 抽象（自动租户 + 软删 + 游标分页）+ todo 模块端到端 + OpenAPI"，为后续模块（antique/iap/auth）提供地基。

**架构：** Spring MVC（阻塞式）+ 虚拟线程；控制器全走"信封自动包装 + 全局异常处理"；`RequestContext` 由参数解析器注入并显式下传；数据层用基于 `MongoTemplate` 的 `BaseRepository → BaseAppRepository（强制 appId 租户过滤）→ BaseAppService`，模块 service 继承复用；文档 `_id` 用 `ObjectId`（对外 hex 字符串），时间存 `Instant`、对外 epoch 毫秒；游标分页按 `_id`。

**技术栈：** Java 25、Spring Boot 4.1、Spring Data MongoDB、Jakarta Bean Validation、springdoc-openapi 3.0.x、JUnit 5 + AssertJ + Testcontainers(MongoDB)、Maven 多模块。

---

## 前置条件（工程师环境）

- **JDK 25** 已安装（`java -version` 显示 25），`JAVA_HOME` 指向它。
- **Maven 3.9+**（`mvn -v`）。
- **Docker 正在运行**（集成测试用 Testcontainers 启动真实 MongoDB；MongoDBContainer 默认起单节点副本集，支持事务）。
- 本地无需手动装 MongoDB——运行/调试可选用 `docker run -p 27017:27017 mongo:8.0`，测试由 Testcontainers 自动管理。

## 约定（贯穿全计划）

- **包根**：`com.ifmix.api.core`。所有主代码在 `core-api/src/main/java/com/ifmix/api/core/...`，测试在 `core-api/src/test/java/com/ifmix/api/core/...`。
- **测试命名**：单元测试与集成测试**统一 `*Test` 后缀**，都由 surefire 在 `mvn test` 一次跑完（集成测试内部用 Testcontainers）。抽象测试基类命名**不带** `Test` 后缀（如 `AbstractMongoTest`），避免被当成测试执行。
- **常用命令**：
  - 全量构建 + 测试：`mvn -q -pl core-api -am test`
  - 单个测试类：`mvn -q -pl core-api -am -Dtest=EnvelopeTest test`
  - 单个测试方法：`mvn -q -pl core-api -am -Dtest=EnvelopeTest#okWrapsData test`
  - 启动服务冒烟：`mvn -pl core-api spring-boot:run`（用完 Ctrl-C 停）
- **每个任务结束都 commit**。commit message 用 Conventional Commits（中文正文可）。

## 文件结构（本计划将创建的文件与职责）

```
ifmix_server/
  pom.xml                                             # 聚合父 POM：Boot 4.1 parent、Java 25、springdoc 版本管理、<modules>
  core-api/
    pom.xml                                           # 服务模块依赖 + spring-boot-maven-plugin
    src/main/resources/application.yml                # 虚拟线程、Mongo URI、端口、swagger 配置
    src/main/java/com/ifmix/api/core/
      CoreApplication.java                            # @SpringBootApplication 入口
      common/http/
        Envelope.java                                 # {code,msg,data} + ok()/error() 工厂
        ErrorCode.java                                # 语义码 → 外部字符串码 + HTTP 状态
        ApiError.java                                 # 携带 ErrorCode 的运行时异常
        RequestContext.java                           # 不可变上下文（appId/installId/lang...）
        ClientPlatform.java                           # 枚举 android|ios|web
        RequestHeaders.java                           # 请求头名常量
        GlobalExceptionHandler.java                   # @RestControllerAdvice 统一错误→信封
        EnvelopeResponseAdvice.java                   # ResponseBodyAdvice：成功返回自动包信封
        RequestContextArgumentResolver.java           # 把请求头解析成 RequestContext 注入控制器
        HeaderValidationInterceptor.java              # 校验 x-app-id 等请求头
        WebConfig.java                                # 注册拦截器 + 参数解析器
      common/db/
        BaseDocument.java                             # id/createdAt/updatedAt/deletedAt 公共字段
        BaseAppDocument.java                          # 追加 appId（租户文档基类）
        Page.java                                     # {items,nextCursor,hasMore}
        CursorQuery.java                              # cursor/order/limit + 默认值逻辑
        ReadOptions.java                              # throwIfNotFound/preferPrimary
        BaseRepository.java                           # MongoTemplate 通用 CRUD + 软删 + 游标分页
        BaseAppRepository.java                        # 覆写 extraCriteria 强制注入 appId
      common/service/
        BaseAppService.java                           # 实体时间戳/appId 盖章 + 委托 repo
      common/config/
        MongoConfig.java                              # 去掉 _class 类型提示
        OpenApiConfig.java                            # 每 BFF 一个 GroupedOpenApi + apiKey 安全方案
      modules/todo/
        TodoDocument.java                             # todos 集合文档（内嵌 items）
        TodoItem.java                                 # 内嵌子项
        TodoDtos.java                                 # 请求/响应 DTO（record 集合）
        TodoMapper.java                               # 文档 → 响应 DTO 映射
        TodoService.java                              # extends BaseAppService，定制 create/update
        TodoConfig.java                               # 定义 todoRepo/todoService bean
      bff/customer/
        CustomerTodoController.java                   # customer BFF 的 todo 路由
    src/test/java/com/ifmix/api/core/
      support/AbstractMongoTest.java                  # Testcontainers Mongo 基类
      common/http/EnvelopeTest.java
      common/http/ErrorCodeTest.java
      common/http/WebLayerTest.java                   # 异常处理 + 信封包装 + 上下文解析（@WebMvcTest 切片）
      common/db/CursorQueryTest.java
      common/db/MongoSerializationTest.java           # _class/_id/时间 序列化验证
      common/db/BaseRepositoryTest.java
      common/db/BaseAppRepositoryTest.java
      common/service/BaseAppServiceTest.java
      modules/todo/TodoMapperTest.java
      modules/todo/TodoServiceTest.java
      bff/customer/CustomerTodoControllerTest.java
      common/config/OpenApiTest.java
```

---
## 任务 1：Maven 多模块骨架 + 启动冒烟

**文件：**
- 创建：`pom.xml`
- 创建：`core-api/pom.xml`
- 创建：`core-api/src/main/java/com/ifmix/api/core/CoreApplication.java`
- 创建：`core-api/src/main/resources/application.yml`
- 创建：`.gitignore`

- [ ] **步骤 1：创建聚合父 POM**

创建 `pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>

    <groupId>com.ifmix</groupId>
    <artifactId>ifmix-server</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>ifmix-server</name>

    <modules>
        <module>core-api</module>
    </modules>

    <properties>
        <java.version>25</java.version>
        <springdoc.version>3.0.3</springdoc.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>${springdoc.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **步骤 2：创建 core-api 模块 POM**

创建 `core-api/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.ifmix</groupId>
        <artifactId>ifmix-server</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>core-api</artifactId>
    <name>core-api</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-mongodb</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>mongodb</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **步骤 3：创建入口类**

创建 `core-api/src/main/java/com/ifmix/api/core/CoreApplication.java`：

```java
package com.ifmix.api.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
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
target/
*.class
.idea/
*.iml
.DS_Store
```

- [ ] **步骤 6：验证编译 + 启动上下文**

运行：`mvn -q -pl core-api -am compile`
预期：BUILD SUCCESS。

再验证应用上下文能启动（不连真实 Mongo 也应能启动 web 上下文；如自动配置尝试连 Mongo 导致启动探测失败，可跳过此步，留待任务 8 的容器化测试覆盖）：
运行：`mvn -pl core-api spring-boot:run`，看到 `Tomcat started on port 3000` 与 `Started CoreApplication` 后 Ctrl-C 停止。

- [ ] **步骤 7：Commit**

```bash
git add pom.xml core-api/pom.xml core-api/src .gitignore
git commit -m "feat: 初始化 ifmix_server 多模块骨架与 core-api 服务"
```

---

## 任务 2：信封 Envelope + 错误码 ErrorCode + 异常 ApiError

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/http/Envelope.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/http/ErrorCode.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/http/ApiError.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/common/http/EnvelopeTest.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/common/http/ErrorCodeTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/common/http/EnvelopeTest.java`：

```java
package com.ifmix.api.core.common.http;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EnvelopeTest {

    @Test
    void okWrapsData() {
        Envelope<String> env = Envelope.ok("hello");
        assertThat(env.code()).isEqualTo("200000");
        assertThat(env.msg()).isEqualTo("success");
        assertThat(env.data()).isEqualTo("hello");
    }

    @Test
    void errorHasNullData() {
        Envelope<Void> env = Envelope.error("404000", "not found");
        assertThat(env.code()).isEqualTo("404000");
        assertThat(env.msg()).isEqualTo("not found");
        assertThat(env.data()).isNull();
    }
}
```

创建 `core-api/src/test/java/com/ifmix/api/core/common/http/ErrorCodeTest.java`：

```java
package com.ifmix.api.core.common.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void notFoundMapsTo404() {
        assertThat(ErrorCode.NOT_FOUND.externalCode()).isEqualTo("404000");
        assertThat(ErrorCode.NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidRequestMapsTo400() {
        assertThat(ErrorCode.INVALID_REQUEST.externalCode()).isEqualTo("400000");
        assertThat(ErrorCode.INVALID_REQUEST.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=EnvelopeTest,ErrorCodeTest test`
预期：编译失败（`Envelope`/`ErrorCode` 尚不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/common/http/Envelope.java`：

```java
package com.ifmix.api.core.common.http;

/** 统一响应信封：{ code, msg, data }。code 为字符串（如 "200000"）。 */
public record Envelope<T>(String code, String msg, T data) {

    public static <T> Envelope<T> ok(T data) {
        return new Envelope<>("200000", "success", data);
    }

    public static Envelope<Void> error(String code, String msg) {
        return new Envelope<>(code, msg, null);
    }
}
```

创建 `core-api/src/main/java/com/ifmix/api/core/common/http/ErrorCode.java`：

```java
package com.ifmix.api.core.common.http;

import org.springframework.http.HttpStatus;

/** 语义错误码 → 对外字符串码 + HTTP 状态。 */
public enum ErrorCode {
    INVALID_REQUEST("400000", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("401000", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("403000", HttpStatus.FORBIDDEN),
    NOT_FOUND("404000", HttpStatus.NOT_FOUND),
    RATE_LIMITED("429000", HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL("500000", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String externalCode;
    private final HttpStatus status;

    ErrorCode(String externalCode, HttpStatus status) {
        this.externalCode = externalCode;
        this.status = status;
    }

    public String externalCode() {
        return externalCode;
    }

    public HttpStatus status() {
        return status;
    }
}
```

创建 `core-api/src/main/java/com/ifmix/api/core/common/http/ApiError.java`：

```java
package com.ifmix.api.core.common.http;

/** 业务异常：携带 ErrorCode，由 GlobalExceptionHandler 映射为信封。 */
public class ApiError extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiError(ErrorCode errorCode) {
        this(errorCode, errorCode.name());
    }

    public ApiError(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=EnvelopeTest,ErrorCodeTest test`
预期：PASS（4 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/common/http core-api/src/test/java/com/ifmix/api/core/common/http
git commit -m "feat: 新增 Envelope/ErrorCode/ApiError 基础 HTTP 类型"
```

---

## 任务 3：请求上下文 RequestContext + ClientPlatform + RequestHeaders

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/http/ClientPlatform.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/http/RequestContext.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/http/RequestHeaders.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/common/http/ClientPlatformTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/common/http/ClientPlatformTest.java`：

```java
package com.ifmix.api.core.common.http;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClientPlatformTest {

    @Test
    void parsesLowercaseValue() {
        assertThat(ClientPlatform.fromHeader("ios")).isEqualTo(ClientPlatform.IOS);
        assertThat(ClientPlatform.fromHeader("ANDROID")).isEqualTo(ClientPlatform.ANDROID);
    }

    @Test
    void nullOrBlankReturnsNull() {
        assertThat(ClientPlatform.fromHeader(null)).isNull();
        assertThat(ClientPlatform.fromHeader("  ")).isNull();
    }

    @Test
    void invalidValueThrows() {
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> ClientPlatform.fromHeader("windows"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=ClientPlatformTest test`
预期：编译失败（`ClientPlatform` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/common/http/ClientPlatform.java`：

```java
package com.ifmix.api.core.common.http;

/** 客户端平台枚举，对应请求头 x-client-platform。 */
public enum ClientPlatform {
    ANDROID,
    IOS,
    WEB;

    /** 空/空白返回 null；非法值抛 IllegalArgumentException（调用方转成 400）。 */
    public static ClientPlatform fromHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return ClientPlatform.valueOf(raw.trim().toUpperCase());
    }
}
```

创建 `core-api/src/main/java/com/ifmix/api/core/common/http/RequestContext.java`：

```java
package com.ifmix.api.core.common.http;

/**
 * 不可变请求上下文，显式作为方法参数在 controller → service → repo 之间传递。
 * userId 预留给未来 auth 模块。
 */
public record RequestContext(
        String appId,
        String installId,
        String lang,
        String currency,
        String country,
        ClientPlatform clientPlatform,
        String userId) {
}
```

创建 `core-api/src/main/java/com/ifmix/api/core/common/http/RequestHeaders.java`：

```java
package com.ifmix.api.core.common.http;

/** 请求头名常量。 */
public final class RequestHeaders {
    public static final String APP_ID = "x-app-id";
    public static final String INSTALL_ID = "x-install-id";
    public static final String LANG = "x-lang";
    public static final String CURRENCY = "x-currency";
    public static final String COUNTRY = "x-country";
    public static final String NATIVE_VERSION = "x-native-version";
    public static final String JS_VERSION = "x-js-version";
    public static final String CLIENT_PLATFORM = "x-client-platform";

    private RequestHeaders() {
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=ClientPlatformTest test`
预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/common/http core-api/src/test/java/com/ifmix/api/core/common/http/ClientPlatformTest.java
git commit -m "feat: 新增 RequestContext/ClientPlatform/RequestHeaders"
```

---
## 任务 4：全局异常处理 + 信封自动包装

说明：用 `MockMvcBuilders.standaloneSetup` 独立测试（不启动 Spring 上下文、不连 Mongo），注册一个内联测试控制器 + 两个 advice。真实控制器**返回纯 DTO/record**，由 `EnvelopeResponseAdvice` 自动包成信封；抛出的 `ApiError`/校验错误由 `GlobalExceptionHandler` 转成信封。

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/http/GlobalExceptionHandler.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/http/EnvelopeResponseAdvice.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/common/http/WebLayerTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/common/http/WebLayerTest.java`：

```java
package com.ifmix.api.core.common.http;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WebLayerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler(true), new EnvelopeResponseAdvice())
                .build();
    }

    @Test
    void successResponseIsWrappedInEnvelope() throws Exception {
        mvc.perform(get("/_test/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200000"))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.k").value("v"));
    }

    @Test
    void apiErrorMapsToEnvelopeWithStatus() throws Exception {
        mvc.perform(get("/_test/boom"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404000"))
                .andExpect(jsonPath("$.msg").value("gone"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void validationErrorHasFieldMessage() throws Exception {
        mvc.perform(post("/_test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400000"))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("name:")));
    }

    @Test
    void genericExceptionMapsTo500() throws Exception {
        mvc.perform(get("/_test/rte"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("500000"))
                .andExpect(jsonPath("$.msg").value("kaboom"));
    }

    @RestController
    static class TestController {
        @GetMapping("/_test/ok")
        Map<String, String> ok() {
            return Map.of("k", "v");
        }

        @GetMapping("/_test/boom")
        Map<String, String> boom() {
            throw new ApiError(ErrorCode.NOT_FOUND, "gone");
        }

        @GetMapping("/_test/rte")
        Map<String, String> rte() {
            throw new RuntimeException("kaboom");
        }

        @PostMapping("/_test/validate")
        Map<String, String> validate(@Valid @RequestBody Payload p) {
            return Map.of("ok", p.name());
        }

        record Payload(@NotBlank String name) {
        }
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=WebLayerTest test`
预期：编译失败（`GlobalExceptionHandler`/`EnvelopeResponseAdvice` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/common/http/GlobalExceptionHandler.java`：

```java
package com.ifmix.api.core.common.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/** 统一异常处理：把异常映射为信封响应。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final boolean exposeErrors;

    public GlobalExceptionHandler(@Value("${app.expose-errors:true}") boolean exposeErrors) {
        this.exposeErrors = exposeErrors;
    }

    @ExceptionHandler(ApiError.class)
    public ResponseEntity<Envelope<Void>> handleApiError(ApiError ex) {
        ErrorCode code = ex.errorCode();
        return ResponseEntity.status(code.status())
                .body(Envelope.error(code.externalCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Envelope<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Envelope.error(ErrorCode.INVALID_REQUEST.externalCode(),
                        msg.isEmpty() ? "invalid request" : msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Envelope<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Envelope.error(ErrorCode.INVALID_REQUEST.externalCode(), "malformed request body"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Envelope<Void>> handleGeneric(Exception ex) {
        String msg = exposeErrors ? ex.getMessage() : "internal error";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Envelope.error(ErrorCode.INTERNAL.externalCode(), msg));
    }
}
```

创建 `core-api/src/main/java/com/ifmix/api/core/common/http/EnvelopeResponseAdvice.java`：

```java
package com.ifmix.api.core.common.http;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 把控制器返回的裸 DTO 自动包成 Envelope。
 * 规则：跳过 String 返回（避免与 StringHttpMessageConverter 冲突）、
 * 跳过框架自身控制器（springframework/springdoc）、已是 Envelope 则不重复包装。
 */
@RestControllerAdvice
public class EnvelopeResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        if (StringHttpMessageConverter.class.isAssignableFrom(converterType)) {
            return false;
        }
        String pkg = returnType.getContainingClass().getPackageName();
        return !pkg.startsWith("org.springframework") && !pkg.startsWith("org.springdoc");
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Envelope<?>) {
            return body;
        }
        return Envelope.ok(body);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=WebLayerTest test`
预期：PASS（4 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/common/http core-api/src/test/java/com/ifmix/api/core/common/http/WebLayerTest.java
git commit -m "feat: 全局异常处理与信封自动包装"
```

---
## 任务 5：请求头校验拦截器 + RequestContext 参数解析器 + WebConfig

说明：`HeaderValidationInterceptor` 在业务前校验 `x-app-id`（必填 + 必须是合法 ObjectId）与 `x-client-platform`（枚举）；`RequestContextArgumentResolver` 把请求头组装成 `RequestContext` 注入控制器参数。用 standalone MockMvc 同时测两者。

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/http/HeaderValidationInterceptor.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/http/RequestContextArgumentResolver.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/config/WebConfig.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/common/http/RequestContextResolutionTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/common/http/RequestContextResolutionTest.java`：

```java
package com.ifmix.api.core.common.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RequestContextResolutionTest {

    // 合法 24 位 hex（ObjectId 格式）
    private static final String VALID_APP_ID = "0123456789abcdef01234567";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CtxController())
                .setCustomArgumentResolvers(new RequestContextArgumentResolver())
                .addInterceptors(new HeaderValidationInterceptor())
                .addPlaceholderValue("", "") // no-op，保持 builder 链式可读
                .setControllerAdvice(new GlobalExceptionHandler(true), new EnvelopeResponseAdvice())
                .build();
    }

    @Test
    void resolvesContextFromHeaders() throws Exception {
        mvc.perform(get("/customer/core/query/ctx/echo")
                        .header(RequestHeaders.APP_ID, VALID_APP_ID)
                        .header(RequestHeaders.LANG, "en")
                        .header(RequestHeaders.CLIENT_PLATFORM, "ios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appId").value(VALID_APP_ID))
                .andExpect(jsonPath("$.data.lang").value("en"))
                .andExpect(jsonPath("$.data.platform").value("IOS"));
    }

    @Test
    void missingAppIdReturns400() throws Exception {
        mvc.perform(get("/customer/core/query/ctx/echo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400000"))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("x-app-id")));
    }

    @Test
    void invalidAppIdReturns400() throws Exception {
        mvc.perform(get("/customer/core/query/ctx/echo")
                        .header(RequestHeaders.APP_ID, "not-an-objectid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("x-app-id")));
    }

    @Test
    void invalidPlatformReturns400() throws Exception {
        mvc.perform(get("/customer/core/query/ctx/echo")
                        .header(RequestHeaders.APP_ID, VALID_APP_ID)
                        .header(RequestHeaders.CLIENT_PLATFORM, "windows"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("x-client-platform")));
    }

    @RestController
    static class CtxController {
        @GetMapping("/customer/core/query/ctx/echo")
        Map<String, Object> echo(RequestContext ctx) {
            Map<String, Object> m = new HashMap<>();
            m.put("appId", ctx.appId());
            m.put("lang", ctx.lang());
            m.put("platform", ctx.clientPlatform() == null ? null : ctx.clientPlatform().name());
            return m;
        }
    }
}
```

> 注：`addPlaceholderValue("", "")` 仅为占位保持链式；若该 API 在你的版本不存在，删除这一行即可。

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=RequestContextResolutionTest test`
预期：编译失败（相关类不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/common/http/HeaderValidationInterceptor.java`：

```java
package com.ifmix.api.core.common.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 校验请求头：x-app-id 必填且为合法 ObjectId；x-client-platform 若存在须为合法枚举。 */
@Component
public class HeaderValidationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String appId = request.getHeader(RequestHeaders.APP_ID);
        if (appId == null || appId.isBlank()) {
            throw new ApiError(ErrorCode.INVALID_REQUEST, RequestHeaders.APP_ID + ": required");
        }
        if (!ObjectId.isValid(appId)) {
            throw new ApiError(ErrorCode.INVALID_REQUEST, RequestHeaders.APP_ID + ": invalid input");
        }
        String platform = request.getHeader(RequestHeaders.CLIENT_PLATFORM);
        if (platform != null && !platform.isBlank()) {
            try {
                ClientPlatform.fromHeader(platform);
            } catch (IllegalArgumentException e) {
                throw new ApiError(ErrorCode.INVALID_REQUEST, RequestHeaders.CLIENT_PLATFORM + ": invalid input");
            }
        }
        return true;
    }
}
```

创建 `core-api/src/main/java/com/ifmix/api/core/common/http/RequestContextArgumentResolver.java`：

```java
package com.ifmix.api.core.common.http;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 把校验后的请求头组装成 RequestContext，注入到控制器方法参数。 */
public class RequestContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(RequestContext.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return new RequestContext(
                header(webRequest, RequestHeaders.APP_ID),
                header(webRequest, RequestHeaders.INSTALL_ID),
                header(webRequest, RequestHeaders.LANG),
                header(webRequest, RequestHeaders.CURRENCY),
                header(webRequest, RequestHeaders.COUNTRY),
                ClientPlatform.fromHeader(header(webRequest, RequestHeaders.CLIENT_PLATFORM)),
                null);
    }

    private static String header(NativeWebRequest request, String name) {
        String value = request.getHeader(name);
        return (value == null || value.isBlank()) ? null : value;
    }
}
```

创建 `core-api/src/main/java/com/ifmix/api/core/common/config/WebConfig.java`：

```java
package com.ifmix.api.core.common.config;

import com.ifmix.api.core.common.http.HeaderValidationInterceptor;
import com.ifmix.api.core.common.http.RequestContextArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** 注册请求头校验拦截器（仅 customer/app-admin）与 RequestContext 参数解析器。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final HeaderValidationInterceptor headerValidationInterceptor;

    public WebConfig(HeaderValidationInterceptor headerValidationInterceptor) {
        this.headerValidationInterceptor = headerValidationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(headerValidationInterceptor)
                .addPathPatterns("/customer/**", "/app/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new RequestContextArgumentResolver());
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=RequestContextResolutionTest test`
预期：PASS（4 个测试通过）。若因 `addPlaceholderValue` 报编译错，删除测试中那一行重跑。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/common core-api/src/test/java/com/ifmix/api/core/common/http/RequestContextResolutionTest.java
git commit -m "feat: 请求头校验拦截器与 RequestContext 参数解析器"
```

---
## 任务 6：分页值对象 Page + CursorQuery + ReadOptions

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/db/Page.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/db/CursorQuery.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/db/ReadOptions.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/common/db/CursorQueryTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/common/db/CursorQueryTest.java`：

```java
package com.ifmix.api.core.common.db;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CursorQueryTest {

    @Test
    void nullLimitDefaultsTo20() {
        assertThat(new CursorQuery(null, null, null).effectiveLimit()).isEqualTo(20);
    }

    @Test
    void limitCappedAtMax() {
        assertThat(new CursorQuery(null, null, 500).effectiveLimit()).isEqualTo(100);
    }

    @Test
    void limitFlooredAtOne() {
        assertThat(new CursorQuery(null, null, 0).effectiveLimit()).isEqualTo(1);
        assertThat(new CursorQuery(null, null, -3).effectiveLimit()).isEqualTo(1);
    }

    @Test
    void nullOrderDefaultsToDesc() {
        assertThat(new CursorQuery(null, null, null).effectiveOrder()).isEqualTo(CursorQuery.Order.DESC);
    }

    @Test
    void explicitOrderKept() {
        assertThat(new CursorQuery(null, CursorQuery.Order.ASC, null).effectiveOrder())
                .isEqualTo(CursorQuery.Order.ASC);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=CursorQueryTest test`
预期：编译失败（`CursorQuery` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/common/db/Page.java`：

```java
package com.ifmix.api.core.common.db;

import java.util.List;

/** 游标分页结果。nextCursor 为最后一条的 id（hex），无更多则 null。 */
public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {
}
```

创建 `core-api/src/main/java/com/ifmix/api/core/common/db/CursorQuery.java`：

```java
package com.ifmix.api.core.common.db;

/** 游标分页查询参数（对外请求体）。cursor 为上一页最后一条 id。 */
public record CursorQuery(String cursor, Order order, Integer limit) {

    public enum Order {ASC, DESC}

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public int effectiveLimit() {
        int value = (limit == null) ? DEFAULT_LIMIT : limit;
        return Math.min(Math.max(value, 1), MAX_LIMIT);
    }

    public Order effectiveOrder() {
        return (order == null) ? Order.DESC : order;
    }
}
```

创建 `core-api/src/main/java/com/ifmix/api/core/common/db/ReadOptions.java`：

```java
package com.ifmix.api.core.common.db;

/**
 * 读选项。
 * throwIfNotFound：getById 未命中是否抛 NOT_FOUND（默认 true）。
 * preferPrimary：是否强制走主库（写后回读避免副本延迟，read-your-writes）。
 */
public record ReadOptions(boolean throwIfNotFound, boolean preferPrimary) {

    public static final ReadOptions DEFAULT = new ReadOptions(true, false);
    public static final ReadOptions PRIMARY = new ReadOptions(true, true);
    public static final ReadOptions NULLABLE = new ReadOptions(false, false);
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=CursorQueryTest test`
预期：PASS（5 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/common/db core-api/src/test/java/com/ifmix/api/core/common/db/CursorQueryTest.java
git commit -m "feat: 新增 Page/CursorQuery/ReadOptions 分页值对象"
```

---

## 任务 7：文档基类 BaseDocument + BaseAppDocument + todo 文档

说明：文档用可变类（Spring Data Mongo 映射友好）。`@Id String id` 会被 Spring Data 自动以 `ObjectId` 存入 `_id`、读出时转成 24 位 hex 字符串。公共字段抽到基类，DRY。本任务为纯 POJO，无独立单元测试，用编译验证；行为在任务 8+ 覆盖。

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/db/BaseDocument.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/db/BaseAppDocument.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoItem.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoDocument.java`

- [ ] **步骤 1：创建 BaseDocument**

创建 `core-api/src/main/java/com/ifmix/api/core/common/db/BaseDocument.java`：

```java
package com.ifmix.api.core.common.db;

import org.springframework.data.annotation.Id;

import java.time.Instant;

/** 所有文档的公共字段：id + 三个时间戳（含软删标记）。 */
public abstract class BaseDocument {

    @Id
    private String id;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
```

- [ ] **步骤 2：创建 BaseAppDocument**

创建 `core-api/src/main/java/com/ifmix/api/core/common/db/BaseAppDocument.java`：

```java
package com.ifmix.api.core.common.db;

/** 租户（app 级）文档基类：追加 appId。所有 app 级集合的文档继承它。 */
public abstract class BaseAppDocument extends BaseDocument {

    private String appId;

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }
}
```

- [ ] **步骤 3：创建内嵌子项 TodoItem**

创建 `core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoItem.java`：

```java
package com.ifmix.api.core.modules.todo;

/** 内嵌在 TodoDocument 里的子项。id 应用侧生成，供客户端引用。 */
public class TodoItem {

    private String id;
    private String content;
    private boolean done;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }
}
```

- [ ] **步骤 4：创建 TodoDocument**

创建 `core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoDocument.java`：

```java
package com.ifmix.api.core.modules.todo;

import com.ifmix.api.core.common.db.BaseAppDocument;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

/** todos 集合。内嵌 items（聚合边界内、有界，单文档原子读写）。 */
@Document(collection = "todos")
@CompoundIndex(name = "todos_app_id_id_idx", def = "{'appId': 1, '_id': 1}")
public class TodoDocument extends BaseAppDocument {

    private String title;
    private boolean done;
    private List<TodoItem> items = new ArrayList<>();

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public List<TodoItem> getItems() {
        return items;
    }

    public void setItems(List<TodoItem> items) {
        this.items = items;
    }
}
```

- [ ] **步骤 5：验证编译**

运行：`mvn -q -pl core-api -am test-compile`
预期：BUILD SUCCESS。

- [ ] **步骤 6：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/common/db core-api/src/main/java/com/ifmix/api/core/modules/todo
git commit -m "feat: 新增文档基类 BaseDocument/BaseAppDocument 与 todo 文档"
```

---
## 任务 8：Mongo 序列化配置 + Testcontainers 测试基类

说明：本任务引入第一个真实 Mongo 集成测试（**需要 Docker 运行**）。`AbstractMongoTest` 用 `@ServiceConnection` 自动把容器连接注入 Spring；`MongoDBContainer` 默认起单节点副本集（后续事务可用）。`MongoConfig` 去掉 `_class` 类型提示，让文档整洁。

> 设计说明：不需要为 `ObjectId ↔ String`、`Instant ↔ epoch ms` 写自定义转换器——`@Id String id` 由 Spring Data 自动以 `ObjectId` 存取，`Instant` 自动存为 BSON `Date`，而对外 epoch 毫秒由 DTO 组装层负责（任务 12 的 `TodoMapper`）。故 `MongoConfig` 只做 `_class` 清理（YAGNI）。

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/config/MongoConfig.java`
- 创建：`core-api/src/test/java/com/ifmix/api/core/support/AbstractMongoTest.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/common/db/MongoSerializationTest.java`

- [ ] **步骤 1：编写测试基类与失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/support/AbstractMongoTest.java`：

```java
package com.ifmix.api.core.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 集成测试基类：启动真实 MongoDB（单节点副本集），每个测试后清库。 */
@SpringBootTest
@Testcontainers
public abstract class AbstractMongoTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @Autowired
    protected MongoTemplate mongoTemplate;

    @AfterEach
    void dropDatabase() {
        mongoTemplate.getDb().drop();
    }
}
```

创建 `core-api/src/test/java/com/ifmix/api/core/common/db/MongoSerializationTest.java`：

```java
package com.ifmix.api.core.common.db;

import com.ifmix.api.core.modules.todo.TodoDocument;
import com.ifmix.api.core.support.AbstractMongoTest;
import org.junit.jupiter.api.Test;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class MongoSerializationTest extends AbstractMongoTest {

    @Test
    void storesObjectIdAndDateWithoutClassHint() {
        TodoDocument doc = new TodoDocument();
        doc.setTitle("hello");
        doc.setAppId("app-1");
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());

        mongoTemplate.insert(doc);

        // insert 后 id 被回填（hex 字符串）
        assertThat(doc.getId()).isNotNull().hasSize(24);

        org.bson.Document raw = mongoTemplate.getCollection("todos").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.get("_id")).isInstanceOf(ObjectId.class);
        assertThat(raw.containsKey("_class")).isFalse();
        assertThat(raw.get("createdAt")).isInstanceOf(Date.class);

        TodoDocument loaded = mongoTemplate.findById(doc.getId(), TodoDocument.class);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getTitle()).isEqualTo("hello");
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

确保 Docker 正在运行。
运行：`mvn -q -pl core-api -am -Dtest=MongoSerializationTest test`
预期：FAIL——`raw.containsKey("_class")` 为 true（尚未清理类型提示），断言失败。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/common/config/MongoConfig.java`：

```java
package com.ifmix.api.core.common.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

/** 去掉文档里的 _class 类型提示，保持存储整洁。 */
@Configuration
public class MongoConfig {

    @Autowired
    public void removeTypeHint(MappingMongoConverter converter) {
        converter.setTypeMapper(new DefaultMongoTypeMapper(null));
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=MongoSerializationTest test`
预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/common/config/MongoConfig.java core-api/src/test/java/com/ifmix/api/core/support core-api/src/test/java/com/ifmix/api/core/common/db/MongoSerializationTest.java
git commit -m "feat: Mongo 去除 _class 提示 + Testcontainers 集成测试基类"
```

---
## 任务 9：通用仓储 BaseRepository（CRUD + 软删 + 游标分页）

说明：这是数据层核心。基于 `MongoTemplate`，不关注租户（租户在任务 10 的子类注入）。所有按 id 操作对非法/未知 id 优雅处理：`getById` 视 `throwIfNotFound` 抛 NOT_FOUND 或返回 null，`updateById`/`deleteById` 返回 false。

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/db/BaseRepository.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/common/db/BaseRepositoryTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/common/db/BaseRepositoryTest.java`：

```java
package com.ifmix.api.core.common.db;

import com.ifmix.api.core.common.http.ApiError;
import com.ifmix.api.core.common.http.RequestContext;
import com.ifmix.api.core.modules.todo.TodoDocument;
import com.ifmix.api.core.support.AbstractMongoTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class BaseRepositoryTest extends AbstractMongoTest {

    private final RequestContext ctx = new RequestContext("app-1", null, null, null, null, null, null);
    private BaseRepository<TodoDocument> repo;

    @BeforeEach
    void init() {
        repo = new BaseRepository<>(mongoTemplate, TodoDocument.class, true);
    }

    private String insertTodo(String title) {
        TodoDocument d = new TodoDocument();
        d.setTitle(title);
        d.setAppId("app-1");
        Instant now = Instant.now();
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        repo.insertOne(ctx, d);
        return d.getId();
    }

    @Test
    void insertThenGetById() {
        String id = insertTodo("hello");
        TodoDocument found = repo.getById(ctx, id);
        assertThat(found.getTitle()).isEqualTo("hello");
    }

    @Test
    void getByIdMissingThrowsNotFound() {
        String missing = new org.bson.types.ObjectId().toHexString();
        assertThatThrownBy(() -> repo.getById(ctx, missing)).isInstanceOf(ApiError.class);
    }

    @Test
    void getByIdNullableReturnsNull() {
        String missing = new org.bson.types.ObjectId().toHexString();
        assertThat(repo.getById(ctx, missing, ReadOptions.NULLABLE)).isNull();
    }

    @Test
    void invalidIdTreatedAsNotFound() {
        assertThat(repo.getById(ctx, "not-an-objectid", ReadOptions.NULLABLE)).isNull();
        assertThat(repo.updateById(ctx, "not-an-objectid", Map.of("title", "x"))).isFalse();
        assertThat(repo.deleteById(ctx, "not-an-objectid")).isFalse();
    }

    @Test
    void updateByIdModifiesFields() {
        String id = insertTodo("old");
        boolean hit = repo.updateById(ctx, id, Map.of("title", "new"));
        assertThat(hit).isTrue();
        assertThat(repo.getById(ctx, id).getTitle()).isEqualTo("new");
    }

    @Test
    void updateByIdMissingReturnsFalse() {
        String missing = new org.bson.types.ObjectId().toHexString();
        assertThat(repo.updateById(ctx, missing, Map.of("title", "x"))).isFalse();
    }

    @Test
    void softDeleteHidesFromGetAndFind() {
        String id = insertTodo("x");
        assertThat(repo.deleteById(ctx, id)).isTrue();
        assertThat(repo.getById(ctx, id, ReadOptions.NULLABLE)).isNull();
        Page<TodoDocument> page = repo.findMany(ctx, new CursorQuery(null, null, 10));
        assertThat(page.items()).isEmpty();
    }

    @Test
    void findManyPaginatesDescendingById() {
        String id1 = insertTodo("a");
        String id2 = insertTodo("b");
        String id3 = insertTodo("c");

        Page<TodoDocument> p1 = repo.findMany(ctx, new CursorQuery(null, CursorQuery.Order.DESC, 2));
        assertThat(p1.items()).extracting(TodoDocument::getId).containsExactly(id3, id2);
        assertThat(p1.hasMore()).isTrue();
        assertThat(p1.nextCursor()).isEqualTo(id2);

        Page<TodoDocument> p2 = repo.findMany(ctx, new CursorQuery(p1.nextCursor(), CursorQuery.Order.DESC, 2));
        assertThat(p2.items()).extracting(TodoDocument::getId).containsExactly(id1);
        assertThat(p2.hasMore()).isFalse();
        assertThat(p2.nextCursor()).isNull();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=BaseRepositoryTest test`
预期：编译失败（`BaseRepository` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/common/db/BaseRepository.java`：

```java
package com.ifmix.api.core.common.db;

import com.ifmix.api.core.common.http.ApiError;
import com.ifmix.api.core.common.http.ErrorCode;
import com.ifmix.api.core.common.http.RequestContext;
import com.mongodb.ReadPreference;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 基于 MongoTemplate 的通用 CRUD 基类，不关注租户。 */
public class BaseRepository<T extends BaseDocument> {

    protected final MongoTemplate mongo;
    protected final Class<T> type;
    protected final boolean softDelete;

    public BaseRepository(MongoTemplate mongo, Class<T> type, boolean softDelete) {
        this.mongo = mongo;
        this.type = type;
        this.softDelete = softDelete;
    }

    /** 子类覆写以注入额外过滤（如租户）。默认无。 */
    protected Criteria extraCriteria(RequestContext ctx) {
        return null;
    }

    public void insertOne(RequestContext ctx, T entity) {
        mongo.insert(entity);
    }

    public void insertMany(RequestContext ctx, Collection<T> entities) {
        if (!entities.isEmpty()) {
            mongo.insert(entities, type);
        }
    }

    public T getById(RequestContext ctx, String id) {
        return getById(ctx, id, ReadOptions.DEFAULT);
    }

    public T getById(RequestContext ctx, String id, ReadOptions options) {
        if (invalidId(id)) {
            if (options.throwIfNotFound()) {
                throw new ApiError(ErrorCode.NOT_FOUND);
            }
            return null;
        }
        Query query = buildQuery(idCriteria(ctx, id));
        if (options.preferPrimary()) {
            query.withReadPreference(ReadPreference.primary());
        }
        T found = mongo.findOne(query, type);
        if (found == null && options.throwIfNotFound()) {
            throw new ApiError(ErrorCode.NOT_FOUND);
        }
        return found;
    }

    public boolean updateById(RequestContext ctx, String id, Map<String, Object> patch) {
        if (invalidId(id)) {
            return false;
        }
        Query query = buildQuery(idCriteria(ctx, id));
        Update update = new Update();
        patch.forEach(update::set);
        update.set("updatedAt", Instant.now());
        return mongo.updateFirst(query, update, type).getModifiedCount() > 0;
    }

    public boolean deleteById(RequestContext ctx, String id) {
        if (invalidId(id)) {
            return false;
        }
        Query query = buildQuery(idCriteria(ctx, id));
        if (softDelete) {
            Update update = new Update()
                    .set("deletedAt", Instant.now())
                    .set("updatedAt", Instant.now());
            return mongo.updateFirst(query, update, type).getModifiedCount() > 0;
        }
        return mongo.remove(query, type).getDeletedCount() > 0;
    }

    public Page<T> findMany(RequestContext ctx, CursorQuery cursorQuery) {
        int limit = cursorQuery.effectiveLimit();
        CursorQuery.Order order = cursorQuery.effectiveOrder();

        List<Criteria> criteria = baseCriteria(ctx);
        if (cursorQuery.cursor() != null && !cursorQuery.cursor().isBlank()
                && ObjectId.isValid(cursorQuery.cursor())) {
            ObjectId cursorId = new ObjectId(cursorQuery.cursor());
            criteria.add(order == CursorQuery.Order.DESC
                    ? Criteria.where("_id").lt(cursorId)
                    : Criteria.where("_id").gt(cursorId));
        }

        Sort.Direction direction = (order == CursorQuery.Order.DESC)
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        Query query = buildQuery(criteria)
                .with(Sort.by(direction, "_id"))
                .limit(limit + 1);

        List<T> rows = mongo.find(query, type);
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? new ArrayList<>(rows.subList(0, limit)) : rows;
        String nextCursor = hasMore ? items.get(items.size() - 1).getId() : null;
        return new Page<>(items, nextCursor, hasMore);
    }

    // ---- helpers ----

    /** 基础过滤：extraCriteria（如租户）+ 软删过滤。 */
    protected List<Criteria> baseCriteria(RequestContext ctx) {
        List<Criteria> list = new ArrayList<>();
        Criteria extra = extraCriteria(ctx);
        if (extra != null) {
            list.add(extra);
        }
        if (softDelete) {
            list.add(Criteria.where("deletedAt").is(null));
        }
        return list;
    }

    private List<Criteria> idCriteria(RequestContext ctx, String id) {
        List<Criteria> list = baseCriteria(ctx);
        list.add(Criteria.where("_id").is(new ObjectId(id)));
        return list;
    }

    private static Query buildQuery(List<Criteria> criteria) {
        Query query = new Query();
        if (criteria.size() == 1) {
            query.addCriteria(criteria.get(0));
        } else if (criteria.size() > 1) {
            query.addCriteria(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
        }
        return query;
    }

    private static boolean invalidId(String id) {
        return id == null || !ObjectId.isValid(id);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=BaseRepositoryTest test`
预期：PASS（8 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/common/db/BaseRepository.java core-api/src/test/java/com/ifmix/api/core/common/db/BaseRepositoryTest.java
git commit -m "feat: BaseRepository 通用 CRUD + 软删 + 游标分页"
```

---
## 任务 10：租户仓储 BaseAppRepository（自动注入 appId）

说明：覆写 `extraCriteria` 强制在每次查询追加 `appId = ctx.appId()`，实现"默认安全、无法绕过"的租户隔离。测试验证跨租户读/写/删都被隔离。

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/db/BaseAppRepository.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/common/db/BaseAppRepositoryTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/common/db/BaseAppRepositoryTest.java`：

```java
package com.ifmix.api.core.common.db;

import com.ifmix.api.core.common.http.RequestContext;
import com.ifmix.api.core.modules.todo.TodoDocument;
import com.ifmix.api.core.support.AbstractMongoTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaseAppRepositoryTest extends AbstractMongoTest {

    private final RequestContext app1 = new RequestContext("app-1", null, null, null, null, null, null);
    private final RequestContext app2 = new RequestContext("app-2", null, null, null, null, null, null);
    private BaseAppRepository<TodoDocument> repo;

    @BeforeEach
    void init() {
        repo = new BaseAppRepository<>(mongoTemplate, TodoDocument.class, true);
    }

    private String insert(RequestContext ctx, String title) {
        TodoDocument d = new TodoDocument();
        d.setTitle(title);
        d.setAppId(ctx.appId());
        Instant now = Instant.now();
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        repo.insertOne(ctx, d);
        return d.getId();
    }

    @Test
    void getByIdIsolatedByTenant() {
        String id = insert(app1, "secret");
        assertThat(repo.getById(app2, id, ReadOptions.NULLABLE)).isNull();
        assertThat(repo.getById(app1, id).getTitle()).isEqualTo("secret");
    }

    @Test
    void findManyIsolatedByTenant() {
        insert(app1, "a1");
        insert(app2, "b1");
        insert(app2, "b2");
        Page<TodoDocument> page = repo.findMany(app2, new CursorQuery(null, null, 10));
        assertThat(page.items()).extracting(TodoDocument::getTitle)
                .containsExactlyInAnyOrder("b1", "b2");
    }

    @Test
    void crossTenantUpdateAndDeleteAreNoop() {
        String id = insert(app1, "x");
        assertThat(repo.updateById(app2, id, Map.of("title", "hacked"))).isFalse();
        assertThat(repo.deleteById(app2, id)).isFalse();
        assertThat(repo.getById(app1, id).getTitle()).isEqualTo("x");
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=BaseAppRepositoryTest test`
预期：编译失败（`BaseAppRepository` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/common/db/BaseAppRepository.java`：

```java
package com.ifmix.api.core.common.db;

import com.ifmix.api.core.common.http.RequestContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;

/** 租户仓储：每次查询强制注入 appId = ctx.appId()。app 级集合一律用它。 */
public class BaseAppRepository<T extends BaseAppDocument> extends BaseRepository<T> {

    public BaseAppRepository(MongoTemplate mongo, Class<T> type, boolean softDelete) {
        super(mongo, type, softDelete);
    }

    @Override
    protected Criteria extraCriteria(RequestContext ctx) {
        return Criteria.where("appId").is(ctx.appId());
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=BaseAppRepositoryTest test`
预期：PASS（3 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/common/db/BaseAppRepository.java core-api/src/test/java/com/ifmix/api/core/common/db/BaseAppRepositoryTest.java
git commit -m "feat: BaseAppRepository 强制 appId 租户隔离"
```

---
## 任务 11：通用服务 BaseAppService

说明：`createOne` 在插入前盖章 `appId` + 时间戳，返回 Mongo 生成的 id；其余方法委托给 `BaseAppRepository`。模块 service 继承它复用这些方法（任务 13）。

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/service/BaseAppService.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/common/service/BaseAppServiceTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/common/service/BaseAppServiceTest.java`：

```java
package com.ifmix.api.core.common.service;

import com.ifmix.api.core.common.db.BaseAppRepository;
import com.ifmix.api.core.common.http.RequestContext;
import com.ifmix.api.core.modules.todo.TodoDocument;
import com.ifmix.api.core.support.AbstractMongoTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaseAppServiceTest extends AbstractMongoTest {

    private final RequestContext ctx = new RequestContext("app-9", null, null, null, null, null, null);
    private BaseAppService<TodoDocument> service;

    @BeforeEach
    void init() {
        service = new BaseAppService<>(new BaseAppRepository<>(mongoTemplate, TodoDocument.class, true));
    }

    @Test
    void createOneStampsTenantAndTimestamps() {
        TodoDocument d = new TodoDocument();
        d.setTitle("t");

        String id = service.createOne(ctx, d);

        assertThat(id).isNotNull();
        TodoDocument saved = service.getById(ctx, id);
        assertThat(saved.getAppId()).isEqualTo("app-9");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    void updateByIdDelegatesToRepo() {
        TodoDocument d = new TodoDocument();
        d.setTitle("old");
        String id = service.createOne(ctx, d);

        assertThat(service.updateById(ctx, id, Map.of("title", "new"))).isTrue();
        assertThat(service.getById(ctx, id).getTitle()).isEqualTo("new");
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=BaseAppServiceTest test`
预期：编译失败（`BaseAppService` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/common/service/BaseAppService.java`：

```java
package com.ifmix.api.core.common.service;

import com.ifmix.api.core.common.db.BaseAppDocument;
import com.ifmix.api.core.common.db.BaseAppRepository;
import com.ifmix.api.core.common.db.CursorQuery;
import com.ifmix.api.core.common.db.Page;
import com.ifmix.api.core.common.db.ReadOptions;
import com.ifmix.api.core.common.http.RequestContext;

import java.time.Instant;
import java.util.Map;

/** 通用租户服务基类：模块 service 继承它复用 CRUD，仅覆写定制点。 */
public class BaseAppService<T extends BaseAppDocument> {

    protected final BaseAppRepository<T> repo;

    public BaseAppService(BaseAppRepository<T> repo) {
        this.repo = repo;
    }

    /** 盖章 appId + 时间戳后插入，返回 Mongo 生成的 id。 */
    public String createOne(RequestContext ctx, T entity) {
        Instant now = Instant.now();
        entity.setAppId(ctx.appId());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setDeletedAt(null);
        repo.insertOne(ctx, entity);
        return entity.getId();
    }

    public T getById(RequestContext ctx, String id) {
        return repo.getById(ctx, id);
    }

    public T getById(RequestContext ctx, String id, ReadOptions options) {
        return repo.getById(ctx, id, options);
    }

    public boolean updateById(RequestContext ctx, String id, Map<String, Object> patch) {
        return repo.updateById(ctx, id, patch);
    }

    public boolean deleteById(RequestContext ctx, String id) {
        return repo.deleteById(ctx, id);
    }

    public Page<T> findMany(RequestContext ctx, CursorQuery cursorQuery) {
        return repo.findMany(ctx, cursorQuery);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=BaseAppServiceTest test`
预期：PASS（2 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/common/service/BaseAppService.java core-api/src/test/java/com/ifmix/api/core/common/service/BaseAppServiceTest.java
git commit -m "feat: BaseAppService 盖章与委托"
```

---
## 任务 12：Todo DTO（TodoDtos）+ 文档→响应映射（TodoMapper）

说明：DTO 用 record + Jakarta Validation 注解，集中放在 `TodoDtos` 持有类的嵌套 record 里。`TodoMapper` 负责文档→响应映射，并把 `Instant` 转成 epoch 毫秒（对外契约）。

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoDtos.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoMapper.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/modules/todo/TodoMapperTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/modules/todo/TodoMapperTest.java`：

```java
package com.ifmix.api.core.modules.todo;

import com.ifmix.api.core.modules.todo.TodoDtos.TodoResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodoMapperTest {

    @Test
    void mapsDocumentToResponseWithEpochMillis() {
        TodoDocument doc = new TodoDocument();
        doc.setId("aaaaaaaaaaaaaaaaaaaaaaaa");
        doc.setTitle("t");
        doc.setDone(true);
        doc.setCreatedAt(Instant.ofEpochMilli(1000));
        doc.setUpdatedAt(Instant.ofEpochMilli(2000));

        TodoItem item = new TodoItem();
        item.setId("i1");
        item.setContent("c");
        item.setDone(false);
        doc.setItems(List.of(item));

        TodoResponse r = TodoMapper.toResponse(doc);

        assertThat(r.id()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(r.title()).isEqualTo("t");
        assertThat(r.done()).isTrue();
        assertThat(r.createdAt()).isEqualTo(1000L);
        assertThat(r.updatedAt()).isEqualTo(2000L);
        assertThat(r.items()).singleElement().satisfies(ir -> {
            assertThat(ir.id()).isEqualTo("i1");
            assertThat(ir.content()).isEqualTo("c");
            assertThat(ir.done()).isFalse();
        });
    }

    @Test
    void nullTimestampsMapToZero() {
        TodoDocument doc = new TodoDocument();
        doc.setId("bbbbbbbbbbbbbbbbbbbbbbbb");
        doc.setTitle("t");

        TodoResponse r = TodoMapper.toResponse(doc);

        assertThat(r.createdAt()).isZero();
        assertThat(r.updatedAt()).isZero();
        assertThat(r.items()).isEmpty();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=TodoMapperTest test`
预期：编译失败（`TodoDtos`/`TodoMapper` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoDtos.java`：

```java
package com.ifmix.api.core.modules.todo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** todo 模块的请求/响应 DTO。时间字段对外为 epoch 毫秒。 */
public final class TodoDtos {

    private TodoDtos() {
    }

    public record CreateTodoItem(@NotBlank @Size(max = 1000) String content) {
    }

    public record CreateTodoRequest(
            @NotBlank @Size(max = 255) String title,
            @Valid List<CreateTodoItem> items) {
    }

    public record UpdateTodoRequest(
            @Size(max = 255) String title,
            Boolean done) {
    }

    public record UpdateOneTodoRequest(
            @NotBlank String id,
            @NotNull @Valid UpdateTodoRequest patch) {
    }

    public record ByIdRequest(@NotBlank String id) {
    }

    public record TodoItemResponse(String id, String content, boolean done) {
    }

    public record TodoResponse(
            String id,
            String title,
            boolean done,
            List<TodoItemResponse> items,
            long createdAt,
            long updatedAt) {
    }

    public record DeleteResult(boolean deleted) {
    }
}
```

创建 `core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoMapper.java`：

```java
package com.ifmix.api.core.modules.todo;

import com.ifmix.api.core.modules.todo.TodoDtos.TodoItemResponse;
import com.ifmix.api.core.modules.todo.TodoDtos.TodoResponse;

import java.time.Instant;
import java.util.List;

/** 文档 → 响应 DTO 映射（含 Instant → epoch 毫秒）。 */
public final class TodoMapper {

    private TodoMapper() {
    }

    public static TodoResponse toResponse(TodoDocument doc) {
        List<TodoItemResponse> items = (doc.getItems() == null)
                ? List.of()
                : doc.getItems().stream()
                    .map(i -> new TodoItemResponse(i.getId(), i.getContent(), i.isDone()))
                    .toList();
        return new TodoResponse(
                doc.getId(),
                doc.getTitle(),
                doc.isDone(),
                items,
                epochMs(doc.getCreatedAt()),
                epochMs(doc.getUpdatedAt()));
    }

    private static long epochMs(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=TodoMapperTest test`
预期：PASS（2 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoDtos.java core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoMapper.java core-api/src/test/java/com/ifmix/api/core/modules/todo/TodoMapperTest.java
git commit -m "feat: todo DTO 与文档映射"
```

---
## 任务 13：TodoService（继承 BaseAppService，定制 create/update）

说明：`TodoService extends BaseAppService<TodoDocument>`，直接继承 `getById/findMany/deleteById/updateById`，仅新增带内嵌 items 的 `create` 与部分更新 `update`。体现"继承复用、只写定制点"。

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoService.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/modules/todo/TodoServiceTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/modules/todo/TodoServiceTest.java`：

```java
package com.ifmix.api.core.modules.todo;

import com.ifmix.api.core.common.db.BaseAppRepository;
import com.ifmix.api.core.common.db.ReadOptions;
import com.ifmix.api.core.common.http.RequestContext;
import com.ifmix.api.core.modules.todo.TodoDtos.CreateTodoItem;
import com.ifmix.api.core.modules.todo.TodoDtos.CreateTodoRequest;
import com.ifmix.api.core.modules.todo.TodoDtos.UpdateTodoRequest;
import com.ifmix.api.core.support.AbstractMongoTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodoServiceTest extends AbstractMongoTest {

    private final RequestContext ctx = new RequestContext("app-7", null, null, null, null, null, null);
    private TodoService service;

    @BeforeEach
    void init() {
        service = new TodoService(new BaseAppRepository<>(mongoTemplate, TodoDocument.class, true));
    }

    @Test
    void createEmbedsItemsWithGeneratedIds() {
        String id = service.create(ctx, new CreateTodoRequest("shopping",
                List.of(new CreateTodoItem("milk"), new CreateTodoItem("eggs"))));

        TodoDocument doc = service.getById(ctx, id);
        assertThat(doc.getTitle()).isEqualTo("shopping");
        assertThat(doc.isDone()).isFalse();
        assertThat(doc.getAppId()).isEqualTo("app-7");
        assertThat(doc.getItems()).hasSize(2);
        assertThat(doc.getItems()).allSatisfy(i -> assertThat(i.getId()).isNotBlank());
        assertThat(doc.getItems()).extracting(TodoItem::getContent).containsExactly("milk", "eggs");
    }

    @Test
    void createWithNullItemsGivesEmptyList() {
        String id = service.create(ctx, new CreateTodoRequest("t", null));
        assertThat(service.getById(ctx, id).getItems()).isEmpty();
    }

    @Test
    void updatePartialSetsOnlyProvidedFields() {
        String id = service.create(ctx, new CreateTodoRequest("keep-title", null));

        assertThat(service.update(ctx, id, new UpdateTodoRequest(null, true))).isTrue();

        TodoDocument doc = service.getById(ctx, id);
        assertThat(doc.isDone()).isTrue();
        assertThat(doc.getTitle()).isEqualTo("keep-title");
    }

    @Test
    void inheritedDeleteSoftDeletes() {
        String id = service.create(ctx, new CreateTodoRequest("t", null));
        assertThat(service.deleteById(ctx, id)).isTrue();
        assertThat(service.getById(ctx, id, ReadOptions.NULLABLE)).isNull();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=TodoServiceTest test`
预期：编译失败（`TodoService` 不存在）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoService.java`：

```java
package com.ifmix.api.core.modules.todo;

import com.ifmix.api.core.common.db.BaseAppRepository;
import com.ifmix.api.core.common.http.RequestContext;
import com.ifmix.api.core.common.service.BaseAppService;
import com.ifmix.api.core.modules.todo.TodoDtos.CreateTodoItem;
import com.ifmix.api.core.modules.todo.TodoDtos.CreateTodoRequest;
import com.ifmix.api.core.modules.todo.TodoDtos.UpdateTodoRequest;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** todo 业务逻辑：继承通用 CRUD，仅定制带内嵌 items 的创建与 patch 更新。 */
public class TodoService extends BaseAppService<TodoDocument> {

    public TodoService(BaseAppRepository<TodoDocument> repo) {
        super(repo);
    }

    /** 创建 todo（内嵌 items 单文档原子写），返回新 id。 */
    public String create(RequestContext ctx, CreateTodoRequest req) {
        TodoDocument doc = new TodoDocument();
        doc.setTitle(req.title());
        doc.setDone(false);

        List<CreateTodoItem> inputItems = (req.items() == null) ? List.of() : req.items();
        List<TodoItem> items = new ArrayList<>();
        for (CreateTodoItem in : inputItems) {
            TodoItem item = new TodoItem();
            item.setId(new ObjectId().toHexString());
            item.setContent(in.content());
            item.setDone(false);
            items.add(item);
        }
        doc.setItems(items);

        return createOne(ctx, doc); // 继承自 BaseAppService：盖章 appId/时间戳 + 插入
    }

    /** 部分更新：仅设置提供的字段；空 patch 时校验存在性后视为命中。 */
    public boolean update(RequestContext ctx, String id, UpdateTodoRequest patch) {
        Map<String, Object> set = new HashMap<>();
        if (patch.title() != null) {
            set.put("title", patch.title());
        }
        if (patch.done() != null) {
            set.put("done", patch.done());
        }
        if (set.isEmpty()) {
            getById(ctx, id); // 不存在则抛 NOT_FOUND
            return true;
        }
        return updateById(ctx, id, set);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=TodoServiceTest test`
预期：PASS（4 个测试通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoService.java core-api/src/test/java/com/ifmix/api/core/modules/todo/TodoServiceTest.java
git commit -m "feat: TodoService 继承复用 + 内嵌 items 创建/更新"
```

---
## 任务 14：Todo bean 装配 + CustomerTodoController + 全栈端到端测试

说明：定义 `todoRepository`/`todoService` bean，编写 customer BFF 控制器（`PUT`=query、`POST`=mutation，URL 含 `query`/`mutation` 段）。用 `@SpringBootTest + @AutoConfigureMockMvc + Testcontainers` 打通信封、请求头校验、租户隔离、软删的完整链路。

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoConfig.java`
- 创建：`core-api/src/main/java/com/ifmix/api/core/bff/customer/CustomerTodoController.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/bff/customer/CustomerTodoControllerTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/bff/customer/CustomerTodoControllerTest.java`：

```java
package com.ifmix.api.core.bff.customer;

import com.ifmix.api.core.common.http.RequestHeaders;
import com.ifmix.api.core.support.AbstractMongoTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class CustomerTodoControllerTest extends AbstractMongoTest {

    private static final String APP_ID = "0123456789abcdef01234567";

    @Autowired
    private MockMvc mvc;

    private String createTodo(String jsonBody) throws Exception {
        String resp = mvc.perform(post("/customer/core/mutation/todo/createOne")
                        .header(RequestHeaders.APP_ID, APP_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200000"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(resp, "$.data.id");
    }

    @Test
    void createThenGetByIdReturnsEnvelope() throws Exception {
        String id = createTodo("{\"title\":\"shopping\",\"items\":[{\"content\":\"milk\"}]}");

        mvc.perform(put("/customer/core/query/todo/getById")
                        .header(RequestHeaders.APP_ID, APP_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("shopping"))
                .andExpect(jsonPath("$.data.items[0].content").value("milk"))
                .andExpect(jsonPath("$.data.items[0].id").isNotEmpty())
                .andExpect(jsonPath("$.data.createdAt").isNumber());
    }

    @Test
    void missingAppIdHeaderRejected() throws Exception {
        mvc.perform(post("/customer/core/mutation/todo/createOne")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg", containsString("x-app-id")));
    }

    @Test
    void blankTitleFailsValidation() throws Exception {
        mvc.perform(post("/customer/core/mutation/todo/createOne")
                        .header(RequestHeaders.APP_ID, APP_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400000"))
                .andExpect(jsonPath("$.msg", containsString("title")));
    }

    @Test
    void tenantIsolationHidesOtherAppTodo() throws Exception {
        String id = createTodo("{\"title\":\"secret\"}");
        String otherApp = "ffffffffffffffffffffffff";

        mvc.perform(put("/customer/core/query/todo/getById")
                        .header(RequestHeaders.APP_ID, otherApp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404000"));
    }

    @Test
    void deleteSoftDeletesAndSubsequentGetIs404() throws Exception {
        String id = createTodo("{\"title\":\"t\"}");

        mvc.perform(post("/customer/core/mutation/todo/deleteById")
                        .header(RequestHeaders.APP_ID, APP_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));

        mvc.perform(put("/customer/core/query/todo/getById")
                        .header(RequestHeaders.APP_ID, APP_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void findManyReturnsPageEnvelope() throws Exception {
        createTodo("{\"title\":\"a\"}");
        createTodo("{\"title\":\"b\"}");

        mvc.perform(put("/customer/core/query/todo/findMany")
                        .header(RequestHeaders.APP_ID, APP_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=CustomerTodoControllerTest test`
预期：编译失败（`TodoConfig`/`CustomerTodoController` 不存在）。

- [ ] **步骤 3：编写实现（bean 装配）**

创建 `core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoConfig.java`：

```java
package com.ifmix.api.core.modules.todo;

import com.ifmix.api.core.common.db.BaseAppRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/** todo 模块 bean 装配。todos 集合开启软删。 */
@Configuration
public class TodoConfig {

    @Bean
    public BaseAppRepository<TodoDocument> todoRepository(MongoTemplate mongo) {
        return new BaseAppRepository<>(mongo, TodoDocument.class, true);
    }

    @Bean
    public TodoService todoService(BaseAppRepository<TodoDocument> todoRepository) {
        return new TodoService(todoRepository);
    }
}
```

- [ ] **步骤 4：编写实现（控制器）**

创建 `core-api/src/main/java/com/ifmix/api/core/bff/customer/CustomerTodoController.java`：

```java
package com.ifmix.api.core.bff.customer;

import com.ifmix.api.core.common.db.CursorQuery;
import com.ifmix.api.core.common.db.Page;
import com.ifmix.api.core.common.db.ReadOptions;
import com.ifmix.api.core.common.http.RequestContext;
import com.ifmix.api.core.modules.todo.TodoDocument;
import com.ifmix.api.core.modules.todo.TodoDtos.ByIdRequest;
import com.ifmix.api.core.modules.todo.TodoDtos.CreateTodoRequest;
import com.ifmix.api.core.modules.todo.TodoDtos.DeleteResult;
import com.ifmix.api.core.modules.todo.TodoDtos.TodoResponse;
import com.ifmix.api.core.modules.todo.TodoDtos.UpdateOneTodoRequest;
import com.ifmix.api.core.modules.todo.TodoMapper;
import com.ifmix.api.core.modules.todo.TodoService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** customer BFF 的 todo 路由。PUT=query，POST=mutation。返回值由信封 advice 自动包装。 */
@RestController
@RequestMapping("/customer/core")
public class CustomerTodoController {

    private final TodoService todoService;

    public CustomerTodoController(TodoService todoService) {
        this.todoService = todoService;
    }

    @PutMapping("/query/todo/findMany")
    public Page<TodoResponse> findMany(RequestContext ctx,
                                       @RequestBody(required = false) CursorQuery query) {
        CursorQuery effective = (query == null) ? new CursorQuery(null, null, null) : query;
        Page<TodoDocument> page = todoService.findMany(ctx, effective);
        List<TodoResponse> items = page.items().stream().map(TodoMapper::toResponse).toList();
        return new Page<>(items, page.nextCursor(), page.hasMore());
    }

    @PutMapping("/query/todo/getById")
    public TodoResponse getById(RequestContext ctx, @Valid @RequestBody ByIdRequest req) {
        return TodoMapper.toResponse(todoService.getById(ctx, req.id()));
    }

    @PostMapping("/mutation/todo/createOne")
    public TodoResponse createOne(RequestContext ctx, @Valid @RequestBody CreateTodoRequest req) {
        String id = todoService.create(ctx, req);
        return TodoMapper.toResponse(todoService.getById(ctx, id, ReadOptions.PRIMARY));
    }

    @PostMapping("/mutation/todo/updateOne")
    public TodoResponse updateOne(RequestContext ctx, @Valid @RequestBody UpdateOneTodoRequest req) {
        todoService.update(ctx, req.id(), req.patch());
        return TodoMapper.toResponse(todoService.getById(ctx, req.id(), ReadOptions.PRIMARY));
    }

    @PostMapping("/mutation/todo/deleteById")
    public DeleteResult deleteById(RequestContext ctx, @Valid @RequestBody ByIdRequest req) {
        return new DeleteResult(todoService.deleteById(ctx, req.id()));
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=CustomerTodoControllerTest test`
预期：PASS（6 个测试通过）。

- [ ] **步骤 6：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/modules/todo/TodoConfig.java core-api/src/main/java/com/ifmix/api/core/bff/customer core-api/src/test/java/com/ifmix/api/core/bff/customer
git commit -m "feat: customer BFF todo 控制器与装配（端到端打通）"
```

---
## 任务 15：OpenAPI 分组 + 统一 apiKey 安全方案

说明：每个 BFF 一个 `GroupedOpenApi`（customer/app/platform），并注册以 `x-app-id` 为 header 的 apiKey security scheme，形成统一 Authorize 弹窗。

**文件：**
- 创建：`core-api/src/main/java/com/ifmix/api/core/common/config/OpenApiConfig.java`
- 测试：`core-api/src/test/java/com/ifmix/api/core/common/config/OpenApiTest.java`

- [ ] **步骤 1：编写失败的测试**

创建 `core-api/src/test/java/com/ifmix/api/core/common/config/OpenApiTest.java`：

```java
package com.ifmix.api.core.common.config;

import com.ifmix.api.core.support.AbstractMongoTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class OpenApiTest extends AbstractMongoTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void customerGroupDocExposesTodoPathAndSecurityScheme() throws Exception {
        mvc.perform(get("/v3/api-docs/customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/customer/core/mutation/todo/createOne']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.appId.name").value("x-app-id"));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -q -pl core-api -am -Dtest=OpenApiTest test`
预期：FAIL——`$.components.securitySchemes.appId` 不存在（尚未配置安全方案）。

- [ ] **步骤 3：编写实现**

创建 `core-api/src/main/java/com/ifmix/api/core/common/config/OpenApiConfig.java`：

```java
package com.ifmix.api.core.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 每 BFF 一个 OpenAPI 分组 + 以 x-app-id 为 header 的统一 apiKey 安全方案。 */
@Configuration
public class OpenApiConfig {

    private static final String APP_ID_SCHEME = "appId";

    @Bean
    public GroupedOpenApi customerApi() {
        return GroupedOpenApi.builder().group("customer").pathsToMatch("/customer/**").build();
    }

    @Bean
    public GroupedOpenApi appAdminApi() {
        return GroupedOpenApi.builder().group("app").pathsToMatch("/app/**").build();
    }

    @Bean
    public GroupedOpenApi platformAdminApi() {
        return GroupedOpenApi.builder().group("platform").pathsToMatch("/platform/**").build();
    }

    @Bean
    public OpenAPI coreOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ifmix core-api").version("v1"))
                .components(new Components().addSecuritySchemes(APP_ID_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("x-app-id")))
                .addSecurityItem(new SecurityRequirement().addList(APP_ID_SCHEME));
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -q -pl core-api -am -Dtest=OpenApiTest test`
预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/java/com/ifmix/api/core/common/config/OpenApiConfig.java core-api/src/test/java/com/ifmix/api/core/common/config/OpenApiTest.java
git commit -m "feat: OpenAPI 分组与统一 apiKey 安全方案"
```

---

## 任务 16：全量验证 + 冒烟

说明：跑全部测试确认地基完整可用，并手动冒烟一次 Swagger 与一个接口。

- [ ] **步骤 1：全量测试**

运行：`mvn -q -pl core-api -am test`
预期：BUILD SUCCESS，所有测试类通过（Envelope/ErrorCode/ClientPlatform/WebLayer/RequestContextResolution/CursorQuery/MongoSerialization/BaseRepository/BaseAppRepository/BaseAppService/TodoMapper/TodoService/CustomerTodoController/OpenApi）。

- [ ] **步骤 2：启动冒烟（需要本地 Mongo）**

先起一个本地 Mongo（若没有）：`docker run -d --name ifmix-mongo -p 27017:27017 mongo:8.0`
启动服务：`mvn -pl core-api spring-boot:run`
- 浏览器打开 `http://localhost:3000/customer/swagger-ui.html`（或 `/swagger-ui/index.html`），确认能看到 customer 分组与 Authorize 弹窗。
- 用 curl 冒烟创建（注意 `x-app-id` 用合法 ObjectId）：
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

**1. 规格覆盖度**（对照设计文档 `2026-07-26-ifmix-core-foundation-design.md` 各节）：

| 规格章节 | 对应任务 |
|---|---|
| 技术栈（Java 25/Boot 4.1/虚拟线程/Maven） | 任务 1 |
| 仓库结构（Maven 多模块聚合） | 任务 1 |
| HTTP 契约（URL 含 query/mutation + PUT/POST 方法语义 + 信封） | 任务 4、14 |
| 错误处理（ApiError/校验/500 分级） | 任务 2、4 |
| 请求头与上下文（显式参数 + 拦截器 + 解析器） | 任务 3、5 |
| 通用抽象（BaseRepository→BaseAppRepository→BaseAppService，继承复用） | 任务 9、10、11、13 |
| 软删除 | 任务 9（实现）、10/13/14（验证） |
| 游标分页（_id keyset） | 任务 6、9 |
| Mongo 与序列化（ObjectId/Instant↔epoch/去 _class/内嵌建模） | 任务 7、8、12 |
| 多租户 + 分片键（appId） | 任务 10（租户强制）；分片键 `{appId,_id}` 见下方说明 |
| 首切片 todo（create/getById/findMany/updateOne/deleteById） | 任务 12、13、14 |
| OpenAPI（每 BFF 分组 + apiKey） | 任务 15 |
| 测试（JUnit5+AssertJ+Testcontainers 副本集） | 任务 8 起全部集成任务 |

> **分片键说明**：`{appId: 1, _id: 1}` 是**分片集群的运维配置**（`sh.shardCollection`），不属于应用代码；应用侧对应的是 `TodoDocument` 上的 `(appId, _id)` 复合索引（任务 7）与租户强制过滤（任务 10）。分片启用属部署事项，本代码计划不含，后续部署文档单独覆盖。

**2. 占位符扫描**：无 TODO/待定/"类似任务 N"/无代码的测试步骤。每个代码步骤都含完整代码。✅

**3. 类型一致性**：跨任务的类型/方法名核对一致——`RequestContext`（7 参构造，全程一致）、`Envelope.ok/error`、`ErrorCode.externalCode()/status()`、`CursorQuery.Order`/`effectiveLimit()`/`effectiveOrder()`、`ReadOptions.DEFAULT/PRIMARY/NULLABLE`、`Page(items,nextCursor,hasMore)`、`BaseRepository.getById/updateById/deleteById/findMany/insertOne`、`BaseAppService.createOne`、`TodoService.create/update`、`TodoMapper.toResponse`、`TodoDtos.*`。✅

---

## 执行交接

计划已完成并保存到 `docs/superpowers/plans/2026-07-26-ifmix-core-foundation.md`。两种执行方式：

**1. 子代理驱动（推荐）** —— 每个任务调度一个新的子代理，任务间进行审查，快速迭代。必需子技能：superpowers:subagent-driven-development。

**2. 内联执行** —— 在当前会话中使用 superpowers:executing-plans 执行任务，批量执行并设有检查点。

选哪种方式？
