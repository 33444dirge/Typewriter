# JiuJiu 与上游对齐记录

上游：[33444dirge/Typewriter](https://github.com/33444dirge/Typewriter)，默认分支 `develop`。

本次合入提交：`94ec4238d95a5a65ce54270acf036800ea302085`（2026-11-17）。本地原基线：`f124f70a2bf7e49f8ab65950c9dea19ead7427e4`（beta-177）。上游在两者之间有 31 个提交，239 个文件变化，约 9,302 行新增和 901 行删除；大部分新文件是编辑器素材。

当前版本：`0.9.0-jiujiu-folia26.2.1`，目标 Java 25 / Folia 26.2。远端只推送 `jiujiu` 分支，`develop` 保持原状。

## 上游更新及接入结果

| 更新 | 来源 | 本分支结果 |
| --- | --- | --- |
| 观众父子过滤切换保持一致 | `df56a35a` | 保留上游成员事务锁，并在玩家所属实体线程上执行增删与子节点传播；合入 AudienceMembershipSpec |
| 单显示器只由所属过滤器 tick | `ab40aa06` | 保留本地已有 ref 所属校验、按玩家合并 tick 和实体线程调度 |
| NPC 穿过围栏拐角、台阶与边缘的寻路修复 | `67cacce2`、`2f17730c` | 合入 NodeClearance、侧移、节点到达规则、抬升后横移的跳跃碰撞检查和失败路线终止；所有运行时碰撞读取继续使用所属区域采集的方块快照 |
| 犰狳、蝙蝠、末影螨、蠹虫、骷髅马、物品展示框 | `59c58bec`、`96119519`、`3e8a56de`、`c3a35095`、`7d4fad53`、`25c4075e` | 新实体定义、实例及相应状态/朝向数据均合入，包含新增实体清单测试 |
| 鹦鹉坐姿、羊驼和玩家 NPC 数据修复 | `f48e405b` 等 | 保留坐骑退休清理，并合入玩家 NPC 名称唯一性和计分板成员接口 |
| 观察目标的俯仰角修复、挥臂数据 | `e7c5abea` 等 | 已合入 |
| PacketEvents 2.14.0 | `7db034f2` | 引擎与扩展测试依赖均升至 2.14.0，保留尚无 Bukkit 玩家对象时的安全检查 |
| Minecraft 26.1–26.3 物品与图标 | `328158b6` | app 模型、版本枚举、图标素材及物品生成输入均与本次上游提交一致；发布包使用重新构建的网页 |

## 相对上游保留的本地差异

- Folia：区域/实体调度、玩家退休回调、所属线程检查、可取消协程生命周期、有界事件/寻路预算、独立方块碰撞快照。
- 对话与镜头：外部 GUI 事件、四选项分页、输入确认与音效、潜行退出、固定镜头、玩家状态恢复及镜头资源清理。
- 并发与存储：事实状态合并、会话失效、并发 YAML、关闭期刷新与缓存上限。
- 集成：FancyNpcs 公开事件适配、MythicMobs / WorldGuard / Vault 的 Folia 线程边界、JiuJiuQuestExtension 的地点判断和奖励账本。
- 中文消息、启动命令保留标签、本地真实 Folia 26.2 API 和编译输入。上游通用目标为 Java 21，本分支继续使用 Java 25。
- 默认仍排除 Citizens、SuperiorSkyblock、RPGRegions 三个可选扩展；它们的上游源码在 Git 历史中保留。上游文档站、机器人、设计文件等未从分支历史删除，本地使用 sparse checkout 只展开运行相关目录。

`app/lib/` 与素材当前无相对上游的源码差异。Flutter SDK 更新了锁文件中的 `meta`、`vector_math` 两项依赖，并在静态分析配置中排除构建及平台目录。引擎和扩展使用上游为合并父提交，后续可以直接 `git fetch upstream` 后合并 `upstream/develop`，无需再把归档源码当作无历史目录。

## 构建

依赖输入见 [compile-libs/SOURCES.md](compile-libs/SOURCES.md)。Gradle wrapper 为 9.8.2，Java 25；网页使用与上游 CI 相同的 Flutter `3.48.0-0.1.pre`。

以下命令在仓库根目录执行。引擎、构建插件、扩展存在 composite build 共享输出目录，必须依次执行，避免并行 clean：

```bat
flutter --version
cd app
flutter pub get
flutter analyze --no-fatal-warnings --no-fatal-infos
flutter test
flutter build web --release
cd ..
engine\gradlew.bat -p engine build check :engine-paper:buildRelease --no-configuration-cache --console=plain
module-plugin\gradlew.bat -p module-plugin build --no-configuration-cache --console=plain
extensions\gradlew.bat -p extensions build --no-configuration-cache --console=plain
```

主插件：`jars/engine/Typewriter-0.9.0-jiujiu-folia26.2.1.jar`。扩展：`extensions/扩展名/build/libs/*-all.jar`，安装在 `plugins/Typewriter/extensions/`。

旧 `web-assets/` 只作本地历史回退，不进入 Git；正式发布应先执行 Flutter 构建，确保新素材进入主包。

构建结果、测试数及产物 SHA256 记录在本文件的验证部分。本地编译和自动测试不代替实服玩家、跨区域 NPC 走位和 Minecraft 客户端的验收。

## 分支历史

1. 在 beta-177 基线建立 `jiujiu`，提交本地已维护的 Folia / GUI / 主线适配。
2. 合并 `upstream/develop` 的 `94ec4238d95a5a65ce54270acf036800ea302085`，逐处整合冲突。
3. 补齐版本标记、依赖清单、构建与差异说明，构建通过后推送 `HEAD:refs/heads/jiujiu`。

## 验证结果

验证日期：2026-11-21（Asia/Hong_Kong）。

| 范围 | 测试数 | 失败 / 错误 |
| --- | ---: | ---: |
| engine | 225 | 0 / 0 |
| extensions | 157 | 0 / 0 |
| module-plugin | 0 | 0 / 0 |

引擎、模块构建工具和所有启用扩展的 Gradle build 均通过；模块构建工具包含 Gradle 插件配置校验。

Flutter pub get、静态检查、44 项测试和 release web 构建通过。静态检查沿用上游 CI 的 `--no-fatal-warnings --no-fatal-infos`，有 1 项既有 analyzer legacy plugin 弃用警告，0 个错误。

发布主包从当前 `app/build/web` 收集网页资源，并核对包含 `26.1–26.3` 新增素材。主包和扩展的清单版本使用同一 `0.9.0-jiujiu-folia26.2.1` 引擎版本。

| 产物 | SHA256 |
| --- | --- |
| `jars/engine/Typewriter-0.9.0-jiujiu-folia26.2.1.jar` | `cf8a8cefd950b1ad983def9a42077d47f1342f7f7eba0fd5db06d82a533f98c7` |
| `extensions/BasicExtension/build/libs/BasicExtension-0.9.0-jiujiu-folia26.2.1-all.jar` | `53430fbcad34f80e3f1ad737662bd5f2f234c898c317bcadcca85627382d7e19` |
| `extensions/EntityExtension/build/libs/EntityExtension-0.9.0-jiujiu-folia26.2.1-all.jar` | `499bdf784dcf1bb5d273daa036aa4e1d82984ff7d68f88053b78805570eefb7c` |
| `extensions/FancyNpcsExtension/build/libs/FancyNpcsExtension-0.9.0-jiujiu-folia26.2.1-all.jar` | `e8a574b22840a4967c7d525ecb24ccdfa199c66c6a291eacd495198ee6f6c2be` |
| `extensions/JiuJiuQuestExtension/build/libs/JiuJiuQuestExtension-0.9.0-jiujiu-folia26.2.1-all.jar` | `e589f7debcb91364631b98029029a7cf6cf2bf9387ec83162b9fb5d8a821798c` |
| `extensions/MythicMobsExtension/build/libs/MythicMobsExtension-0.9.0-jiujiu-folia26.2.1-all.jar` | `e92783dba99579dbcdf04ab80ca40da990a33ee2751102b364aa3e5e3ec22d6d` |
| `extensions/QuestExtension/build/libs/QuestExtension-0.9.0-jiujiu-folia26.2.1-all.jar` | `8a0641ee3d084f08db0c461a53fb0dc563ada0350f8fa4ffe008aec08b07c0e4` |
| `extensions/RoadNetworkExtension/build/libs/RoadNetworkExtension-0.9.0-jiujiu-folia26.2.1-all.jar` | `9c2eeb0541f0a7c47e9c3fd39b67f3dcef43b941a0ed5cad7f8aab499a222cb2` |
| `extensions/VaultExtension/build/libs/VaultExtension-0.9.0-jiujiu-folia26.2.1-all.jar` | `b37a308eddbee465ed9463be69cdcf619ed6494464d71b69ab88c85b3a7250a5` |
| `extensions/WorldGuardExtension/build/libs/WorldGuardExtension-0.9.0-jiujiu-folia26.2.1-all.jar` | `3265ec3256a45e9c023378eefeb12ddbd0027d3f1de81aa4d18826afb8049939` |
