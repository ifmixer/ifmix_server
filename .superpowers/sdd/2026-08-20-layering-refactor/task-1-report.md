STATUS: DONE

COMMIT: 8bb9ac6

TESTS:
$ ./gradlew :core-api:compileKotlin
BUILD SUCCESSFUL in 4s
3 actionable tasks: 2 executed, 1 up-to-date

CONCERNS:
1. BaseCrudRepository/BaseAppCrudRepository 仍使用 SvcCtx 类型名（Plan Phase 3 中删除，Task 1 保持兼容）
2. AiConfig 仍使用 @Configuration + @Bean 注册 agnesKeyStore — Task 2+ 会处理为 @Component 直注册
3. CollectionFetcher 仍直接注入 ScanRecordRepository + SvcCtxFactory — 跨层问题将在 Task 7 (ai 模块) 处理
4. 所有 Handler 参数名保留 `sc`（未改为 `mc`），因 sed 替换规则仅匹配完整单词 `svcCtx` → `mc`；业务代码中的 `sc` 是个人风格而非标准命名，不在 Task 1 范围
