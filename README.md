# jellyfish-spring-boot-starter

让**已有的 Spring Boot 应用**几行配置就拥有对接大模型的能力：一个自动配置 + 一组门面 Bean，
业务代码可以直接注入内核的会话 / 回合 / 模型 / 权限 / 工具等能力，并**通过插件系统扩展**。

内核是 [Jellyfish](https://github.com/zcd0831/Jellyfish)：三种外壳（CLI / TUI / Server）共享的同一个内核，
能力全部由插件提供。本 starter 把那个内核搬进你的 Spring 容器。

| 仓库 | 是什么 |
| --- | --- |
| [Jellyfish](https://github.com/zcd0831/Jellyfish) | 内核：外壳、装配、扩展点与插件运行时、插件 SPI |
| [Jellyfish-Plugins](https://github.com/zcd0831/Jellyfish-Plugins) | 官方插件（文件工具 / shell / 持久化 / 待办 / 压缩 / skills / MCP / 编排 / plan 模式）+ 插件开发教程 |
| **Jellyfish-Spring-Boot-Starter**（本仓库） | 把内核接进已有的 Spring Boot 应用 |

## 它不是什么

- **不提供任何 HTTP 接口**。接口由接入方自己定义（`@RestController` + `SseEmitter` 等），本 starter 只交付
  编程式 API。因此它**不依赖 `spring-boot-starter-web`**，不会替你决定 Web 技术选型。
- **不改内核**。它只依赖内核的装配模块 `jellyfish-di`，用它的 `JellyfishAssembler` 装配——
  内核那 55 个组件一个都没有在 Spring 里重建。
- **不支持同进程多套内核**。内核是进程级单例语义（「当前会话」是一个进程级指针、模型索引进程级一份），
  两套内核在同一个 JVM 里会互相污染。

## 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 8 |
| Spring Boot | 2.7（已在 2.7.18 上验过） |
| 内核 | `zcd:jellyfish-di:0.0.1-SNAPSHOT` |

**内核还没发 release**，因此开发期要先把内核装进本地仓库：

```bash
(cd /path/to/Jellyfish && mvn -q install -DskipTests)
```

忘了这一步的表现是「内核明明改了，这边编译却看不到」，或更糟——用到的是本地仓库里的旧快照。

## 引入

```xml
<dependency>
    <groupId>zcd</groupId>
    <artifactId>jellyfish-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## 快速开始

### 1. 写一份 `application.yml`

```yaml
jellyfish:
  enabled: true
  process-name: my-app

  # 路径：yml 只回答「那些文件在哪」
  models:   { global-path: '~/.jellyfish/models.json',    project-path: 'classpath:jellyfish/models.json' }
  agents:   { global-path: '~/.jellyfish/agents.json',    project-path: 'classpath:jellyfish/agents.json' }
  settings: { global-path: '~/.jellyfish/jellyfish.json', project-path: 'classpath:jellyfish/jellyfish.json' }

  plugins:
    roots: [ '~/.jellyfish/plugins/' ]     # 插件 jar 放哪；平铺 jar 或「一插件一子目录」都支持

  runtime:
    interactive: false                      # 有终端时置 true（插件据此降级）

  approval:
    enabled: false                          # 见下「审批」

  ask:
    enabled: false                          # 见下「提问」

  lifecycle:
    bootstrap-on-start: true                # false = 只装配不启动，自己控制时机
```

**内容仍然是内核格式的 JSON**（providers / models / agent 定义 / 插件启用名单 / 逐插件配置段等），
放在上面那些路径指向的文件里。把它们放进 `src/main/resources/jellyfish/` 下、用
`classpath:jellyfish/xxx.json` 指过来是最省事的做法。

最小的一份 `classpath:jellyfish/models.json`：

```json
{
  "defaultProvider": "openai",
  "defaultModel": "gpt-4o",
  "providers": {
    "openai": {
      "type": "openai",
      "apiKey": "${OPENAI_API_KEY}",
      "models": [{ "id": "gpt-4o", "name": "gpt-4o", "contextLength": 128000, "maxOutputTokens": 4096 }]
    }
  }
}
```

密钥走环境变量（**不要把密钥写进配置文件**）。这些 JSON 的全量字段见内核仓库 README 的「配置」一节。

**缺省**路径指向 `~/.jellyfish/` 与 `./.jellyfish/`，与内核 CLI 的缺省一致：同一个进程里跑 CLI 与本应用，
看到的是同一份配置。**配置缺失只告警、不中断启动**——这是内核的既有纪律，因此一份配置都不给也能起来
（只是模型与 agent 都是空的）。

### 2. 写一个 Controller

**业务侧只注入一个东西：`JellyfishUserFacade`**。它的每个方法都以 `userId` 开头，内部做归属校验与配额控制。

```java
@RestController
public class ChatController {

    private final JellyfishUserFacade jellyfish;

    public ChatController(JellyfishUserFacade jellyfish) {
        this.jellyfish = jellyfish;
    }

    /** 建会话。 */
    @PostMapping("/sessions")
    public String create(@RequestParam String userId) {
        return jellyfish.createSession(userId);
    }

    /** 对话。SSE 由你自己写，starter 只给你内核事件。 */
    @GetMapping(value = "/sessions/{sid}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam String userId, @PathVariable String sid,
                           @RequestParam String message) {
        SseEmitter emitter = new SseEmitter(0L);   // 不超时：回合可能持续几分钟
        jellyfish.chat(userId, sid, message, event -> {   // 注意：这个回调在内核线程上
            emitter.send(toFrame(event));
            if (event.isTerminal()) {
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 裁决审批。 */
    @PostMapping("/sessions/{sid}/approvals/{rid}")
    public void approve(@RequestParam String userId, @PathVariable String sid,
                        @PathVariable String rid, @RequestParam boolean ok) {
        if (!jellyfish.approve(userId, sid, rid, ok)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这条待审批项");
        }
    }
}
```

跑起来就能用了。

## 用法

### `JellyfishUserFacade` 方法一览

`createSession` / `listSessions` / `snapshot` / `closeSession` / `deleteSession` / `chat` / `cancel` /
`isTurnRunning` / `pendingApprovals` / `approve` / `pendingAsks` / `answer` / `answerWithText` /
`cancelAsk` / `executeCommand`。

### 四条必须遵守的纪律

1. **交互一律走 `JellyfishUserFacade`，不要直接用内核 Bean。** 内核 Bean（`SessionManager`、
   `CommandManager`、`ApprovalChannel`、`AskChannel`…）**没有归属校验**，绕过门面就是绕过全部护栏。
2. **不要调 `switchTo` / `createDefault`。** 那是进程级的「当前会话」指针，多用户下会让一个用户的
   会话变成所有人的。门面内部一律用 `create(agentId, provider, model)`。
3. **不要调 `/new`、`/resume`、`/delete`、`/session`、`/reload`。** 门面的 `executeCommand` 已经拦住了
   它们（可配，见下）；直接调 `CommandManager` 就绕过了闸门。
4. **`chat` 的 listener 在内核线程上被调用**（`react` / `llm-stream` / 工具输出泵，后两者会并发）：
   必须快且线程安全。写 SSE 这类 I/O 建议自己缓冲或转投线程池；listener 抛出的异常会被记日志后吞掉，
   不会影响内核的事件派发。

### 审批（`jellyfish.approval.enabled`）

`askTools` 里列了的工具在执行前要等人裁决，而裁决只能由外壳提供。本 starter 不实现审批界面，因此这里
**缺省关闭**：

- **关闭**时：需要审批的调用**立即**按拒绝处理（fail-closed），问题当场暴露；
- **打开**时：调用会阻塞 `react` 线程**最多 120 秒**再按拒绝处理。因此**接好审批接口之后再打开**。

打开之后用 `jellyfish.pendingApprovals(userId, sessionId)` 与 `jellyfish.approve(userId, sessionId, requestId, ok)`
做三件事：读本会话待裁决的那一条、读整条队列、裁决。
**只有头槽位那一条能被裁决**，对排队中的请求调用 `approve` 等于无事发生（返回 `false`）。

也可以注入内核的 `ApprovalChannel` 直接读（`pending(sessionId)` / `pendingApprovals(sessionId)`），
但**裁决必须走门面**——网关额外校验了 `requestId` 属于本会话，直接调内核是越权漏洞。

### 提问（`jellyfish.ask.enabled`）

`ask_user` 工具（官方 tools 插件提供）执行时会等用户作答，而作答只能由外壳提供。本 starter 不实现提问
界面，因此这里同样**缺省关闭**，但**代价与审批不同**：

- **关闭**时：提问**立即**返回一条「当前外壳没有交互界面，无法把提问送达用户」的**成功结果**，
  模型据此自行判断。它既不失败、也不阻塞任何线程（审批关闭时是 fail-closed，两者口径不同）；
- **打开**时：提问会阻塞 `react` 线程**最多 120 秒**再返回「用户没有回答」。因此**接好作答接口之后再打开**。

打开之后用 `jellyfish.pendingAsks(userId, sessionId)` 与下面三个方法：

```java
// 用户点了某个候选项
jellyfish.answer(userId, sessionId, requestId, "a");
// 用户自己敲了一个答案（选项都不合适时）
jellyfish.answerWithText(userId, sessionId, requestId, "都不合适，改成先做 Server");
// 放弃作答：客户端断开、用户关掉页面时用它放行，而不是等满 120 秒
jellyfish.cancelAsk(userId, sessionId, requestId, "客户端断开了");
```

与审批同口径：**只有头槽位那一条能被作答**，对排队中的提问调用它们返回 `false`。
三种终态都是正常的——问答不是权限，**放弃作答与超时都不代表任何调用被拒绝**。

也可以注入内核的 `AskChannel` 直接读（`pending(sessionId)` / `pendingAsks(sessionId)`），
但**作答必须走门面**。这一条比审批的同名要求更要紧：答案会作为**工具结果原文进那个会话的模型上下文**，
因此少了归属校验就不只是越权，还是一条把任意文本注入别人对话的路径。

## 配置

### starter 自己的配置

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `jellyfish.enabled` | `true` | `false` 时一个 Bean 都不装配 |
| `jellyfish.process-name` | `Jellyfish` | 进程名，仅用于展示与日志 |
| `jellyfish.models.global-path` / `project-path` | `~/.jellyfish/models.json` / `./.jellyfish/models.json` | 模型配置段的双源路径，支持 `classpath:` 前缀；空串表示该层级未配置 |
| `jellyfish.agents.global-path` / `project-path` | `~/.jellyfish/agents.json` / `./.jellyfish/agents.json` | agent 配置段的双源路径 |
| `jellyfish.settings.global-path` / `project-path` | `~/.jellyfish/jellyfish.json` / `./.jellyfish/jellyfish.json` | 运行期设置段的双源路径（插件名单、逐插件配置段、`react` / `permission` / `subAgent`） |
| `jellyfish.plugins.roots` | `['~/.jellyfish/plugins/']` | 插件扫描目录，顺序即扫描顺序；平铺 jar 与「一插件一子目录 + `plugin.properties`」都支持 |
| `jellyfish.runtime.interactive` | `false` | 是否有终端，插件据此降级。**外壳种类不可配**，恒为「服务化外壳」（`hasUI=false`） |
| `jellyfish.approval.enabled` | `false` | 是否挂载审批通道，见上 |
| `jellyfish.ask.enabled` | `false` | 是否挂载提问通道，见上 |
| `jellyfish.lifecycle.bootstrap-on-start` | `true` | `false` = 只装配不启动内核，自己控制时机（测试常用） |

### 多用户护栏的配置

| 字段 | 缺省 | 说明 |
| --- | --- | --- |
| `jellyfish.user.enabled` | `true` | 关掉则只装配内核，不提供任何护栏入口 |
| `jellyfish.user.max-inflight-turns-per-user` | `2` | 每用户并发回合上限，`<= 0` 表示不限 |
| `jellyfish.user.max-sessions-per-user` | `20` | 每用户会话数上限，`<= 0` 表示不限 |
| `jellyfish.user.reaper-enabled` | `true` | 是否开启闲置回收 |
| `jellyfish.user.idle-timeout` | `30m` | 多久没用算「可以回收了」 |
| `jellyfish.user.sweep-interval` | `5m` | 扫描间隔（钳到 ≥ 1 秒） |
| `jellyfish.user.denied-commands` | `[new, resume, delete, rm, session, sessions, reload]` | 命令闸门拦下的命令 |
| `jellyfish.user.session-required-commands` | `[model, agent, a]` | 这些命令必须有会话才执行 |

### 配置的分工：yml 给路径，内容仍是内核格式

| 配置 | 在哪 | 为什么 |
| --- | --- | --- |
| 四份配置文件**在哪**、插件扫描目录 | `application.yml` | 「文件在哪」是部署事实，与 Spring 的配置习惯一致 |
| providers / models / agent 定义 | `models.json` / `agents.json` | 内核已有格式，`classpath:` 前缀可用 |
| 插件启用名单、逐插件配置段、`react` / `permission` / `subAgent` 段 | `jellyfish.json` | 同上 |

**不去把 JSON 格式翻译成 yml**：那需要给内核加第二个配置写入口（内核的配置快照语义正是靠「只有
`refresh()` 一个写入口」成立的），收益只是「少一个文件」。

另外，`AppConfig` 由 yml 直接构造、**不走 `ConfigLoader.loadAppConfig()`**：后者读的是写死的
`classpath:config.json`，而 classpath 上的同名资源谁生效取决于顺序。本 starter 若自带一份就会与接入方
项目里的同名资源争夺优先级，因此让 `application.yml` 成为唯一入口。

## 用插件扩展能力

内核的全部能力都在插件里（文件工具、shell、会话持久化、待办、压缩、skills、MCP、编排、plan 模式……），
本 starter 用**与内核完全相同的加载路径**把它们接进来：

1. 把插件 jar（或「一插件一子目录」的目录）放进 `jellyfish.plugins.roots` 指向的位置，缺省
   `~/.jellyfish/plugins/`；
2. 在 `jellyfish.json` 里写启用名单与逐插件配置段：

```json
{
  "plugins": {
    "enabled": ["jellyfish-plugin-tools", "jellyfish-plugin-session-file", "jellyfish-plugin-compact"],
    "configurations": {
      "jellyfish-plugin-session-file": { "sessionDir": "~/.jellyfish/sessions", "gitEnabled": true }
    }
  },
  "react": { "autoCompactPercent": 80 }
}
```

插件从哪来、每个插件有哪些配置项、怎么写自己的插件，见
[Jellyfish-Plugins](https://github.com/zcd0831/Jellyfish-Plugins) 的 README（含**插件开发教程**）。

三条要记住的：

- **新增 / 删除插件 jar 需要重启进程**（扫描目录与插件集合只在启动期确定）；只改 `jellyfish.json` 的内容
  可以对插件热更新，但本 starter 没有暴露 `/reload` 的入口，重启最省心。
- **插件是唯一正确的扩展方式**：不要在 Spring 容器里替换内核组件（见下「注入内核 Bean」）。
- **不是所有插件都适合多租户进程**：`shell` / `mcp` / 脚本桥接不做用户隔离，见文末「使用限制」。

## 多用户护栏（本 starter 的主要价值）

内核是为「**本地单用户、多会话**」设计的，它的 HTTP 外壳也是（默认只绑回环、`GET /sessions`
返回全量会话）。它**没有「用户」这一维**——`Session` 上没有 owner 字段。下面六件事内核都不做，
由本 starter 补齐：

| 护栏 | 内核的缺口 | 本 starter 的做法 |
| --- | --- | --- |
| **会话归属** | 无 owner 字段；`all()` 是全量的 | `SessionOwnerStore`（SPI，默认内存实现）+ 每个入口先校验；「不存在」与「不属于你」对外是**同一个**答复，避免用错误信息枚举会话 id |
| **每用户并发配额** | `react` 池 8 线程 + 128 队列是**全进程**共享，一个用户能占满 | 每用户信号量，占不到立刻拒绝（不排队，否则连容器线程一起堵） |
| **每用户会话数上限** | 会话表只增不减、无上限 | 建会话时检查；「查数 + 建 + 绑定」在同一把锁里完成 |
| **闲置回收** | 无 TTL、无淘汰，消息全量驻留内存 | 定时 `close()`；**跳过有在途回合的会话**（长调用不刷新时间戳，只看时间会误杀），也跳过没有归属者的会话 |
| **审批归属** | `resolve` 只校验「是不是它自己会话的头槽位」，**不校验调用方说的会话对不对** | 网关额外要求 requestId 属于本会话且是头槽位 |
| **提问归属** | `resolve` 同上；而答案会作为工具结果原文进模型上下文 | 同一条要求，且更要紧——少了它既是越权，也是**内容注入**路径 |
| **命令入口** | `/new` `/resume` 改进程级指针；`/delete <别人的 id>` 的参数不受归属保护；`/session` 列出全进程会话 | 命令闸门（名单可配） |

**`SessionOwnerStore` 是唯一的可替换点**：它是一条 SPI，不是内核组件的视图。归属存在内存还是关系库，
只有接入方知道，因此实现它并注册成 Bean，默认实现会自动让位。

**会话持久化与归属必须一起持久化**：默认的 `InMemorySessionOwnerStore` 与会话同生命周期（内核的会话默认
也在内存里）。**一旦你给会话加了持久化（自己写插件或换存储），归属也必须一起持久化**——否则重启后会话还在、
归属没了，那些会话就变成谁也访问不到的孤儿。

## 注入内核 Bean（进阶）

除了 `JellyfishUserFacade`，starter 还把 `JellyfishRuntime` 的 21 个门面各注册成了一个 Bean，按类型即可注入：
`ConversationService`（发起对话的唯一入口）、`ShellStreams`（订阅回合事件）、`SessionManager`、
`ModelManager`、`AgentManager`、`PermissionManager`、`ApprovalChannel`、`AskChannel`、`CommandManager`、
`EventChannel`、`ExtensionRegistry`、`ConversationCompactor`、`InputDirectives`、`TurnRegistry`、`RunEventBus`、
`RuntimeConfig`、`SessionDefaults`、`RuntimeInfoHolder`、`HealthCheck`、`LlmClientFactory`、`AgentHarness`。

**但这些 Bean 刻意不做 `@ConditionalOnMissingBean`——`SessionOwnerStore` 是唯一的例外。**
它们不是可以随你替换的组件，而是同一张对象图上的视图：让接入方「再注册一个 `ModelManager`」等于在同一个
JVM 里造出第二套内核状态，而坏掉的方式是静默的（配置刷了一份、另一份永远是空的）。需要不同行为时应当用
内核的扩展点（插件），不是在 Spring 容器里替换内核组件。

加新的 `@ConditionalOnMissingBean` 之前，先问「这是内核组件的第二个视图，还是一条 SPI」。

## 生命周期

`JellyfishLifecycle` 是 `SmartLifecycle`，phase 取 `Integer.MIN_VALUE`（启动最先、停止最后），保证内核
早于 Web 容器就绪、晚于它停止。**不要改成「比 Web 容器小一点」**：Web 容器的 phase 是 Boot 的内部实现
（2.x 是 `Integer.MAX_VALUE - 1`，较新版本已改成 `Integer.MAX_VALUE - 1024`），跟它减一个魔数会在某次
升级 Boot 时静默失效，而表现是「偶发地打不通」。

本 starter **不注册 JVM shutdown hook**：Spring 自己会在上下文关闭时走 `SmartLifecycle.stop()`，再叠一个
hook 只会让「谁先关」取决于 JVM 不保证的 hook 执行顺序。

## Jackson 版本

内核的构建与测试用 `jackson-databind 2.21.6`，Boot 2.7.18 管理的是 `2.13.5`。本 starter 的 pom 覆盖了
Boot 的 `jackson-bom.version` 属性，让自己按内核那一份构建。

**宿主应用的最终 classpath 由宿主自己的 BOM 决定**，因此若你的应用把 Jackson 压回 2.13.5，不必担心——
实测两个版本下全部用例（含真解析 `models.json` / `agents.json` 的那条链路）通过：

```bash
mvn test                                # 2.21.6
mvn test -Djackson-bom.version=2.13.5   # Boot 管理版本
```

想让宿主也用内核那一份，在宿主的 pom 里设同一个属性即可。

## 使用限制

- **做不到「按用户不同权限」**：内核的权限策略按 `agentId` 全局共享，因此同一个 agent 对不同用户只能有
  同一套权限。要区分角色，就给每个角色定义一个 `agentId`，建会话时按角色选。
- **审批与提问队列都是每会话上限 8 条**：第 9 条起，审批直接按拒绝处理、提问直接返回「问不到人」；
  且只有头槽位能被裁决 / 作答，对排队中的那一条调用等于无事发生（返回 `false`）。
- **两者都会占用 `react` 线程直到被作答或超时**：`react` 池只有 8 条线程（= 并发顶层回合上限），
  因此接入方在客户端断开时应当调 `cancelAsk` / 拒绝待审项，而不是放着等满 120 秒。
- **绕过门面就是绕过全部护栏**：内核**没有「用户」概念**——`Session` 无 owner 字段、`SessionManager.all()`
  返回全进程会话、插件也不做用户隔离。护栏都在 `user/` 包里，**直接调内核 Bean 就等于把它们全部关掉**。
- **涉密能力不要装进多租户进程**：`shell`（无沙箱、可起任意子进程、无并发上限）、`mcp`（fork 外部进程）、
  `python` / `node` 桥接（要解释器、每脚本一个进程）都不做用户隔离，装进来等于把所有用户放在同一个权限面里。
- **配置改动何时生效**：`application.yml` 里的路径改动、新增 / 删除插件 jar、`models.json` / `agents.json` /
  `jellyfish.json` 的内容改动，本 starter 都建议**直接重启**（它不提供 `/reload` 的入口，内核那套热更新
  没有暴露出来）。

## License

[MIT](LICENSE)
