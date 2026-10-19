# 交接文档：SuperCow 1.16.5 Fabric 移植（截至 2026-10-19）

本文件记录当前版本的状态、已验证项、配置与构建部署方式。长期设计说明另见同目录 `FABRIC-1.16.5.md`。

---

## 0. 一句话现状

本次第三弹开发：全部 **92 个技能**均有专属趣味小介绍，GUI 悬浮说明、技能列表与强制释放反馈同步展示；新增 **30 个抽象机关技能**（91 个参与抽卡，转圈飞天不抽卡），GUI 共 4 页。本轮发布版本为 **1.0.1**，生产服已平滑重启并加载新版本；Git 分支为 fabric-1.16.5，Release 标签为 1.0.1-1.16.5。下方原有记录保留为历史验证背景。

本轮五项需求（**跟随范围默认改远至 20 格并提供远/中/近档位 + 平滑刹车防抖**、**启动与 Tick 性能调优**、**新增 40 个技能**、**新增「活塞草羊机」技能一下子把敌人顶飞**、**面板多页翻页与命令树动态注册**）已全部实现、清除全部调试插桩、编译通过，并在测试服完成跟随距离与全 62 个技能实机验证。

---

## 1. 仓库与构建

- 仓库：`/root/dayi/ovo/hi-cow/plugins-supercow`（分支 `fabric-1.16.5`）
- 构建命令：
  ```bash
  cd /root/dayi/ovo/hi-cow/plugins-supercow && ./gradlew build --offline
  ```
- 产物：`build/libs/supercow-fabric-1.16.5-1.0.0.jar`

---

## 2. 本轮核心改动摘要

1. **跟随系统（默认远 = 20.0 格，彻底解决贴身吵闹、倒走震荡与冲到跟前才停）**
   - `SuperCowConfig`：`followDistance` 默认 **20.0**（`followStartBuffer = 4.0`，即超出 24 格起步；`followBrakeBand = 6.0`；`teleportDistance = 32.0`）；`repairFollowDefaults()` 自动将旧版 4.0 / 10.0 配置升级到 20.0，并将旧 24.0 瞬移距离升级到 32.0（留出 24~32 格步行追赶窗口）。
   - `CombatManager`：调用 4 参 `moveToEntity(pet, owner, 1.0D, true)` 传入 `followOwner = true`；新增 `maintainPassiveFollow()` 与 `rescueStuckPet()`（卡墙 100 tick 自动脱困拉回并 `groundBelow` 吸附地面）。
   - `PathfindingManager`：
     - 主牛跟随时启用专属 `followBands`（`arrive = 17.0, optimal = 20.0, chaseCap = 25.0`），禁用战斗 `strafing` 横移抖动；
     - 修复 `tickLegacyMover` 二次缓动公式在 `dist > optimal` 时 `t > 2` 导致 `t*(2-t) < 0` 速度变负倒走震荡的数学 Bug（现以 `chaseCap - arrive` 归一化并 `Mth.clamp(t, 0, 1)`）；
     - 修复 `hasLineOfSightFrom` 终点高度（眼对眼 `to.y + 1.0`，不再射向脚底草地）。
   - 命令与 GUI：支持 `/supercow follow [2-24]`，GUI 第 7 格左键 +2 / 右键 -2 / Shift 左键 20 格 / Shift 右键 6 格；`configVersion = 2` 保证旧配置一次性迁移后不再覆盖用户设置的 4/10 格。

2. **启动与运行时性能调优**
   - `SuperCowMod`：`onInitializeServer` 仅注册回调与命令树；配置与 `pets.json` 推迟到 `SERVER_STARTED` 加载；`placeAtFreeSpot` 修复碰撞判断反号 Bug 并增加 `groundBelow` 地面吸附；`recallPet` 同步设置 `data.isAlive = false` 防止下一 tick 被自动重召。
   - `Skills` / `QuirkSkills`：技能列表与分发表按需懒加载；`CombatManager.rollSkill` 改用预分配静态数组（容量 96）零分配抽取。
   - `CombatManager`：默认每 2 tick（`combatTickInterval = 2`）执行一次宠物战斗扫描，无目标时复用短缓存。

3. **技能擴充（共 62 个技能：原版 10 + 第一弹 10 + 转圈飞天 1 + 第二弹 41）**
   - 新增第二弹 40 个技能（位移 8、召唤 7、控制 7、爆发 6、增益 6、趣味 6）+ 用户追加的 **`piston_sheep_machine`（活塞草羊机：生成实体活塞草羊机，往复推刺一下子把敌人顶飞上天）**。
   - 所有衍生实体均通过 `SkillCleanup.track` 托管生命周期。

