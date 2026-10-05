package zcd.jellyfish.spring.boot;

import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.ConfigPaths;
import zcd.jellyfish.infra.config.PluginPaths;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Jellyfish 在 Spring Boot 侧的全部配置项。
 * <p>
 * <b>它只描述「路径」与「开关」，不含任何内核内容</b>：模型 / agent / 插件启用名单等内容仍然写在内核既有的
 * JSON 里（`models.json` / `agents.json` / `jellyfish.json`），这里只回答「那些文件在哪」。
 * 理由见仓库 `AGENTS.md` 的「配置的分工」一节：把 JSON 格式翻译成 yml 需要给内核加第二个配置写入口，
 * 而内核的配置快照语义正是靠「只有 `refresh()` 一个写入口」成立的。
 * <p>
 * 缺省值与内核自己的 `config.json` 保持一致（`~/.jellyfish/` 全局级 + `./.jellyfish/` 项目级），
 * 这样同一个进程里跑 CLI 与本 starter 时看到的是同一份配置。配置文件缺失只是告警、不中断启动——
 * 这是内核的既有纪律，因此全部留空也是一份合法配置。
 * <p>
 * 纯数据类，只做反序列化与取值，文件读取由内核的配置层负责。
 *
 * @author zcd
 */
@ConfigurationProperties(prefix = "jellyfish")
public class JellyfishProperties {

    /** 全局级配置目录，与内核 `config.json` 的缺省一致。 */
    private static final String DEFAULT_GLOBAL_DIR = "~/.jellyfish/";

    /** 项目级配置目录，与内核 `config.json` 的缺省一致。 */
    private static final String DEFAULT_PROJECT_DIR = "./.jellyfish/";

    /** 模型配置段的文件名。 */
    private static final String MODELS_FILE = "models.json";

    /** agent 配置段的文件名。 */
    private static final String AGENTS_FILE = "agents.json";

    /** 运行期设置段的文件名。 */
    private static final String SETTINGS_FILE = "jellyfish.json";

    /** 插件扫描目录的缺省值，与内核 `config.json` 的缺省一致。 */
    private static final String DEFAULT_PLUGINS_ROOT = "~/.jellyfish/plugins/";

    /** 是否启用本 starter：{@code false} 时一个 Bean 都不装配。 */
    private boolean enabled = true;

    /** 进程名，进 {@link AppConfig}，仅用于展示与日志。 */
    private String processName = AppConfig.DEFAULT_PROCESS_NAME;

    /** 模型配置段的双源路径。 */
    private ConfigFiles models = ConfigFiles.of(DEFAULT_GLOBAL_DIR + MODELS_FILE,
            DEFAULT_PROJECT_DIR + MODELS_FILE);

    /** agent 配置段的双源路径。 */
    private ConfigFiles agents = ConfigFiles.of(DEFAULT_GLOBAL_DIR + AGENTS_FILE,
            DEFAULT_PROJECT_DIR + AGENTS_FILE);

    /** 运行期设置段的双源路径（插件启用名单、逐插件配置段、react / permission / subAgent）。 */
    private ConfigFiles settings = ConfigFiles.of(DEFAULT_GLOBAL_DIR + SETTINGS_FILE,
            DEFAULT_PROJECT_DIR + SETTINGS_FILE);

    /** 插件扫描目录与开关。 */
    private Plugins plugins = new Plugins();

    /** 运行期信息。 */
    private Runtime runtime = new Runtime();

    /** 人工审批。 */
    private Approval approval = new Approval();

    /** 生命周期。 */
    private Lifecycle lifecycle = new Lifecycle();

    /**
     * 判断是否启用 starter。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用 starter。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取进程名。
     *
     * @return 进程名
     */
    public String getProcessName() {
        return processName;
    }

    /**
     * 设置进程名。
     *
     * @param processName 进程名
     */
    public void setProcessName(String processName) {
        this.processName = processName;
    }

    /**
     * 获取模型配置段路径。
     *
     * @return 模型配置段路径
     */
    public ConfigFiles getModels() {
        return models;
    }

    /**
     * 设置模型配置段路径。
     *
     * @param models 模型配置段路径
     */
    public void setModels(ConfigFiles models) {
        this.models = models;
    }

    /**
     * 获取 agent 配置段路径。
     *
     * @return agent 配置段路径
     */
    public ConfigFiles getAgents() {
        return agents;
    }

