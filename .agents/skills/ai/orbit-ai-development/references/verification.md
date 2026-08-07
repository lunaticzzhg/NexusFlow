# 验证与验收矩阵

先运行最窄的相关检查：

```bash
./gradlew :ai:test
./gradlew :contracts:test :ai:test :backend:test   # boundary change
git diff --check
python /Users/lunatic/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/orbit-ai-development
```

| 变更类型 | 必需证据 |
| --- | --- |
| 核心 context/提案/策略 | 接受与拒绝单元测试；确定性 Provider 仍有效 |
| Wire 映射或 schema | contracts 测试、映射测试；无伪造必填字段 |
| 远程 Provider/路由 | 使用 fake adapter 的超时/降级/预算测试；trace 元数据断言 |
| Prompt/RAG/安全 | 注入与未知/过期来源测试；安全降级路径 |
| 请求动作 | AI 策略测试加后端审批/重复动作测试 |
| Eval/prompt/策略发布 | 固定用例回放、阈值比较、结果元数据中的版本 |

每项结果报告已运行命令、结果、遗漏检查和需要扩大集成/生产验证的条件。未运行真实 Provider、broker 或部署测试时不得声称已运行。