4. **GUI 多页支持与命令动态注册**
   - `PetGui`：支持 27 格/页多页浏览，第 41/42 格为上一页/下一页箭头，支持 `/supercow gui [页码]` 与 `/supercow skills [页码]`。
   - `SuperCowMod`：`/supercow skill <id>` 动态挂载 `Skills.all()` 全部技能 ID 与别名。

---

## 3. 实机验证记录（测试服 25566）

- **跟随距离实测**：玩家位于 `(296.5, 78, -5.5)`，宠物牛位于 `(296.5, 78, -33.0)`（初始距离 27.5 格 > 起步阈值 24.0 格）。宠物牛以 `0.42 -> 0.409 -> 0.366 -> 0.303 -> 0.0` 平滑减速走近，最终稳稳停在 `z = -25.16`（距玩家 **19.66 格**，紧贴 `followDistance = 20.0`），无抖腿、无倒走、无冲到贴身。
- **全技能冒烟实测**：62 个技能通过 `/supercow skill <id>` 逐一触发（含 `piston_sheep_machine`），服务端日志零异常。
- **调试插桩清理**：`DEBUG_TICK`、`DEBUG_FOLLOW`、`SCTRACE`、`SCFOLLOW` 等临时日志与调试子命令已全部移除。


## 4. 第三弹：小介绍与 30 个抽象机关技能

- 注册元数据在 Skills.java；新增铁砧断头台、彩虹羊大炮、烫手山芋、钓竿抓娃娃机、史莱姆弹球机、村民拍卖会、末地烛钻头机、加特林坤坤机、秋名山飞船、苦力怕虚晃一枪、河豚刺雷阵、蹦床充气堡、鱿鱼喷墨打印机、铁傀儡正骨师、羊驼口水加农炮、熊猫保龄球、猫猫踩键盘、矿车过山车、绿龟壳弹射、狐狸偷菜夹、雪人破壁料理机、飞天猪猪外卖、女巫孟婆汤、骷髅爵士乐队、末影人搬家公司、北极熊大逼兜、蠹虫挠痒痒机、海豚冲浪板、哞菇浓汤高压锅、蝙蝠外星飞碟。
- QuirkSkills.contraption 统一生命周期，各技能独立轨迹、节拍、粒子与击退/状态效果；每次最多 4 个装饰实体，任务总量上限 96，新技能运行 50–80 tick，三连击在 16/32/48 tick，最低时长不得低于 49 tick。
- 铁砧、床、钓竿、末地烛与载具使用盔甲架物品投影；不放置真实方块、不生成可乘坐载具、不调用真实爆炸或传送敌人进入方块。
- 装饰实体带 supercow_quirk_actor，NoAI/noGravity/noPhysics，SkillCleanup.track 托管，结束立即移除并有 TTL/孤儿扫描兜底；目标过滤排除装饰实体，新技能额外拒绝玩家受害者。
- onLivingEntityHurt 阻止装饰实体原生接触伤害；交互包先转服务端线程，再阻止挤奶/剪毛/交易/骑乘等交互。SnowGolemMixin 专门阻止装饰雪人原生铺雪（仅 NoAI 不够）。
- 冒烟脚本 scripts/wave3-smoke.js：需 mineflayer@4、测试服 OP 用户已有宠物；NODE_PATH=/tmp/bot/node_modules node scripts/wave3-smoke.js。默认端口 25566、用户名 TestBot，可用 SC_TEST_PORT / SC_TEST_USER 覆盖；只允许在测试服运行。
- 首次实机验证：4 页 GUI 共 92 个技能的介绍均存在，30 个新增技能逐一释放成功，结束后活动技能实体 0；服务端无技能异常。Gradle build --offline 成功（项目暂无 Java 单元测试，test NO-SOURCE）。

### 最终构建验证

- 最终测试服 JAR SHA-256：819288dff9c846e1700e7b7a15b2736360decead575340fbfd85286142383c1b，与 build/libs 产物一致；仅测试服 25566 更新。
- 最终服务端启动 Done (2.430s)，日志 /tmp/sc-wave3-final-server.log；最终全量冒烟 /tmp/sc-wave3-final-smoke.log 记录 SMOKE_PASS：92 技能、30 次新增技能逐一释放、4 页 GUI，结束活动实体 0。
- 附加回归：装饰小牛交互/攻击不产奶、不被取物，过期自动清理，服务端无 packet-handler 异步异常；测试前需清除角色旧 milk_bucket 库存，避免基线误报。
- 时长边界回归：quirkSkillDurationTicks=20、48、80 时，铁砧三连击均将无甲 200 血尸壳降至 194；通过同步实体 health metadata 验证（测试服 sendCommandFeedback=false，/data 命令成功结果不显示）。已恢复测试服配置与内存时长为 80。
- git diff --check 与 node --check scripts/wave3-smoke.js 通过。尚未 commit/push、未覆盖旧 Release、未改生产服。

