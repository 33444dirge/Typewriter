package com.typewritermc.engine.paper.command

import com.typewritermc.engine.paper.plugin

internal enum class CommandMessage(val key: String, val defaultText: String) {
    VERSION("version", "当前 Typewriter 版本：<b>{version}</b> <gray>（简体中文修订 1）</gray>"),
    RELOADING("reloading", "正在重载配置……"),
    RELOADED("reloaded", "配置重载完成！"),
    NO_EXTENSIONS("extensions.empty", "没有找到扩展，请检查 plugins/Typewriter/extensions 目录。"),
    EXTENSIONS_TITLE("extensions.title", "扩展列表"),
    EXTENSIONS_LOADED("extensions.loaded", "已加载"),
    EXTENSIONS_FAILED("extensions.failed", "加载失败"),
    EXTENSION_DETAILS("extensions.details", "扩展详情"),
    CLICK_DETAILS("extensions.click-details", "点击查看详情"),
    UNSUPPORTED_EXTENSIONS("extensions.unsupported", "⚠ 检测到 {count} 个不受支持的扩展"),
    COMPONENTS("extensions.components", "组件统计"),
    ENTRIES("extensions.entries", "条目"),
    LISTENERS("extensions.listeners", "监听器"),
    INJECTIONS("extensions.injections", "依赖注入"),
    COMMANDS("extensions.commands", "命令"),
    EXTENSION_DEPENDENCIES("extensions.extension-dependencies", "所需扩展"),
    PLUGIN_DEPENDENCIES("extensions.plugin-dependencies", "所需插件"),
    FLAGS("extensions.flags", "扩展标记"),
    NOT_LOADED("extensions.not-loaded", "未加载"),
    JAR_FILE("extensions.jar-file", "JAR 文件"),
    FAILURE_REASON("extensions.failure-reason", "失败原因"),
    EXTENSION_VERSION("extensions.version", "版本"),
    NAMESPACE("extensions.namespace", "命名空间"),
    ENGINE_VERSION("extensions.engine-version", "引擎版本"),
    DESCRIPTION("extensions.description", "说明"),
    FACT_SET("facts.set", "已将 <green>{player}</green> 的变量 <blue>{fact}</blue> 设为 {value}。"),
    FACT_NOT_READABLE("facts.not-readable", "该变量不支持读取，无法执行累加操作。"),
    NO_FACTS("facts.empty", "没有可用的变量。"),
    FACTS_RESET("facts.reset", "已重置 <green>{player}</green> 的全部变量。"),
    FACT_QUERY("facts.query", "<green>{player}</green> 的变量："),
    FACTS_PAGE("facts.page", "<green>{player}</green> 在页面 <blue>{page}</blue> 中的变量："),
    NO_PAGE_FACTS("facts.page-empty", "该页面没有变量。"),
    FACTS_LIST("facts.list", "<green>{player}</green> 的变量如下：\n"),
    FACTS_MORE(
        "facts.more",
        """
            |<gray><i>另有 {count} 项……</i></gray>
            |
            |<gray>使用 <white>/tw facts query [fact_id]</white> 查询指定变量。</gray>
            |<gray>使用 <white>/tw facts inspect [page_name]</white> 查看页面中的全部变量。</gray>
        """.trimMargin(),
    ),
    CLICK_MODIFY("facts.click-modify", "点击修改"),
    CONNECT_DISABLED("connect.disabled", "网页编辑器尚未开启，请在 config.yml 中同时启用 enabled 和 panel.enabled，然后重启服务端。"),
    CONNECT_LINK("connect.link", "请打开 <blue>{url}</blue> 连接剧情编辑器。"),
    CONNECT_BOOK_TITLE("connect.book-title", "<aqua>连接剧情编辑器</aqua>"),
    CONNECT_BOOK_AUTHOR("connect.book-author", "<blue>Typewriter</blue>"),
    CONNECT_BOOK_PAGE(
        "connect.book-page",
        """
            |<dark_aqua><bold>剧情编辑器</bold></dark_aqua>
            |
            |<#3e4975>点击下方链接，连接
            |网页剧情编辑器。
            |连接后即可编写剧情。</#3e4975>
            |
            |<hover:show_text:'<gray>点击打开剧情编辑器'><click:open_url:'{url}'><blue><bold>【打开编辑器】</bold></blue></click></hover>
            |
            |<gray>链接 5 分钟内有效。
            |过期后请重新执行
            |/tw connect。</gray>
        """.trimMargin(),
    ),
    CONNECT_CONSOLE_ONLY("connect.console-only", "仅控制台可以为其他玩家生成编辑器连接。"),
    NO_PLAYERS("connect.no-players", "没有找到玩家。"),
    CONNECT_SINGLE_PLAYER("connect.single-player", "每次只能为一名玩家生成连接。"),
    NO_AUDIENCE("audience.empty", "你当前未加入任何受众条目。"),
    AUDIENCE_LIST("audience.list", "你当前加入的受众条目："),
    NO_PAGE_AUDIENCE("audience.page-empty", "页面 {page} 中没有受众条目。"),
    AUDIENCE_PAGE("audience.page", "页面 <i>{page}</i> 中的受众条目："),
    AUDIENCE_IN("audience.in", "已加入受众"),
    AUDIENCE_BLOCKED("audience.blocked", "未通过筛选"),
    AUDIENCE_NOT_CONSIDERED("audience.not-considered", "尚未参与筛选"),
    PLAYER_ONLY("player-only", "此命令需要由玩家执行，或指定目标玩家。"),
    ;

    fun text(vararg replacements: Pair<String, Any>): String {
        val value = plugin.configurationFile.getOrDefault(path, defaultText, comment)
        val template = value as? String ?: defaultText
        val parameters = replacements.toMap()
        return placeholder.replace(template) { match ->
            parameters[match.groupValues[1]]?.toString() ?: match.value
        }
    }

    private val path: String get() = "messages.commands.$key"
    private val comment: String
        get() = "支持 MiniMessage 颜色格式；保留原有 {参数} 占位符。修改后执行 /tw reload 生效。"

    companion object {
        private val placeholder = Regex("\\{([a-zA-Z0-9_-]+)\\}")

        fun registerDefaults() {
            entries.forEach { message ->
                plugin.configurationFile.registerDefault(message.path, message.defaultText, message.comment)
            }
        }
    }
}
