# AGENTS.md

本文件用于指导 AI 编码代理在**本仓库**（`jellyfish-spring-boot-starter`）中工作。修改代码前请先阅读。

> **本文件是常驻上下文，只放「每次动手前都该知道的规则」**：项目描述、模块边界、关键不变量、编码 / 测试 / Git 约定。
> 面向接入方的说明在 `README.md`；推导与实测数据在对应类的 javadoc 里，本文不重复。

## 项目描述

把 Jellyfish 内核接入**已有的 Spring Boot 应用**：一个自动配置 + 一组门面 Bean，让业务代码能注入内核的
会话 / 回合 / 模型 / 权限 / 工具等能力，并通过插件系统扩展。

三条边界，先记住再动手：

1. **不提供任何 HTTP 接口**。接口由接入方自己写（`@RestController` + `SseEmitter` 等），本 starter 只交付
   编程式 API。带上 `spring-boot-starter-web` 等于替接入方决定技术选型。
2. **不改内核**。只依赖内核的装配模块 `io.github.zcd0831:jellyfish-di`，用它的 `JellyfishAssembler` 装配。
   内核有需要改的地方去 `Jellyfish` 仓库改，不在这里绕。
3. **一个 JVM 一个内核**。内核是进程级单例语义，因此本 starter 不试图支持「同进程两套内核」。

运行环境 **JDK 1.8**（**不要使用 Java 9+ 的 API 或语法**），Spring Boot 2.7。

## 常用命令

```bash
# 0. 前置：内核必须先装进本地仓库（本仓库是独立 reactor，依赖不来自内核源码树）
(cd ../Jellyfish && mvn -q install -DskipTests)

mvn -q compile
mvn -q test                                  # 全量单测（JUnit5 + Mockito + JaCoCo）
mvn -q -Dtest=JellyfishLifecycleTest test    # 单类单测
mvn -q package -DskipTests
```

**第 0 步不能省**：忘了它的表现是「内核明明改了，这边编译却看不到」，或更糟——用到的是本地仓库里的旧快照。

## 结构

```
src/main/java/zcd/jellyfish/spring/boot/
├── JellyfishAutoConfiguration.java        # 内核入口：条件装配
├── JellyfishProperties.java               # @ConfigurationProperties("jellyfish")：只描述路径与开关
├── JellyfishRuntimeConfiguration.java     # @Bean AppConfig + @Bean JellyfishKernel（装配根，内核门面不进容器）
├── JellyfishKernel.java                   # 内核门面的持有者：公开面无能力，runtime() 是包私有
├── JellyfishFacadeConfiguration.java      # 只把「不按会话操作」的观测面注册成 @Bean
├── JellyfishUserWiringConfiguration.java  # 用户层装配：从包私有访问器取内核能力，收成带归属校验的对象
├── JellyfishLifecycle.java                # SmartLifecycle：写 RuntimeInfo → bootstrap / shutdown
└── user/                                  # 多用户护栏（内核没有「用户」，全靠这一层）
    ├── JellyfishUserAutoConfiguration.java / JellyfishUserProperties.java   # 前者只管「什么时候装」
    ├── JellyfishUserFacade.java            # ★ 业务侧唯一入口，每个方法以 userId 开头
    ├── SessionOwnerStore.java / InMemorySessionOwnerStore.java   # 会话归属 SPI 与默认实现
    ├── SessionSummary.java                # 列表页用的轻量投影（不含消息正文）
    ├── UserQuotaGuard.java                # 每用户并发回合 + 会话数配额
    ├── ApprovalGateway.java               # 审批：归属校验 + 只裁决本会话头槽位
    ├── AskGateway.java                    # 提问：归属校验 + 只作答本会话头槽位（兼防内容注入）
    ├── CommandGate.java                   # 命令闸门：拒绝会碰进程级状态 / 别人会话的命令
    ├── IdleSessionReaper.java             # 闲置回收（SmartLifecycle，phase 比内核大 1）
    └── *Exception.java                    # 越权 / 超配额 / 命令被拒

src/main/resources/META-INF/spring.factories   # Boot 2.7 的自动配置注册（两个类都在这里）
```

## 关键不变量

