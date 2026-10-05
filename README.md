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

注入门面即可，20 个内核入口都注册成了 Bean：

```java
@Service
public class ChatService {

    private final SessionManager sessions;
    private final ConversationService conversation;
    private final ShellStreams streams;

    public ChatService(SessionManager sessions, ConversationService conversation, ShellStreams streams) {
        this.sessions = sessions;
        this.conversation = conversation;
        this.streams = streams;
    }

    public String createSession() {
        // 建会话用 create(...)，不要用 createDefault()——后者会改写进程级「当前会话」指针
        return sessions.create(null, null, null).getSessionId();
    }
}
```

### 两条必须遵守的纪律

1. **一律按 sessionId 寻址，永不调用 `switchTo` / `createDefault`**。
   「当前会话」是进程级单指针，多用户下它会让一个用户的会话变成别人的。
2. **不要把 `SessionManager.all()` 直接暴露给业务侧**。
   它返回本进程**全部**会话——内核没有「用户」这一维（`Session` 上没有 owner 字段），
   谁是谁的会话必须由接入方自己记账。

## 与多用户有关的话（重要）

内核是为「**本地单用户、多会话**」设计的，它的 HTTP 外壳也是（默认只绑回环、`GET /sessions` 返回全量会话）。
接进多用户服务时，下面这些**目前在内核里没有对应能力**，需要接入方自己兜：

| 缺什么 | 现状 |
| --- | --- |
| 会话归属 | `Session` 无 owner 字段；`all()` 是全量的 → 自己维护 `userId → sessionId` 并校验 |
| 每用户配额 | 全进程共享 `react` 线程池（8 线程 + 128 队列）、子代理并发许可、压缩 2 线程 → 一个用户能占满 |
| 会话回收 | 内核无 TTL、无淘汰，消息全量驻留内存 → 自己起定时任务 `close()` 闲置会话 |
| 按用户不同权限 | 策略按 `agentId` 全局共享 → 用「每角色一个 agentId」表达 |

接入方**自己实现**：HTTP 接口、鉴权、会话归属存储、会话持久化（写插件或自己落库）。

**在第一版护栏（本仓库后续版本）落地之前**，不要把 `CommandManager` 的原始入口直接暴露给业务侧
（`/new` 与 `/resume` 会改写进程级指针），也不要在多租户进程里装 `shell` / `mcp` / 脚本桥接插件
（无沙箱、可起任意子进程、无并发上限）。

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
| P2 | 多用户护栏（会话归属、配额、闲置回收、越权校验、审批网关） | 未开始 |
| P3 | 插件接入（外部目录 + 自研插件） | 未开始 |
| P4 | 内嵌插件 + 示例工程 | 未开始 |

## License

[MIT](LICENSE)
