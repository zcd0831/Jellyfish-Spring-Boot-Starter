# AGENTS.md

本文件用于指导 AI 编码代理在**本仓库**（`jellyfish-spring-boot-starter`）中工作。修改代码前请先阅读。

> **只写已落地事实**：正文只描述当前代码中存在的类与行为。未落地与明确不做的部分见「已知边界与后续项」。

## 这个仓库是什么

把 Jellyfish 内核接入**已有的 Spring Boot 应用**：一个自动配置 + 一组门面 Bean，让业务代码能注入内核的
会话 / 回合 / 模型 / 权限 / 工具等能力。

三条边界，先记住再动手：

1. **不提供任何 HTTP 接口**。接口由接入方自己写（`@RestController` + `SseEmitter` 等）。
   本 starter 只交付编程式 API。带上 `spring-boot-starter-web` 等于替接入方决定技术选型。
2. **不改内核**。starter 只依赖 `zcd:jellyfish-di`，用它的 `JellyfishAssembler` 装配。
   内核有需要改的地方，去 `Jellyfish` 仓库改，不在这里绕。
3. **一个 JVM 一个内核**。内核是进程级单例语义（`SessionManager.current()` 是单指针、型号索引进程级一份），
   因此本 starter 不试图支持「同进程两套内核」。

## 常用命令

```bash
# 0. 前置：内核必须先装进本地仓库（本仓库是独立 reactor，依赖不来自内核源码树）
(cd ../Jellyfish && mvn -q install -DskipTests)

mvn -q compile
mvn -q test                          # 全量单测（JUnit5 + Mockito + JaCoCo）
mvn -q -Dtest=JellyfishLifecycleTest test   # 单类单测
mvn -q package -DskipTests
```

**第 0 步不能省**：忘了它的表现是「内核明明改了，这边编译却看不到」，或更糟——用到的是本地仓库里的旧快照。

## 结构

```
src/main/java/zcd/jellyfish/spring/boot/
├── JellyfishAutoConfiguration.java      # 入口：条件装配 + 把下面几个 @Configuration 引进来
├── JellyfishProperties.java             # @ConfigurationProperties("jellyfish")：只描述路径与开关
├── JellyfishRuntimeConfiguration.java   # @Bean AppConfig + @Bean JellyfishRuntime（装配根）
├── JellyfishFacadeConfiguration.java    # 把 JellyfishRuntime 的 20 个门面各注册成一个 @Bean
└── JellyfishLifecycle.java              # SmartLifecycle：写 RuntimeInfo → bootstrap / shutdown

src/main/resources/META-INF/spring.factories   # Boot 2.7 的自动配置注册
```

## 约定（与内核 / 插件仓库一致的部分）

- **JDK 8**：不使用 Java 9+ 的 API 与语法。
- 缩进 4 空格，K&R 风格大括号，文件末尾保留换行。
- 注释用中文，说明「为什么」而不是复述代码。
- 类、接口、私有方法、成员变量都要有文档注释；类注释加 `@author zcd`；方法注释用 `@param` / `@return` 写清。
- 单测：JUnit5 + Mockito，类名 `{被测试类名}Test`，方法名 `{被测方法}_should_{预期结果}_when_{条件}`；
  一个测试方法只验证一个行为；用 `@ExtendWith(MockitoExtension.class)`；结构 Given/When/Then。
- 单测**不启动 Web 容器、不访问网络、不碰用户真实配置目录**（`~/.jellyfish`）。装配类测试用
  `ApplicationContextRunner`，并把四份配置路径设成空串（`StringUtils.isBlank` 即视为未配置，
  既不读文件也不发告警）。
- Git：只 `git add` 本会话实际改的文件，禁止 `git add -A` / `git add .`；
  提交信息格式 `{feat,fix,docs,refactor,chore}[(scope)]: 描述`；提交前必须先得到允许。
- 沟通语言：中文。

## 本仓库特有的技术决定（改动前务必先读）

