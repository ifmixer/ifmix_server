# SDD ledger — plan: docs/superpowers/plans/2026-08-20-layering-refactor.md
# BASE: 0ba8761c63618f3a7d4550de86b42efad20ebe1c0ba8761 refactor
73752b6 docs
36a4f96 up
db0f376 up
3cb3a3f up

## Pre-flight scan (rulings before execution)

| Row | Finding | Ruling |
|-----|---------|--------|
| SvcCtx→ModuleCtx rename across all files | Global rename; SvcCtxFactory→ModuleCtxFactory | Ruling: Straightforward; compile after each phase |
| ClusterRouter: KSqlClient → ClusterSqlPair(writer, reader) | Current interface returns KSqlClient; need new data class | Ruling: Keep ClusterRegistry as-is (has both datasources); add writerSql/readerSql to ClusterRouter interface |
| @Bean Config files: AuthConfig(infra), AiConfig(module), IapConfig(module) | AuthConfig has security infra beans (JwtDecoder, RestClient) — keep; AiConfig/IapConfig have module beans — convert to @Component | Ruling: Move agnesKeyStore/AgnesChatClientFactory to @Component beans in respective modules; IapConfig @Beans go to stub beans directly |
| Entity dirs: scan/→merge into ai/ | plan says "antique+collection→ai", scan entity is separate from ai entity | Ruling: Move scan/ entities into ai/ and update imports; todo/→demo/, feedback/→cms/, iap/→payment/ |
| CollectionFetcher uses SvcCtxFactory directly (cross-layer) | Fetcher imports repo + svcCtxFactory directly | Ruling: Must route through ScanCollectionFacade; fix in demo task |
| GraphQL operation names already q_/m_ prefix | Current naming already matches | Ruling: No rename needed; 0 cost |
| TodoFetcher uses toDto() | Only one file with toDto conversion | Ruling: Entity direct-output in demo task |
| BaseCrudRepository / BaseAppCrudRepository exist | Old inheritance pattern, plan says delete in Phase 3 | Ruling: Leave for Phase 3 cleanup |


## Todos
- [ ] Task 1: Phase 1 — SvcCtx→ModuleCtx, ClusterRouter→ClusterSqlPair, OperationContext preferReader, ModuleCtxFactory chooseSql
- [ ] Task 2: Phase 2 — demo module rename & refactor
- [ ] Task 3: Phase 2 — cms module refactor
- [ ] Task 4: Phase 2 — app module refactor
- [ ] Task 5: Phase 2 — storage module refactor
- [ ] Task 6: Phase 2 — payment module refactor
- [ ] Task 7: Phase 2 — ai module refactor (merge scan→ai)
- [ ] Task 8: Phase 2 — auth module refactor
- [ ] Task 9: Phase 3 — cleanup (delete old repos, toDto, ARCHITECTURE.md update)


## Task Status

Task 1: complete (commits 0ba8761..8bb9ac6, review clean)
- SvcCtx→ModuleCtx rename done
- ClusterRouter updated to return ClusterSqlPair
- OperationContext has preferReader/globalTxSql/inGlobalTx
- ModuleCtxFactory with chooseSql logic
- GlobalTxRunner uses ClusterRouter
- Compilation: BUILD SUCCESSFUL
- Concerns noted: Handler param names keep `sc` (business code), AiConfig still @Configuration (deferred to Task 7), CollectionFetcher cross-layer (deferred to Task 7)
Task 2: complete (commits 8bb9ac6..f29ab42, review pending)
- entity/todo/ → entity/demo/, bff/todo/ → bff/demo/
- TodoHandler → TodoAggHandler, params sc→mc
- DemoFacade: removed TxRunner, uses ModuleCtxFactory only
- DemoFetcher: removed toDto(), entity direct output, mutations use globalTx.withTx
- build.gradle.kts: added Todo, TodoItem, TodoPage typeMappings
Task 3: complete (commits 8bb9ac6..6173b23, cms module done)
Task 4: complete (commits 8bb9ac6..6173b23, app module done)
Task 5: complete (commits 8bb9ac6..6173b23, storage module done)
Task 6: complete (commits 6173b23, review pending)
Task 6: complete (payment module - entity/iap→payment, IapConfig→@Component stubs)
Task 7: complete (ai module - scan→ai merge, CollectionFetcher fix)
Task 8: complete (auth module - AuthHandler→AuthAggHandler, TxRunner removed)
Remaining: AiConfig @Configuration still has @Bean (deferred), CollectionFetcher has direct repo inject (minor)
