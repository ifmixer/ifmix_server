# DTO 目录重组

> 日期: 2026-08-18

## 目标

将散落在 `infra/dto/` 和 `modules/*/dto/` 的 DTO 统一到顶层 `dto/` 目录，按模块分子目录。

## 规范

| 类型 | 位置 | 谁写 |
|------|------|------|
| DB 实体 | `model/` | 手写 |
| GraphQL input/payload/enum | `build/generated/` | DGS codegen |
| Service 内部 DTO (Req/Res) | `dto/` | 手写 |
| 通用类型 (Page, OperationResult) | `dto/common/` | 手写 + typeMapping |

**依赖方向**: `dto → model`（dto 可引用 model 常量），反向不允许。

## 目标结构

```
dto/
├── common/
│   ├── Page.kt                  # 从 infra/dto/Page.kt 移入
│   └── OperationResult.kt      # 从 infra/dto/CommonDto.kt 拆出
├── scan/
│   ├── ScanDto.kt              # 从 modules/scan/dto/ScanDto.kt 移入 (ScanInput, ScanMediaItem 等)
│   ├── ScanCollectionDto.kt    # 从 modules/scan/dto/ScanCollectionDto.kt 移入
│   └── ...
├── storage/
│   ├── PresignUploadResult.kt  # 新建（从 StorageCommands 里抽出，替代 DGS payload）
│   └── PresignDownloadResult.kt
├── auth/
│   ├── AuthDtos.kt             # LoginRes, MeRes, ExchangeRes 等（当前在 AuthService.kt 内联）
│   └── ...
├── iap/
│   ├── IapDto.kt               # 从 modules/iap/IapDto.kt 移入
│   └── IapTypes.kt             # 从 modules/iap/IapTypes.kt 移入
└── feedback/
    └── FeedBackDto.kt          # 从 modules/feedback/dto/ 移入
```

Package: `com.ifmix.api.core.dto.common.Page`、`com.ifmix.api.core.dto.scan.ScanInput` 等。

## 移动映射

| 当前位置 | 新位置 |
|----------|--------|
| `infra/dto/Page.kt` | `dto/common/Page.kt` |
| `infra/dto/CommonDto.kt` (OperationResult, ByIdRequest 等) | `dto/common/CommonDto.kt` |
| `infra/dto/CursorQueryInput.kt` | `dto/common/CursorQueryInput.kt`（如果还在用） |
| `modules/scan/dto/ScanDto.kt` | `dto/scan/ScanDto.kt` |
| `modules/scan/dto/ScanCollectionDto.kt` | `dto/scan/ScanCollectionDto.kt` |
| `modules/feedback/dto/FeedBackDto.kt` | `dto/feedback/FeedBackDto.kt` |
| `modules/iap/IapDto.kt` | `dto/iap/IapDto.kt` |
| `modules/iap/IapTypes.kt` | `dto/iap/IapTypes.kt` |
| AuthService 内联的 DTO (LoginRes 等) | `dto/auth/AuthDtos.kt` |
| StorageCommands 返回的 Payload | `dto/storage/PresignResult.kt`（新建） |

## 额外改动

1. **StorageCommands/StorageFacadeService** — 返回类型从 DGS `PresignUploadPayload` 改为 `dto/storage/PresignUploadResult`
2. **StorageFetcher** — 从 service result 转换为 DGS payload
3. **AuthService 内联 DTO** — 抽到 `dto/auth/AuthDtos.kt`（可选，或保留内联）
4. **DGS typeMapping** — `OperationResult` 指向 `dto.common.OperationResult`（改包路径）
5. 删除旧的 `infra/dto/` 和 `modules/*/dto/` 目录

## 步骤

```
1. 创建 dto/ 目录结构
2. 移动文件 + 改 package
3. 全局更新 import
4. StorageCommands 返回自定义 result（替代 DGS payload）
5. DGS typeMapping 更新 OperationResult 包路径
6. 删除旧目录
7. 编译通过
```