## 5. 1.0.1 生产发布（2026-10-19）

- gradle.properties 的 mod_version 更新至 1.0.1，离线 build 成功；全部 89 个 class 与实机验证过的第三弹产物逐字节一致，仅版本资源变化。
- 生产服 /opt/hf/final-paradox-1.16.5 已通过 tmux 16:0.0 stop 平滑关服、备份旧包、替换并 ./start.sh 重启。仅保留 supercow-fabric-1.16.5-1.0.1.jar，避免重复 mod ID。
- 旧 JAR 与配置备份：/opt/hf/final-paradox-1.16.5/backups/supercow-pre-1.0.1-20261019/。
- 生产新包 SHA-256：b3cbc2173ac4fc2d73eef040860fcf9441e0aba9f4c6fb00357d9c74ee9c2676，与 build/libs 产物一致。
- 发布采用新补丁标签 1.0.1-1.16.5，保留旧 1.0.0-1.16.5 标签与下载包；安装时只放正式 JAR，不装 sources.jar。
- /root/deploy_sc.sh 的旧辅助脚本仍硬编码 1.0.0 文件名，不要直接用于新版本；本轮部署脚本 /tmp/sc-prod-1.0.1-deploy.sh 是一次性操作，不可重复运行。

## 6. 未发布工作区：寻路修复与战斗/GUI体验调整（2026-10-19）

- 本节覆盖当前未提交代码；生产服和 GitHub Release 仍为原 1.0.1，未部署生产、未 push，版本号暂未递增。测试服 25566 加载本工作区构建。
- 寻路：保留前轮绕墙方向缓存、沿墙侧向探测、非战斗分支不覆盖绕障方向、高墙不盲目跳跃、水平位移与无接近进度脱困；失联宠物查找跳过离线主人并限频20 tick。复测日志 /tmp/sc-nav-retest2-client.log：直线3/3、长墙3/3、封闭救援约4.8s。未对任意迷宫/复杂地形作全局保证。
- 普攻：默认普通冷却40→24 tick（2→1.2秒），狂暴25→16 tick（1.25→0.8秒）；基础伤害3→2、每级成长0.3→0.2、普通箭2.5→1.75、狂暴箭3.5→2.5。仅普攻/普通箭调低，其他技能专属伤害未整体缩放。特殊技能CD仍120 tick、实体数量不变，触发概率0.15/0.25→0.09/0.16，防止普攻加速顺带放大实体负载。最低攻击间隔11 tick，不重置原版无敌帧；近战命中窗口未到则不消耗整轮冷却。
- 配置schema3：repairCombatDefaults仅匹配旧默认后迁移，不覆盖其他自定义值；repairFollowDefaults仅版本<2时迁移，保留v2用户主动设为4/10的跟随距离。
- 面板入口：主手对自己的活牛在24 tick内连点三次，单次间隔不得超过12 tick；同tick重复包、INTERACT_AT与副手不叠加计数；换牛、喂食、潜行、超时重置。距离检查6格内；命令 /supercow gui 仍可直接打开。干草喂食保留。此处按三次右键实现，而非六次点击。
- GUI：Lore显示双方距离、维度、坐标、实时血量/经验/击杀/模式、目标详情、狂暴、实际普攻剩余冷却与配置间隔；每20 tick仅同步变化槽。新增刷新8、目标43、冷却44、最近操作49，保留4页技能与原交互；GUI操作反馈只存Lore，不再写ActionBar。PREDICTED_CARRIED同步机制保留。
- 头顶：成功释放统一入口executeNamedAttack通知，显示“超级牛 Lv.N | 技能名”，最近释放短显40 tick，过期恢复；飞天实际活动期间优先显示飞天。只在文字改变时更新实体metadata，非每tick强推。仅显示最新一次释放，不代表所有召唤物/持续特效都在2秒内结束。
- API（CombatManager，服务器线程，UUID为主人）：getCurrentSkillId、getCurrentSkillRemainingTicks、getAttackCooldownRemainingTicks、notifySkillTriggered。
- 验证：/tmp/sc-ui-final-client3.log 记录 UI_COMBAT_SMOKE_PASS：单次/两次不打开，第三次打开（含双包/副手去重）、超时重置、距离Lore自动更新、操作反馈在Lore、铁砧头顶名与自动恢复。实际8次命中，间隔1199/1201/1249/1151/1199/1201/1199ms，中位1199ms。早期用箭实体数计频失败是牛近身转为近战，随后改用受害者health metadata；第二次采样受铁砧击飞干扰，最终等特效结束并重置站位后通过。
- 最终构建 /tmp/sc-ui-release-build.log BUILD SUCCESSFUL，Java测试NO-SOURCE；两个子任务编译/配置迁移检查通过。脚本 scripts/ui-combat-smoke.js（需 SC_TEST_MUTATIONS=1、mineflayer、OP宠物和平坦y200地板），会修改测试角色库存、模式与配置，务必先备份，禁止生产服。
- 测试原properties/config备份 /tmp/sc-ui-original.properties、/tmp/sc-ui-original-config.json，已恢复原world/default并重启；schema3旧默认自动迁移属于预期，测试中全关技能与临时跟随距离不应残留。测试最终启动日志 /tmp/sc-ui-restored-server.log。