    /**
     * 设置 agent 配置段路径。
     *
     * @param agents agent 配置段路径
     */
    public void setAgents(ConfigFiles agents) {
        this.agents = agents;
    }

    /**
     * 获取运行期设置段路径。
     *
     * @return 运行期设置段路径
     */
    public ConfigFiles getSettings() {
        return settings;
    }

    /**
     * 设置运行期设置段路径。
     *
     * @param settings 运行期设置段路径
     */
    public void setSettings(ConfigFiles settings) {
        this.settings = settings;
    }

    /**
     * 获取插件配置。
     *
     * @return 插件配置
     */
    public Plugins getPlugins() {
        return plugins;
    }

    /**
     * 设置插件配置。
     *
     * @param plugins 插件配置
     */
    public void setPlugins(Plugins plugins) {
        this.plugins = plugins;
    }

    /**
     * 获取运行期信息配置。
     *
     * @return 运行期信息配置
     */
    public Runtime getRuntime() {
        return runtime;
    }

    /**
     * 设置运行期信息配置。
     *
     * @param runtime 运行期信息配置
     */
    public void setRuntime(Runtime runtime) {
        this.runtime = runtime;
    }

    /**
     * 获取审批配置。
     *
     * @return 审批配置
     */
    public Approval getApproval() {
        return approval;
    }

    /**
     * 设置审批配置。
     *
     * @param approval 审批配置
     */
    public void setApproval(Approval approval) {
        this.approval = approval;
    }

