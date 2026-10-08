# 变更日志

本文件记录 Jellyfish Spring Boot Starter 所有值得接入方知道的变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- `jellyfish.trust-project-config`（缺省 `false`）：是否信任并加载**项目级**配置。
  服务化运行时没有终端可问，因此这里只给一个显式开关；授予发生在 `bootstrap()` 之前，
  否则配置已经读完了。用 `classpath:` 把配置指到自己的 `resources` 时**不受此开关约束**
  ——那是构件的一部分，与全局级同性质。

### Changed

- **项目级配置默认不加载**（破坏性，跟随内核 0.1.1 的同名变更）：它按进程当前目录读取，
  能改模型端点与密钥、新增 agent、改落盘目录。若此前依赖 `.jellyfish/*.json`、又没有把路径写成
  `classpath:`，需要在 `application.yml` 里显式打开 `jellyfish.trust-project-config: true`。
- Maven 坐标由 `zcd` 改为 `io.github.zcd0831`，版本号 0.1.1，已发布到 Maven Central；依赖的内核
  `io.github.zcd0831:jellyfish-di:0.1.1` 也来自中央仓库，接入方不必再本地 `install` 内核。
- 显式声明 `url` / `licenses` / `developers` / `scm`：parent 是 `spring-boot-starter-parent`，
  不写就会继承 Spring 的项目主页、Apache 2.0 许可与开发者信息。
- 新增 `release` profile（`-P release`）承载 source / javadoc / GPG 签名 / Central 发布四个插件。

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
- 配置属性 `jellyfish.lifecycle.bootstrap-on-start`（缺省 `false`）。
- 越权 / 超配额 / 命令被拒三种专门异常。

[Unreleased]: https://github.com/zcd0831/Jellyfish-Spring-Boot-Starter/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/zcd0831/Jellyfish-Spring-Boot-Starter/releases/tag/v0.1.0