### 装配走手工装配，不把 Dagger 塞进 Spring

`JellyfishRuntime` 是内核交付的门面接口（20 个访问器，不含任何 DI 注解），有两种实现：
Dagger 生成的 `DaggerJellyfishComponent` 与纯 Java 的 `JellyfishAssembler`。
**本 starter 用后者**：Spring 侧看不见 Dagger，也不需要用 Spring 重建内核那 55 个带 `@Inject` 的类。

**不要把 `DaggerJellyfishComponent` 引进来**：它会拖进 Dagger 的运行期依赖，并使内核加一个绑定就需要
starter 跟着改。装配知识只写一份（在 `jellyfish-di` 里），这里只调用它。

### 门面 Bean 不做 `@ConditionalOnMissingBean`

`JellyfishFacadeConfiguration` 里那 20 个 `@Bean` 刻意**不允许被覆盖**。它们是同一张对象图上的视图，
让接入方「再注册一个 `ModelManager`」等于在同一个 JVM 里造出第二套内核状态——
而坏掉的方式是静默的（配置刷了一份、另一份永远是空的）。需要不同行为时应当用内核的扩展点（插件），
不是在 Spring 容器里替换内核组件。

### `AppConfig` 由 properties 直接构造，不走 `ConfigLoader.loadAppConfig()`

`AppConfig.CONFIG_PATH` 写死为 `classpath:config.json`，而 `SettingsReader` 取的是 classpath 上
**第一个**匹配项。本 starter 若自带一份 `config.json`，就与接入方项目里的同名资源争夺优先级，
谁赢取决于 classpath 顺序。因此这里直接 `new AppConfig(...)`，让 `application.yml` 成为唯一入口。

### 配置的分工：yml 给路径，内容仍是内核格式

| 配置 | 在哪 | 为什么 |
| --- | --- | --- |
| 四份配置文件**在哪**（models / agents / jellyfish）、插件扫描目录 | `application.yml` | 「文件在哪」是部署事实，与 Spring 的配置习惯一致 |
| providers / models / agent 定义 | `models.json` / `agents.json` | 内核已有格式，`classpath:` 前缀可用 |
| 插件启用名单、逐插件配置段、`react` / `permission` / `subAgent` 段 | `jellyfish.json` | 同上 |

后两类也可以放进 `src/main/resources/jellyfish/` 下，用 `classpath:jellyfish/xxx.json` 指过来。
**不去把 JSON 格式翻译成 yml**：那需要给内核加第二个配置写入口（内核的快照语义正是靠「只有
`refresh()` 一个写入口」成立的），收益只是「少一个文件」。

### `RuntimeInfo` 必须如实反映「有没有审批通道」

`JellyfishLifecycle.start()` 在 `bootstrap()` 之前写 `RuntimeInfo`。这里有一个**不能用工厂方法的坑**：

- `RuntimeInfo.server(...)` 恒把 `supportsApproval` 置为 `true`。若我们没 `attach()` 审批通道却这样声明，
  插件会按「审批可用」去规划行为（例如提供需要写权限的工具），结果是每次调用白等 120 秒再失败——
  比直接拒绝更糟。
- 因此在「server 外壳 + 未启用审批」这个组合下，用 `new RuntimeInfo(Shell.SERVER, false, false, interactive)`
  如实填写。

### phase 取最小值，是为了不依赖 Boot 的内部常量

`JellyfishLifecycle.getPhase()` 返回 `Integer.MIN_VALUE`，即「启动最先、停止最后」。需要保证的只有一条：
内核早于 Web 容器就绪、晚于它停止（Spring 启动按 phase 升序、停止按降序，否则请求会打进一个已经关掉的
线程池）。

**不要改成「比 Web 容器小一点」**：Web 容器的 phase 是 Boot 的内部实现（2.x 是 `Integer.MAX_VALUE - 1`，
较新版本已改成 `Integer.MAX_VALUE - 1024`）。跟它减一个魔数会在某次升级 Boot 时静默失效，而表现是
「偶发地打不通」，排查成本极高。取最小值与那个数字无关。