- **内核门面不进容器，只由 `JellyfishKernel` 持有（访问器包私有）**：内核门面（`JellyfishRuntime`）上挂的
  能力都是「按 id 操作某个会话」且**不校验调用方是谁**（会话域、审批与提问通道、外壳通道、命令域、
  扩展注册表）。一旦做成 Bean，容器里任何 Bean 注入进来就能读别人的对话正文、批准别人的工具调用，
  归属校验只剩一句约定。**要加一个转发 Bean 之前，先问「拿到它做不做得了跨用户的事」**：
  「不按会话操作、也读不到别人数据」的那几个（`ModelManager` / `AgentManager` / `RuntimeConfig` /
  `SessionDefaults` / `RuntimeInfoHolder` / `HealthCheck` / `LlmClientFactory`）才可以。
  要拿内核做别的事，请让接入方自己 `JellyfishAssembler.create(...)`。
  **用户层的装配写在 `boot` 包（`JellyfishUserWiringConfiguration`）而不是 `user` 包**，因为只有与
  `JellyfishKernel` 同包的代码才拿得到那份内核能力——包可见性就是这道边界，别为了「看起来整洁」把它挪回去。
- **门面 Bean 不做 `@ConditionalOnMissingBean`——`SessionOwnerStore` 是仅有的例外之一。**
  前者是同一张对象图上的视图，让接入方「再注册一个 `ModelManager`」等于在同一个 JVM 里造出第二套内核状态，
  而坏掉的方式是静默的。**`SessionOwnerStore` 是一条 SPI**，归属存在内存还是关系库只有接入方知道，
  因此必须可替换。**加新的 `@ConditionalOnMissingBean` 之前，先问「这是内核组件的第二个视图，还是一条 SPI」。**
- **装配走手工装配，不把 Dagger 塞进 Spring**：用 `JellyfishAssembler`。**不要把 `DaggerJellyfishComponent`
  引进来**（会拖进 Dagger 运行期依赖，并使内核加一个绑定就需要 starter 跟着改）。装配知识只写一份。
- **审批网关必须自己校验 `requestId` 属于本会话**：内核的 `ApprovalChannel.resolve(id, approved)` 只校验
  「是不是它自己会话的头槽位」，**不校验调用方说的会话对不对**——少了这一步就是一个真实的越权漏洞。
- **提问网关同一条要求，而且更要紧**：内核的 `AskChannel.resolve(id, answer)` 有完全相同的缺口；
  而提问的答案会作为**工具结果原文进那个会话的模型上下文**，因此少了归属校验就不只是越权，
  还是一条把任意文本注入别人对话的路径。审批与提问的网关是**两个必需的组件，不是可选的加固**。
- **命令闸门必须存在**：命令的**参数部分不受归属校验保护**（`/delete <别人的会话 id>` 里的 id 从不经过本层
  检查），闸门是唯一能拦住它的地方。逐条理由写在 `CommandNotAllowedException` 的 javadoc 里。
- **`AppConfig` 由 properties 直接构造，不走 `ConfigLoader.loadAppConfig()`**：后者读写死的
  `classpath:config.json`，而 classpath 上同名资源谁生效取决于顺序——自带一份就会与接入方争夺优先级。
- **配置的分工**：yml 只给「文件在哪」（四份配置文件路径 + 插件扫描目录），**内容仍是内核格式的 JSON**。
  **不去把 JSON 翻译成 yml**：那需要给内核加第二个配置写入口，而内核的快照语义正是靠「只有 `refresh()`
  一个写入口」成立的。
- **`RuntimeInfo` 必须如实反映「有没有审批通道」**：`RuntimeInfo.server(...)` 恒把 `supportsApproval` 置为
  `true`，未挂审批通道却这样声明会让插件按「审批可用」规划行为，结果是每次调用白等 120 秒再失败——
  比直接拒绝更糟。因此用 `new RuntimeInfo(Shell.SERVER, false, false, interactive)` 如实填写。
  **提问通道没有对应字段，也不为它伪造一个**：提问不是安全边界，「能不能问」由通道运行时返回
  `UNAVAILABLE` 如实反映，不靠启动期的静态降级。
- **`JellyfishLifecycle.getPhase()` 取 `Integer.MIN_VALUE`**（启动最先、停止最后）。**不要改成「比 Web 容器
  小一点」**：Web 容器的 phase 是 Boot 的内部实现（2.x 是 `Integer.MAX_VALUE - 1`，较新版本已改成
  `Integer.MAX_VALUE - 1024`），跟它减一个魔数会在某次升级 Boot 时静默失效。
- **不注册 JVM shutdown hook**：Spring 自己会走 `SmartLifecycle.stop()`，再叠一个只会让「谁先关」取决于
  JVM 不保证的 hook 顺序。
- **接入方自己挑启动时机只有一条入口：`JellyfishLifecycle.bootstrap()`**（`bootstrap-on-start=false` 时它才
  有意义）。内核门面不在容器里，接入方拿不到 `AgentHarness`——别把这条入口删掉，也别让 `stop()` 只认
  「自动启动才算起过」：认错了的话，手动起的内核在容器关闭时不落盘、不关插件，两条都在进程退出时才显形。
