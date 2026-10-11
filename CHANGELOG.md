# 变更日志

本文件记录 Jellyfish Spring Boot Starter 所有值得接入方知道的变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.1.2] - 2026-10-11

### Changed

- 版本号对齐到 `0.1.2`：本 starter 与它依赖的内核 `io.github.zcd0831:jellyfish-di` 同步到 `0.1.2`。
  本版 starter 自身没有面向接入方的行为变更。

## [0.1.1] - 2026-10-11

### Added

- `jellyfish.trust-project-config`（缺省 `false`）：是否信任并加载**项目级**配置。
  服务化运行时没有终端可问，因此这里只给一个显式开关；授予发生在 `bootstrap()` 之前，
  否则配置已经读完了。用 `classpath:` 把配置指到自己的 `resources` 时**不受此开关约束**
  ——那是构件的一部分，与全局级同性质。
- `JellyfishLifecycle.bootstrap()`（公开、幂等、与 `stop()` 共用一把锁）：接入方要自己挑时机启动内核
  时用它。`jellyfish.lifecycle.bootstrap-on-start: false` 这条路此前是断的——`start()` 直接返回，
  `stop()` 因此什么也不做，容器关闭时既不落盘也不关插件。`stop()` 随之改为认「内核起没起」，
  而不是「谁起的」。

### Changed

- **内核门面不再注册成 Spring Bean**（破坏性）：`JellyfishRuntime` 与原先注册的 14 个转发门面
  （`SessionManager`、`ConversationService`、`ShellStreams`、`TurnRegistry`、`CommandManager`、
  `ApprovalChannel`、`AskChannel`、`PermissionManager`、`AgentHarness`、`EventChannel`、`RunEventBus`、
  `ExtensionRegistry`、`InputDirectives`、`ConversationCompactor`）都从容器里移除；
  内核由 `JellyfishKernel` 持有，其访问器是包私有的。
  理由：它们都是「按 id 操作某个会话」的能力，且都**不校验调用方是谁**——做成 Bean 之后，容器里任何
  Bean 注入进来就能读别人的对话正文、批准别人的工具调用，归属校验只剩一句约定。
  **迁移**：业务侧改用 `JellyfishUserFacade`（带归属校验）；确实需要一份内核句柄的（自写外壳等），
  自己 `JellyfishAssembler.create(appConfig)` 并接管它的生命周期。仍然可作为 Bean 注入的只剩观测面：
  `ModelManager`、`AgentManager`、`RuntimeConfig`、`SessionDefaults`、`RuntimeInfoHolder`、`HealthCheck`、
  `LlmClientFactory`。`JellyfishLifecycle` 的构造参数随之由 `JellyfishRuntime` 改为 `JellyfishKernel`。
- **项目级配置默认不加载**（破坏性，跟随内核 0.1.1 的同名变更）：它按进程当前目录读取，
  能改模型端点与密钥、新增 agent、改落盘目录。若此前依赖 `.jellyfish/*.json`、又没有把路径写成
  `classpath:`，需要在 `application.yml` 里显式打开 `jellyfish.trust-project-config: true`。
- **审批与提问的超时配 `0` 会一直占住 `react` 线程**（跟随内核 0.1.1）：`permission.approvalTimeoutSeconds`
  / `ask.timeoutSeconds` 写 `0` 表示**永不超时**，而两处等待都发生在内核的 `react` 线程上（全进程 8 条，
  = 并发顶层回合上限），那条线程不会自愈——接入方没把裁决 / 作答接口接起来时，只有客户端断开后调
  `cancelAsk` / 拒绝待审项能放开它。**两个开关「必须显式打开」的理由因此更成立**，
  `README.md` 与两个开关的 javadoc 已写明这条代价。
- 跟随内核 0.1.1 的「取快照收成唯一入口」：`JellyfishUserFacade.snapshot` 改用
  `Session.captureSnapshot()`（公共签名与行为不变，仅内部适配）。
- Maven 坐标由 `zcd` 改为 `io.github.zcd0831`，版本号 0.1.1：本 starter 与它依赖的内核
  `io.github.zcd0831:jellyfish-di:0.1.1` 随本次发布上传到 Maven Central，本次发布之前接入方仍需
  先本地 `install` 内核。
