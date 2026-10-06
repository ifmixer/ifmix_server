# 已实施归档（2026-10-06）

以下文档对应的实施工作**已全部完成并合入**，移入本目录存档。现行协议与编码规则的决策摘要见 [../rpc-protocol-decisions.md](../rpc-protocol-decisions.md)（新 agent 先读那份，需要推导依据时再查这里）：

- `graphql-to-http-rpc-openapi.md` — 迁移总计划；§一 = 协议定稿全文（信封/meta/header/命名/DTO 分界）
- `rpc-pilot-server.md` / `rpc-pilot-client.md` — demo 试点实施计划（Controller/DTO 模式先例）
- `rpc-rollout-server.md` — 全量迁移 M0–M5；§3 = mapper/controller 通用规范定稿
- `rpc-refactor-controller-mapper.md` — ActionSpec 撤销、Envelope reqId、mapper 换形态、限流收口（四项均已落地）

仍在有效期的 api-protocol 文档：`../rpc-protocol-decisions.md`、`../konvert-rollout-server.md`（K3/K4 待实施）、`../rpc-rollout-client.md`（客户端仓库侧迁移）。
