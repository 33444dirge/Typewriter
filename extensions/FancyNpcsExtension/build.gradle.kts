dependencies {
    compileOnly(files("../../compile-libs/FancyNpcs-2.12.1.jar"))
}

typewriter {
    namespace = "typewritermc"
    extension {
        name = "FancyNpcs"
        shortDescription = "使用 FancyNpcs 的 NPC 触发对话与任务。"
        description = """
            在 Typewriter 网页编辑器中引用 FancyNpcs 已创建的 NPC，以 NPC 名称或稳定的内部 ID 绑定。
            可自定义对话显示名称和声音来源，并将左键、右键、任意点击或自定义交互接入任务与剧情触发器。
            玩家交互会切回其所属的实体线程，再判断是否开始新对话或推进当前对话，适用于 Folia 26.2。
        """.trimIndent()
        engineVersion = rootProject.extra["typewriterEngineVersion"] as String
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE
        paper {
            dependency("FancyNpcs")
        }
    }
}
