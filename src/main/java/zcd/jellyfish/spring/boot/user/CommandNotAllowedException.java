package zcd.jellyfish.spring.boot.user;

import zcd.jellyfish.api.JellyfishException;

/**
 * 调用了被本层拒绝的命令时抛出。
 * <p>
 * 内核的命令域里有若干条命令会碰<b>进程级</b>状态或<b>别人的</b>会话，多用户下不能放行：
 * <ul>
 *     <li>{@code /new}、{@code /resume}——改写进程级的「当前会话」指针。一个用户的提交会变成
 *     另一个用户的「当前会话」（{@code SessionManager.switchTo} 覆盖的是同一个原子引用）；</li>
 *     <li>{@code /delete}（别名 {@code /rm}）——按参数里的会话 id 删除，参数不受本层的归属校验保护，
 *     等于提供一个删别人会话的入口；</li>
 *     <li>{@code /session}（别名 {@code /sessions}）——列出<b>本进程全部</b>会话，直接跨用户泄露；</li>
 *     <li>{@code /reload}——重载进程级配置，不是终端用户该做的事。</li>
 * </ul>
 * 另外 {@code /model} 与 {@code /agent} 不带会话时写的是「下次新建会话的默认值」，那是进程级状态，
 * 因此本层要求它们必须带会话（见 {@code JellyfishUserProperties}）。
 *
 * @author zcd
 */
public class CommandNotAllowedException extends JellyfishException {

    /** 被拒的命令名。 */
    private final String commandName;

    /**
     * 构造异常。
     *
     * @param commandName 被拒的命令名（已去掉前缀与参数）
     * @param reason      拒绝理由，写成一句给人看的话
     */
    public CommandNotAllowedException(String commandName, String reason) {
        super(reason);
        this.commandName = commandName;
    }

    /**
     * 获取被拒的命令名。
     *
     * @return 命令名
     */
    public String getCommandName() {
        return commandName;
    }
}
