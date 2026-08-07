# 验证参考

## 最小测试面

- 在 `commonTest` 中使用 `kotlin.test` 覆盖 ViewModel 的 Intent → UiState / UiEffect、领域用例、DTO 序列化和 mapper。
- 使用 `kotlinx-coroutines-test` 控制协程调度；对具有业务分支、生命周期或装配风险的 Koin module 使用 Koin test 验证关键装配。纯接线以受影响 target 的依赖解析和编译作为证据。
- 在需要时添加 Android 或 iOS source-set 测试，验证对应平台实现；不要用平台测试替代共享逻辑测试。
- 为关键 Compose Screen 覆盖主要状态和用户动作；优先测试可观察行为，而非实现细节。

## 验证与测试 ROI

- 按层次验证：先运行编译、静态检查或最窄构建，再为自有且高风险的决策逻辑补单元测试，最后只为关键真实用户流程增加 UI 或端到端测试。
- 不测试第三方库已经保证的行为，也不为没有分支的装配代码建立重型测试；依赖解析和编译已能证明时，以它们作为相应证据。
- 当测试基建成本高于当前风险且没有复用价值时，停止扩展测试并在交付中说明残余风险；不要为单一基础接线引入新的测试框架或平台专用基础设施。
- 业务事实、状态机、权限、安全边界和失败恢复属于高风险自有逻辑，不得以 ROI 为由跳过测试。

## 按任务类型选择验证

| 当前改动实际触及的路径 | 最小证据 | 按风险追加 |
| --- | --- | --- |
| 认证或会话 | 会话状态转换、存储失败和认证失败的共享测试 | 刷新/失效、平台 credential 回调、启动恢复 |
| API、DTO 或 repository | 请求/响应序列化、业务码与 HTTP 失败映射测试 | 与真实后端契约的兼容验证、非 2xx 错误体 |
| Koin、ViewModel 或 DI | 有分支的 module/`viewModel` 补装配或状态测试；纯接线可由受影响 target 编译证明 | Android/iOS host 编译、生命周期相关行为 |
| 平台能力 | 对应 `actual` 或入口的最小平台验证 | 真实设备或模拟器上的权限、回调、系统能力路径 |

仅在当前改动实际触及表中路径时选择对应行；纯 UI 或无关改动不因此补认证、API/DI 或平台测试。不为没有分支的接线重复建立测试；表中的“按风险追加”只在当前改动实际触及该路径时执行。认证、API/DI 或平台改动同时影响 KMP 共享边界时，至少验证 Android 与受影响的 iOS target。

## 命令

优先在 NexusFlow 根目录执行：

```bash
./gradlew :app:composeApp:ktlintCheck
./gradlew :app:composeApp:allTests
```

### 代码风格收尾

- 任何 Kotlin 或 Gradle Kotlin DSL 改动完成后，`./gradlew :app:composeApp:ktlintCheck` 是必跑项；`check` 也会聚合该任务，但在交付前显式执行可使失败原因更直接。
- 检查失败时，先执行 `./gradlew :app:composeApp:ktlintFormat`，审查格式化 diff，再重跑 `ktlintCheck` 和受改动影响的测试或编译任务。
- `.editorconfig` 是格式规则的唯一项目级配置来源。不要按个人 IDE 设置制造不同格式。
- `composeApp/ktlint-baseline.xml` 只用于无法控制的生成代码。新增或扩大 baseline 前，必须确认目标文件确为生成文件并记录原因；手写源码与构建脚本的违规必须直接修复。

涉及 iOS 或其实现时，再执行：

```bash
./gradlew :app:composeApp:iosSimulatorArm64Test
```

根据改动范围补充 Android 构建或 iOS Xcode 构建。若环境或当前工程尚未具备对应测试任务，记录实际运行的命令、未验证范围与原因。