顺序由 `JellyfishLifecycleTest` 里一个真容器用例钉住：注册一个 phase 为 `Integer.MAX_VALUE - 1` 的
假 Web 生命周期，断言启动顺序是「内核 → Web」、停止顺序是「Web → 内核」。

### 为什么不注册 JVM shutdown hook

内核的外壳（`Launcher`、`JellyfishServer.start()`）都会注册 hook，本 starter **不注册**：
Spring 自己会在上下文关闭时走 `SmartLifecycle.stop()`，再叠一个 hook 只会让「谁先关」取决于 JVM
不保证的 hook 执行顺序。收敛顺序由 phase 决定。

### Jackson 版本：两个版本都实测过

内核的构建与测试用 `jackson-databind 2.21.6`，Boot 2.7.18 管理 `2.13.5`。本仓库的 pom **覆盖了 Boot 的
`jackson-bom.version` 属性**，让自己按内核那一份构建——否则「内核在 2.21.6 上测过、starter 却在 2.13.5 上跑」
这件事没人知道。

**覆盖只影响本仓库自身的构建**：宿主应用最终 classpath 上的版本由宿主自己的 BOM 决定。因此这里实测了
两个版本，结论是都可用（含 `JellyfishConfigLoadingTest` 那条真解析 JSON 的链路）：

```bash
mvn test                                # 2.21.6
mvn test -Djackson-bom.version=2.13.5   # Boot 管理版本
```

改 Jackson 版本、或升级 Boot 之后，请把这两条各跑一遍——本仓库的单测**默认几乎不读配置文件**
（路径被清空），只有 `JellyfishConfigLoadingTest` 会走到反序列化那条链。

### `JellyfishStarterRegistrationTest` 是 `spring.factories` 的唯一守卫

其它用例都用 `AutoConfigurations.of(JellyfishAutoConfiguration.class)` **显式点名**加载配置类，
因此 `spring.factories` 里类名写错、漏写、或换 Boot 版本后注册失效，它们全都照样绿。
只有这个用例走完整 `SpringApplication`，真正去读 `spring.factories`（已验证：故意写错类名它就会红）。
**改那个文件之后必须跑它。**

## 测试的写法

- 用 `ApplicationContextRunner` 起非 Web 容器，**每个用例都把四份配置路径清空**
  （缺省指向 `~/.jellyfish/`，那是使用者的真实环境：单测碰它既不可重复、又会互相污染），
  插件扫描目录指向 JUnit 临时目录。
- `jellyfish.lifecycle.bootstrap-on-start` 缺省设 false：多数用例验的是「装配出哪些 Bean」，
  起一遍内核只会更慢。需要真启动的用例自己覆盖这一个属性，并断言「容器关闭后内核确实收敛了」——
  否则测试进程里会静默累积后台线程。
- 生命周期与 Web 容器的相对顺序用**真容器**验（`JellyfishLifecycleOrderingTest`）：
  那条性质由 Spring 的 phase 规则合成，mock 掉容器就等于把被测的性质抽走了。

## 已知边界与后续项

- **已落地**：装配、生命周期、门面 Bean、yml 配置、装配契约测试。
- **明确不做**：HTTP 接口（接口归接入方）；同进程多套内核。
- **未落地**（分期见仓库 README）：多用户护栏（会话归属、每用户配额、闲置回收、越权校验、审批网关）、
  插件接入（外部目录 + 内嵌）、示例工程。
- **多用户提醒**：内核**没有「用户」概念**——`Session` 无 owner 字段、`SessionManager.all()` 返回全进程会话、
  插件也不做用户隔离。因此**在护栏落地之前，不要把 `sessionManager.all()` 或 `CommandManager` 的原始入口
  直接暴露给业务侧**；涉密能力（`shell` / `mcp` / 脚本桥接）不要装进多租户进程。
