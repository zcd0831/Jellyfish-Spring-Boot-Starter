# jellyfish-spring-boot-starter

把 [Jellyfish](https://github.com/zcd0831/Jellyfish) 内核接入**已有的 Spring Boot 应用**：
一个自动配置 + 一组门面 Bean，让业务代码能注入内核的会话 / 回合 / 模型 / 权限 / 工具等能力。

## 它不是什么

- **不提供任何 HTTP 接口**。接口由接入方自己定义（`@RestController` + `SseEmitter` 等），
  本 starter 只交付编程式 API。因此它**不依赖 `spring-boot-starter-web`**，不会替你决定 Web 技术选型。
- **不改内核**。它只依赖内核的装配模块 `jellyfish-di`，用它的 `JellyfishAssembler` 装配——
  内核那 55 个组件一个都没有在 Spring 里重建。
- **不支持同进程多套内核**。内核是进程级单例语义（「当前会话」是一个进程级指针、模型索引进程级一份），
  两套内核在同一个 JVM 里会互相污染。

## 环境

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

## 配置

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

  lifecycle:
    bootstrap-on-start: true                # false = 只装配不启动，自己控制时机
```

**内容仍然是内核格式的 JSON**（providers / models / agent 定义 / 插件启用名单 / 逐插件配置段等），
放在上面那些路径指向的文件里。把它们放进 `src/main/resources/jellyfish/` 下、用 `classpath:jellyfish/xxx.json`
指过来是最省事的做法。配置缺失只告警、不中断启动——这是内核的既有纪律。

**缺省**路径指向 `~/.jellyfish/` 与 `./.jellyfish/`，与内核 CLI 的缺省一致：同一个进程里跑 CLI 与本应用，
看到的是同一份配置。

### 审批（`approval.enabled`）

`askTools` 里列了的工具在执行前要等人裁决，而裁决只能由外壳提供。本 starter 不实现审批界面，因此这里
**缺省关闭**：

- **关闭**时：需要审批的调用**立即**按拒绝处理（fail-closed），问题当场暴露；
- **打开**时：调用会阻塞 `react` 线程**最多 120 秒**再按拒绝处理。因此**接好审批接口之后再打开**。

打开之后用注入的 `ApprovalChannel` 做三件事：读本会话待裁决的那一条（`pending(sessionId)`）、
读整条队列（`pendingApprovals(sessionId)`）、裁决（`resolve(id, approved)`）。
**只有头槽位那一条能被裁决**，对排队中的请求调用 `resolve` 等于无事发生。

## 用

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

方法一览：`createSession` / `listSessions` / `snapshot` / `closeSession` / `deleteSession` / `chat` /
`cancel` / `isTurnRunning` / `pendingApprovals` / `approve` / `executeCommand`。

### 四条必须遵守的纪律

1. **交互一律走 `JellyfishUserFacade`，不要直接用内核 Bean。** 内核 Bean（`SessionManager`、
   `CommandManager`、`ApprovalChannel`…）**没有归属校验**，绕过门面就是绕过全部护栏。
2. **不要调 `switchTo` / `createDefault`。** 那是进程级的「当前会话」指针，多用户下会让一个用户的
   会话变成所有人的。门面内部一律用 `create(agentId, provider, model)`。
3. **不要调 `/new`、`/resume`、`/delete`、`/session`、`/reload`。** 门面的 `executeCommand` 已经拦住了
   它们（可配，见下）；直接调 `CommandManager` 就绕过了闸门。理由见
   `CommandNotAllowedException` 的 javadoc。
4. **`chat` 的 listener 在内核线程上被调用**（`react` / `llm-stream` / 工具输出泵，后两者会并发）：
   必须快且线程安全。写 SSE 这类 I/O 建议自己缓冲或转投线程池；listener 抛出的异常会被记日志后吞掉，
   不会影响内核的事件派发。

### 配置

```yaml
jellyfish:
  user:
    enabled: true                 # 关掉则只装配内核，不提供任何护栏入口
    max-inflight-turns-per-user: 2    # <= 0 表示不限
    max-sessions-per-user: 20         # <= 0 表示不限
    reaper-enabled: true
    idle-timeout: 30m                 # 多久没用算「可以回收了」
    sweep-interval: 5m
    denied-commands: [ new, resume, delete, rm, session, sessions, reload ]
    session-required-commands: [ model, agent, a ]
```

## 多用户护栏（本 starter 的主要价值）

内核是为「**本地单用户、多会话**」设计的，它的 HTTP 外壳也是（默认只绑回环、`GET /sessions`
返回全量会话）。它**没有「用户」这一维**——`Session` 上没有 owner 字段。下面六件事内核都不做，
由本 starter 补齐：

| 护栏 | 内核的缺口 | 本 starter 的做法 |
| --- | --- | --- |
| **会话归属** | 无 owner 字段；`all()` 是全量的 | `SessionOwnerStore`（SPI，默认内存实现）+ 每个入口先校验；「不存在」与「不属于你」对外是**同一个**答复，避免用错误信息枚举会话 id |
| **每用户并发配额** | `react` 池 8 线程 + 128 队列是**全进程**共享，一个用户能占满 | 每用户信号量，占不到立刻拒绝（不排队，否则连容器线程一起堵） |
| **每用户会话数上限** | 会话表只增不减、无上限 | 建会话时检查；「查数 + 建 + 绑定」在同一把锁里完成 |
| **闲置回收** | 无 TTL、无淘汰，消息全量驻留内存 | 定时 `close()`；**跳过有在途回合的会话**（长调用不刷新时间戳，只看时间会误杀） |
| **审批归属** | `resolve` 只校验「是不是它自己会话的头槽位」，**不校验调用方说的会话对不对**——拿着「我的会话 + 别人的 requestId」就能批准别人的工具调用 | 网关额外要求 requestId 属于本会话且是头槽位 |
| **命令入口** | `/new` `/resume` 改进程级指针；`/delete <别人的 id>` 的参数不受归属保护；`/session` 列出全进程会话 | 命令闸门（可配名单） |

### 会话持久化与归属必须一起持久化

默认的 `InMemorySessionOwnerStore` 与会话同生命周期（内核的会话默认也在内存里）。
**一旦你给会话加了持久化（自己写插件或换存储），归属也必须一起持久化**——
否则重启后会话还在、归属没了，那些会话就变成谁也访问不到的孤儿。

替换方式：自己实现 `SessionOwnerStore` 并注册成 Bean，默认实现会自动让位。

## Jackson 版本

内核的构建与测试用 `jackson-databind 2.21.6`，Boot 2.7.18 管理的是 `2.13.5`。本 starter 的 pom
覆盖了 Boot 的 `jackson-bom.version` 属性，让自己按内核那一份构建。

**宿主应用的最终 classpath 由宿主自己的 BOM 决定**，因此若你的应用把 Jackson 压回 2.13.5，不必担心——
实测两个版本下 26 个用例（含真解析 `models.json` / `agents.json` 的那条链路）全部通过：

```bash
mvn test                                # 2.21.6
mvn test -Djackson-bom.version=2.13.5   # Boot 管理版本
```

想让宿主也用内核那一份，在宿主的 pom 里设同一个属性即可。

## 分期

| 期 | 内容 | 状态 |
| --- | --- | --- |
| P1 | 装配 + 生命周期 + 门面 Bean + yml 配置 + 装配契约测试 | ✅ 已落地 |
| P2 | 多用户护栏（会话归属、并发配额、会话数上限、闲置回收、审批网关、命令闸门） | ✅ 已落地 |
| P3 | 插件接入（外部目录 + 自研插件） | 未开始 |
| P4 | 内嵌插件 + 示例工程 | 未开始 |

**不做的**：HTTP 接口（归接入方）；同进程多套内核。

### 仍未覆盖的（P2 之后）

- **按用户不同权限**：内核的策略按 `agentId` 全局共享，做不到「同一个 agent 对不同用户不同权限」。
  目前的表达方式是「每角色一个 `agentId`」。
- **审批队列深度与公平性**：内核每会话队列上限 8 条，第 9 条起直接按拒绝处理；
  且只有头槽位可见（`pendingApprovals` 给的是本会话整条队列，这是 P0 在内核侧补的读取面）。
- **不在多租户进程里装高危插件**：`shell`（无沙箱、可起任意子进程、无并发上限）、
  `mcp`（fork 外部进程）、`python` / `node` 桥接（要解释器、每脚本一个进程）。
  这些插件的隔离不在本 starter 的范围内。

## License

[MIT](LICENSE)