- 显式声明 `url` / `licenses` / `developers` / `scm`：parent 是 `spring-boot-starter-parent`，
  不写就会继承 Spring 的项目主页、Apache 2.0 许可与开发者信息。
- 新增 `release` profile（`-P release`）承载 source / javadoc / GPG 签名 / Central 发布四个插件。
- **`jellyfish.user.idle-timeout ≤ 0` 现在表示「不回收」**（不配这个键仍是缺省的 `30m`，会回收）：
  此前被钳成 1 毫秒，判据 `updatedAt < now - 1ms` 使每轮扫描清空所有空闲会话，客户端手里的
  `sessionId` 下次请求即 404。现在不排扫描并打一条显眼的 WARN（会话表只增不减），
  与 `max-inflight-turns-per-user` / `max-sessions-per-user` 同口径。
- **`UserQuotaGuard` 的额度信号量表有了上限**（`MAX_TRACKED_USERS = 65536`）：每个出现过的标识原先是
  一条与进程同寿命的记录，现在到上限后**新标识一律占不到额度（fail-closed）并记 WARN**；
  `releaseTurn` 改为「没有记录就不归还」，防止被上限拒过的标识靠归还凭空挣到额度。
  把请求 id 当 `userId` 用的接入方会看到「超限之后请求被拒」。

### Fixed

- **订阅句柄的登记窗口漏退订**：监听器的「先 `subscribe`、再登记」两步之间若落进终态事件，
  `finish()` 看到的句柄还是 `null`，于是它永久留在 `ShellStreams` 里（内存泄漏），业务回调还会在回合
  「已结束」之后继续被调用。现在赋值之后再复查一次终态，两种先后次序都覆盖；该类同时从
  `JellyfishUserFacade` 的内部类抽成包级的 `QuotaReleasingListener`（公共 API 未变）。

## [0.1.0] - 2026-10-07

### Added

- 自动配置：`JellyfishAutoConfiguration` + `JellyfishProperties`（`jellyfish.*` 只描述四份配置文件路径与
  插件扫描目录），并在 `META-INF/spring.factories` 注册。
- 装配根：`JellyfishRuntimeConfiguration` 由 properties 直接构造 `AppConfig` 并交付 `JellyfishRuntime`；
  `JellyfishFacadeConfiguration` 把内核的 21 个门面各注册成一个 `@Bean`。
- 生命周期：`JellyfishLifecycle`（`SmartLifecycle`）启动最先写 `RuntimeInfo` 并 bootstrap，停止最后
  shutdown；`RuntimeInfo` 如实反映有没有审批通道（未挂通道就填 `false`）。
- 多用户护栏 `JellyfishUserFacade`（业务侧唯一入口，每个方法以 `userId` 开头），配套组件：
  - `SessionOwnerStore` / `InMemorySessionOwnerStore`：会话归属 SPI 与默认实现，
    也是本 starter 唯一可被接入方替换的 Bean。
  - `UserQuotaGuard`：每用户并发回合数与会话数配额。
  - `ApprovalGateway`：校验 `requestId` 属于本会话且只裁决本会话头槽位。
  - `AskGateway`：同上的归属校验，防止把任意文本注入别人的对话上下文。
  - `CommandGate`：拒绝会碰进程级状态或别人会话的命令，也拦住命令参数里不受归属校验保护的部分。
  - `IdleSessionReaper`：闲置会话回收（phase 比内核大 1）。
- 提问通道接入：配置开关 + 门面方法 + 越权网关。
- 配置属性 `jellyfish.lifecycle.bootstrap-on-start`（缺省 `true`）。
- 越权 / 超配额 / 命令被拒三种专门异常。

[Unreleased]: https://github.com/zcd0831/Jellyfish-Spring-Boot-Starter/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/zcd0831/Jellyfish-Spring-Boot-Starter/releases/tag/v0.1.2
[0.1.1]: https://github.com/zcd0831/Jellyfish-Spring-Boot-Starter/releases/tag/v0.1.1
[0.1.0]: https://github.com/zcd0831/Jellyfish-Spring-Boot-Starter/releases/tag/v0.1.0
