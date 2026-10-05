package zcd.jellyfish.spring.boot.user;

import zcd.jellyfish.infra.command.CommandManager;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 命令闸门：把内核命令域里「会碰进程级状态或别人的会话」的命令挡在多用户入口之外。
 * <p>
 * <b>为什么命令域必须收口而不能直接暴露</b>：内核的命令域是<b>对外壳中立</b>的，它服务的是「一个人在自己
 * 终端上操作」这个场景，因此好几条命令直接操作进程级状态，或者按参数里的会话 id 动手——而那个参数
 * <b>不受本层归属校验的保护</b>。逐条看：
 * <ul>
 *     <li>{@code /new}、{@code /resume}——写进程级的「当前会话」原子引用。A 用户的 {@code /resume}
 *     会把 B 用户接下来的 {@code /status} 指到 A 的会话上；</li>
 *     <li>{@code /delete}（别名 {@code /rm}）——从<b>命令参数</b>里取会话 id 去删。本层校验的是调用时
 *     传的 sessionId，命令原文里的那个 id 完全不被检查，等于一个删别人会话的入口；</li>
 *     <li>{@code /session}（别名 {@code /sessions}）——列出本进程<b>全部</b>会话（id 与标题），
 *     直接跨用户泄露；</li>
 *     <li>{@code /reload}——重载进程级配置（模型 / agent / 插件），不是终端用户该做的事。</li>
 * </ul>
 * <b>还有一类不拒绝、但要求带会话</b>：{@code /model} 与 {@code /agent} 不带会话时写的是
 * {@code SessionDefaults}，也就是「下次新建会话的默认值」——同样是进程级状态，会影响所有用户。
 * 带上会话时它们只改这一个会话，是安全的。
 * <p>
 * 名单可配（{@code jellyfish.user.denied-commands} / {@code session-required-commands}）。
 * 比较是<b>大小写敏感</b>的：内核的命令名一律小写，而 {@code /HELP} 那种写法本来也匹配不到命令。
 *
 * @author zcd
 */
public class CommandGate {

    /** 直接拒绝的命令名。 */
    private final Set<String> denied;

    /** 必须带会话才准执行的命令名。 */
    private final Set<String> sessionRequired;

    /**
     * 构造闸门。
     *
     * @param properties 配置，不可为 {@code null}
     */
    public CommandGate(JellyfishUserProperties properties) {
        this.denied = new LinkedHashSet<String>(properties.getDeniedCommands());
        this.sessionRequired = new LinkedHashSet<String>(properties.getSessionRequiredCommands());
    }

    /**
     * 检查一条命令能否在本次调用里执行。
     *
     * @param commandLine 命令原文，形如 {@code /compact preview}
     * @param sessionId   调用方给出的会话标识，可为 {@code null}
     * @throws CommandNotAllowedException 命中拒绝名单，或属于「必须带会话」却没带时抛出
     */
    public void check(String commandLine, String sessionId) {
        String name = nameOf(commandLine);
        if (name.isEmpty()) {
            return;
        }
        if (denied.contains(name)) {
            throw new CommandNotAllowedException(name,
                    "命令 /" + name + " 在多用户入口下不可用（它会操作进程级状态或其它会话）");
        }
        if (sessionId == null && sessionRequired.contains(name)) {
            throw new CommandNotAllowedException(name,
                    "命令 /" + name + " 必须指定会话：不带会话时它修改的是「新建会话的默认值」，属于进程级状态");
        }
    }

    /**
     * 从命令原文里取出命令名（不含前缀与参数）。
     * <p>
     * 刻意只做「取第一个空白之前的词」这一件事，不引用内核的命令解析：
     * 把 {@code CommandManager} 的解析结果拿来做安全判定，等于把判定权交给了被判定的一方
     * （它认识别名、也认补全，而这里要的是「用户到底敲了哪个词」）。取不到命令名时返回空串，
     * 由调用方放行给内核自己去报「未知命令」。
     *
     * @param commandLine 命令原文，可为 {@code null}
     * @return 命令名（小写前缀已去掉）；不是命令时为空串
     */
    public String nameOf(String commandLine) {
        if (commandLine == null) {
            return "";
        }
        String trimmed = commandLine.trim();
        if (!trimmed.startsWith(CommandManager.COMMAND_PREFIX)) {
            return "";
        }
        String withoutPrefix = trimmed.substring(CommandManager.COMMAND_PREFIX.length());
        int end = withoutPrefix.length();
        for (int i = 0; i < withoutPrefix.length(); i++) {
            if (Character.isWhitespace(withoutPrefix.charAt(i))) {
                end = i;
                break;
            }
        }
        return withoutPrefix.substring(0, end);
    }

    /**
     * 获取拒绝名单（供诊断与文档展示）。
     *
     * @return 不可修改的命令名清单
     */
    public List<String> deniedCommands() {
        return Arrays.asList(denied.toArray(new String[0]));
    }

    /**
     * 获取「必须带会话」名单（供诊断与文档展示）。
     *
     * @return 不可修改的命令名清单
     */
    public List<String> sessionRequiredCommands() {
        return Arrays.asList(sessionRequired.toArray(new String[0]));
    }
}