    /**
     * 获取生命周期配置。
     *
     * @return 生命周期配置
     */
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    /**
     * 设置生命周期配置。
     *
     * @param lifecycle 生命周期配置
     */
    public void setLifecycle(Lifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * 转换成本 starter 交给内核的部署事实载体。
     * <p>
     * 转换本身不做任何校验：配置「可疑但可用」时内核只告警、不中断启动（既有纪律），
     * 而真正用到时才报错——在这里提前抛异常会把「少配了一个字段」变成「应用起不来」。
     *
     * @return 应用级配置，保证非 {@code null}
     */
    public AppConfig toAppConfig() {
        return new AppConfig(processName,
                models.toConfigPaths(),
                agents.toConfigPaths(),
                settings.toConfigPaths(),
                new PluginPaths(plugins.getRoots()));
    }

    /**
     * 一类配置的「全局级 + 项目级」双源文件路径对。
     * <p>
     * 路径支持 {@code classpath:} 前缀，因此接入方可以把配置放进自己的 `src/main/resources` 下，
     * 用 {@code classpath:jellyfish/models.json} 指过来，而不必落到工作目录里。
     * 空串表示该层级未配置——内核把它视为「未配置」而非「配错」，因此不会发告警。
     *
     * @author zcd
     */
    public static class ConfigFiles {

        /** 全局级配置文件完整路径，空串表示未配置。 */
        private String globalPath = "";

        /** 项目级配置文件完整路径，空串表示未配置。 */
        private String projectPath = "";

        /**
         * 构造空路径对，供 Spring 绑定使用。
         */
        public ConfigFiles() {
        }

        /**
         * 构造指定路径对。
         *
         * @param globalPath  全局级路径，可为 {@code null}
         * @param projectPath 项目级路径，可为 {@code null}
         * @return 路径对，保证非 {@code null}
         */
        public static ConfigFiles of(String globalPath, String projectPath) {
            ConfigFiles files = new ConfigFiles();
            files.globalPath = globalPath == null ? "" : globalPath;
            files.projectPath = projectPath == null ? "" : projectPath;
            return files;
        }

        /**
         * 获取全局级路径。
         *
         * @return 全局级路径，未配置时为空串
         */
        public String getGlobalPath() {
            return globalPath;
        }

        /**
         * 设置全局级路径。
         *
         * @param globalPath 全局级路径
         */
        public void setGlobalPath(String globalPath) {
            this.globalPath = globalPath == null ? "" : globalPath;
        }

        /**
         * 获取项目级路径。
         *
         * @return 项目级路径，未配置时为空串
         */
        public String getProjectPath() {
            return projectPath;
        }

        /**
         * 设置项目级路径。
         *
         * @param projectPath 项目级路径
         */
        public void setProjectPath(String projectPath) {
            this.projectPath = projectPath == null ? "" : projectPath;
        }

        /**
         * 转换成内核的路径对类型。
         *
         * @return 路径对，保证非 {@code null}
         */
        ConfigPaths toConfigPaths() {
            ConfigPaths paths = new ConfigPaths();
            paths.setGlobalPath(globalPath);
            paths.setProjectPath(projectPath);
            return paths;
        }
    }

    /**
     * 插件相关配置：只含扫描目录。
     * <p>
     * <b>扫描目录为什么在 yml、而启用名单在 `jellyfish.json`</b>：前者是「插件 jar 放在哪」，
     * 与「models.json 放在哪」同类的部署事实，随环境而变；后者是「加载后怎么用」的运行期设置，
     * 属于内容。内核自己也是这么分的（`config.json` 的 `plugins.roots` vs `jellyfish.json` 的 `plugins` 段）。
     * <p>
     * <b>可给多个目录</b>：平铺 jar 与「一个插件一个子目录 + `plugin.properties`」两种形态都支持
     * （PF4J 只找目录下一层，不递归）。新增 / 删除 jar 需要重启进程——jar 集合在启动期就定了。
     *
     * @author zcd
     */
    public static class Plugins {

        /** 插件扫描根目录，顺序即扫描顺序。 */
        private List<String> roots = new ArrayList<String>();

        /**
         * 构造缺省配置：扫描用户主目录下的插件目录。
         */
        public Plugins() {
            this.roots.add(DEFAULT_PLUGINS_ROOT);
        }

        /**
         * 获取插件扫描根目录。
         *
         * @return 目录列表，未配置时含缺省目录
         */
        public List<String> getRoots() {
            return roots;
        }

        /**
         * 设置插件扫描根目录。
         *
         * @param roots 目录列表，可为 {@code null}（等价于清空，之后由内核回退到缺省目录）
         */
        public void setRoots(List<String> roots) {
            this.roots = roots == null ? new ArrayList<String>() : roots;
        }
    }

    /**
     * 运行期信息配置：内核据此告诉插件「我正跑在什么环境里」。
     * <p>
     * <b>外壳种类在这里不可配，恒为「服务化外壳」</b>：本 starter 面向的是服务端应用，没有可交互界面，
     * 因此 `hasUI` 恒为 `false`、外壳恒为 {@code SERVER}。留一个只能配错的开关不如不留——
     * 配成 `TUI` 会让只在界面里成立的插件贡献被收集与渲染，而没有人在看。
     *
     * @author zcd
     */
    public static class Runtime {

        /** 是否有终端。服务化运行时通常为 {@code false}。 */
        private boolean interactive;

        /**
         * 判断是否有终端。
         *
         * @return 有终端返回 {@code true}
         */
        public boolean isInteractive() {
            return interactive;
        }

        /**
         * 设置是否有终端。
         *
         * @param interactive 是否有终端
         */
        public void setInteractive(boolean interactive) {
            this.interactive = interactive;
        }
    }

    /**
     * 人工审批配置。
     * <p>
     * {@code askTools} 里列了的工具在执行前会等人裁决，而裁决只能由外壳提供。本 starter 不实现任何审批
     * 界面（那是接入方的事），因此这里是一个**必须显式打开**的开关，缺省关闭。
     * <p>
     * <b>为什么缺省关闭是对的</b>：审批通道未挂载时，需要审批的调用<b>立即</b>按拒绝处理
     * （fail-closed，与 {@code -cli} 同语义）；挂上但接入方还没把界面接起来时，调用会**阻塞 react 线程
     * 最多 120 秒**再按拒绝处理。前者让问题当场暴露，后者让工具调用静默挂住两分钟——后者更难排查。
     * 因此接好审批接口之后再打开。
     *
     * @author zcd
     */
    public static class Approval {

        /** 是否挂载审批通道。 */
        private boolean enabled;

        /**
         * 判断是否挂载审批通道。
         *
         * @return 挂载返回 {@code true}
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置是否挂载审批通道。
         *
         * @param enabled 是否挂载
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 生命周期配置。
     *
     * @author zcd
     */
    public static class Lifecycle {

        /** 上下文启动时是否自动拉起内核。 */
        private boolean bootstrapOnStart = true;

        /**
         * 判断是否在上下文启动时拉起内核。
         *
         * @return 自动拉起返回 {@code true}
         */
        public boolean isBootstrapOnStart() {
            return bootstrapOnStart;
        }

        /**
         * 设置是否在上下文启动时拉起内核。
         *
         * @param bootstrapOnStart 是否自动拉起
         */
        public void setBootstrapOnStart(boolean bootstrapOnStart) {
            this.bootstrapOnStart = bootstrapOnStart;
        }
    }
}
