# Orbit 文档索引

产品需求、技术方案与交付计划按版本归档；跨版本架构规范保留在独立目录。

| 版本 | 状态 | 内容 |
| --- | --- | --- |
| [v0.1](v0.1/) | 当前 MVP 基线 | 需求基线、App 技术方案与交付任务/进度。 |

跨版本工程约束见 [architecture/](architecture/)。当前长期 architecture authority 按执行环境拆分：

| 范围 | Authority |
| --- | --- |
| App / KMP interaction state | [Orbit 前端架构主规范](architecture/orbit-frontend-architecture.md) |
| Backend / Ktor durable truth and IO | [NexusFlow Backend 架构主规范](architecture/nexusflow-backend-architecture.md) |
| AI / planning proposal boundary | [NexusFlow AI 架构主规范](architecture/nexusflow-ai-architecture.md) |

新增版本时，创建 `docs/vX.Y/`，在其中维护该版本的产品、技术与交付文档；不要修改已冻结版本的事实，而应在新版本中说明演进或替代关系。