- **`jellyfish.user.idle-timeout ≤ 0` 是「不回收」，不能折算成一个很小的门槛**：判据是
  `updatedAt < now - 门槛`，1 毫秒意味着「每轮扫描关掉所有空闲会话并解绑」，而配的人以为自己写的是「不限」。
- **`IdleSessionReaper.shouldReap` 必须同时满足「有归属者」与「没有在途回合」**：闲置判据只看时间戳，
  而一次长 LLM 调用在第一条新消息落库前**不刷新这个时间戳**——只看时间会把正在跑的会话关掉。
- **建会话的锁用固定 64 个散列槽位，不是每用户一把**（后者会随用户数无限增长）。
- **`JellyfishStarterRegistrationTest` 是 `spring.factories` 的唯一守卫**：其它用例都显式点名加载配置类，
  因此类名写错、漏写、注册失效它们全都照样绿。**改那个文件之后必须跑它。**
- **`@AutoConfigureAfter` 的必然性未被测试证明**，但**不要删**：靠字母序恰好正确不是契约。

## 编码规范

- 缩进 4 空格，K&R 风格大括号，文件末尾保留换行。
- 依赖注入一律用构造器注入 `@Inject`，不用字段注入。
- 注释用中文，说明「为什么」而非复述代码。
- 类、接口、私有方法、成员变量都要有文档注释；类注释加 `@author zcd`；方法注释用 `@param` 写清每个参数、
  用 `@return` 写清返回值。
- 异常统一抛 `JellyfishException`（本仓库的越权 / 超配额 / 命令被拒另有专门的异常类型）。
- 工具方法 / 常量类用 `final` + 私有构造器。
- 所有代码均需满足 sonar 规范要求。

## 单元测试规范

- JUnit5 + Mockito，JaCoCo 收集覆盖率。
- 测试类包路径与被测类一致；命名 `{被测试类名}Test`。
- 方法名 `{被测试方法}_should_{预期结果}_when_{条件}`；一个方法只验一个行为；测试独立、可重复、无外部依赖。
- 结构遵循 Given/When/Then 或 Arrange/Act/Assert。
- 只 mock 外部依赖或协作者，不 mock 被测类、POJO、DTO。
- 用 `@ExtendWith(MockitoExtension.class)`，统一 JUnit5 API，不混用 JUnit4。
- 断言用 JUnit5 `Assertions` 或 AssertJ，异常用 `assertThrows`。
- **单测不启动 Web 容器、不访问网络、不碰用户真实配置目录**（`~/.jellyfish`）。装配类测试用
  `ApplicationContextRunner`，并把四份配置路径设成空串（`StringUtils.isBlank` 即视为未配置，
  既不读文件也不发告警）；插件扫描目录指向 JUnit 临时目录。
- **`jellyfish.lifecycle.bootstrap-on-start` 缺省设 false**：多数用例验的是「装配出哪些 Bean」，
  起一遍内核只会更慢。需要真启动的用例自己覆盖这一个属性，并断言「容器关闭后内核确实收敛了」。
- **要测命令就必须真启动内核**：系统命令是在 `AgentHarness.bootstrap()` 里注册的，不起内核时命令域是空的——
  那时测出来的「命令能跑」是假的。
- **生命周期与 Web 容器的相对顺序用真容器验**：那条性质由 Spring 的 phase 规则合成，mock 掉容器
  就等于把被测的性质抽走了。
- **不要为了让测试好写去 mock `TurnRegistry` 之类的 final 内核组件**（本仓库没有引 `mockito-inline`）：
  用一个真的 `TurnRegistry` 加一个自己写的 `ReActTurn` 存根，那样测的才是真组件的语义。

## Git 约定

- 只 `git add` 本会话实际修改的文件，禁止 `git add -A` / `git add .`。
- 提交前先 `git status` 确认暂存范围。
- 提交信息格式：`{feat,fix,docs,refactor,chore}[(scope)]: 描述`，描述简洁说明改动。
- 不要提交密钥、真实 apiKey 或本地配置文件。
- 提交代码前必须先得到允许。

## 语言

- 使用中文沟通

## 行为准则

- 开发时必须严格按与用户确认的方案执行，如果开发过程中发现方案有问题，先征求用户意见，禁止私自变更方案。
- **改了接入方可见的行为就同步改文档**：新增配置项 / 门面方法时，`README.md` 的对应位置要一起改。
