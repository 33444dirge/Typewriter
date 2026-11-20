dependencies {
    compileOnly(files("../../compile-libs/JiuJiuWelfare-quest-api.jar"))
    compileOnly(files("../../compile-libs/luckperms-api-5.4.jar"))
    testImplementation(files("../../compile-libs/JiuJiuWelfare-quest-api.jar"))
}

typewriter {
    namespace = "jiujiu"
    extension {
        name = "JiuJiuQuest"
        shortDescription = "啾啾主线：一次性奖励、精确地点与称号解锁。"
        description = "啾啾空岛主线任务专用扩展，任务目标、线索和进度仍由Typewriter保存。钱和普通物品通过啾啾福利原有持久账本按阶段结算，重看对白不会重复领取，背包满或结果不确定时保留记录。提供精确世界与地点判断，以及通过LuckPerms持久解锁纪念称号权限的动作。"
        engineVersion = rootProject.extra["typewriterEngineVersion"] as String
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE
        paper {
            dependency("JiuJiuWelfare")
            dependency("LuckPerms")
        }
    }
}
tasks.withType<Jar>().configureEach {
    manifest.attributes["JiuJiu-Quest-Revision"] = "zero-chapter-20261108-1"
}