- 补充回归限制：恢复原测试世界后尝试只读检查四页技能（/tmp/sc-ui-pages2.log），/supercow call 提示宠物已在身边，但 GUI 在等待窗口内未保持打开；伴随原世界区块加载警告与反复脱困提示。本轮不将这次分页检查计为通过，未据此修改逻辑。三次点击、Lore实时更新、头顶与攻速证据来自隔离平坦世界的最终完整通过测试。

## 7. 1.0.2 发布候选（2026-10-19）

- 本节覆盖§6旧参数：schema4，普通14tick/0.7秒、狂暴12tick/0.6秒（战斗每2tick运行），基础伤害1.2/每级0.12，箭1.0/1.5；特殊概率0.055/0.12维持实体负载，独立技能CD仍120。近战额外击退0.25/0.15→0.12/0.05，减少追停。保留无敌帧。
- 真实客户端缺陷修复：Lore每行使用Component.Serializer.toJson；原来的字符串检查不能证明原版客户端能显示，现测试用prismarine-nbt提取并JSON.parse所有Lore。面板远距不再因24格限制立刻关闭（仍验证主人UUID与宠物存活）；分页命令优先setPage；关闭用明确tick标记，非server.execute同步执行；仅普通/shift左右键触发操作；全服共享配置按钮限制权限2。
- 三次右键是服务端三次有效主手交互。原版不提供按键松开事件，长按自动重复也可能达到三次，不能承诺区分三次物理按下。
- 双只读审查均结束；最终构建 /tmp/sc-102-final-build.log 成功，迁移Java断言MIGRATION_PASS（schema2/3旧默认、custom保留、幂等）。
- /tmp/sc-102-smoke.log：完整UI_COMBAT_SMOKE_PASS，全部92介绍/4页JSON Lore、远距面板保持、延迟关闭、三连防误触/超时/副手去重、实时Lore、头顶提示与恢复。普通实际14次命中，间隔698~701ms，中位700ms。测试与最终构建SHA256相同9d609fecd85ca40b92ca41f482c65d3837e9bc30a1fe747131674bccf0135509。未声称所有技能伤害统一降低或多人高负载覆盖。
- 测试环境已恢复原properties与config（/tmp/sc-102-test.properties、/tmp/sc-102-test-config.json）；/tmp/sc-102-test-restored.log。生产部署日志 /tmp/sc-deploy-1.0.2.log，平滑save-all/stop、先备份JAR与配置，启动后还需确认日志/端口。

### 1.0.2 正式部署验证
- 正式服已平滑更新并启动：/opt/hf/final-paradox-1.16.5，tmux16:0.0，生产PID372073，25565监听，18:37:31 Done(2.331s)，加载supercow1.0.2。备份 /opt/hf/final-paradox-1.16.5/backups/supercow-pre-1.0.2-20261019-183711/ 含旧1.0.1 JAR和原配置；新JAR与测试一致SHA256 9d609fecd85ca40b92ca41f482c65d3837e9bc30a1fe747131674bccf0135509。schema4迁移旧默认，保留生产自选followDistance=3。
- 原测试世界补测四页已通过 /tmp/sc-102-default-pages.log GUI_PAGES_PASS（27/27/27/11），覆盖§6中此前未通过的额外分页项。原世界仍有区块/窒息等独立异常，不将其宣称为存档修复。
- 发布tag 1.0.2-1.16.5，GitHub Release附二进制与sources；发布结果以GitHub及 /tmp/sc-publish-1.0.2.log 为准。回滚：平滑stop后将当前1.0.2 JAR移出mods，恢复备份1.0.1 JAR及config，再./start.sh；不要让两个supercow JAR同时留在mods。
