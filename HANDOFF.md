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

## 8. 未发布：直接伤害/击退平衡 + 25个抽象技能（2026-10-20）

- 用户要求：以生产1.0.2为基准，攻击伤害与击退减少80%，稍增并随机化攻击间隔、增强暴击与技能频率，对最大生命值>400的Boss保守普攻但更常放技能；追加25个抽象技能。当前只在25566测试，未授权本轮生产部署/提交/Release；代码尚未提交，mod_version仍1.0.2，勿与正式1.0.2二进制混淆。
- 新增 combat/CombatBalance.java：只根据当前DamageSource的真实攻击实体/直接实体及其主人标记判定宠物攻击，绝不从受害者recordDamageOwner历史推断来源；玩家攻击直接排除。LivingEntityMixin.hurt入口只乘一次outgoingDamageMultiplier=.2，Boss再乘.75（普通为原20%、Boss为原15%），原生护甲/无敌帧仍照常结算。宠物、召唤物、带归属投射物和脚本直接伤害统一覆盖；两处无归属雷击改成带宠物来源伤害。
- 注意：旧技能施加的燃烧/毒/凋零后续DOT、后续摔落/环境伤害没有可靠攻击来源，未冒险用过期归属推断；所以不能声称所有技能造成的最终生命损失、所有环境伤害或总体DPS精确减少80%。本轮减伤保证针对可归属的直接攻击伤害。现有真实爆炸/环境副效应未全面重做。
- 击退：LivingEntityMixin重定向本次hurt内原生knockback并按当前来源缩放；近战额外push*.2，QuirkSkills/SpecialAttackManagers的受害者push和setVelocity走CombatBalance。setVelocity按old+(desired-old)*.2缩放新增速度变化，而非把已有速度硬归零；宠物/装饰实体自身轨迹不缩放。
- schema5默认：普通攻击18 tick ±4 tick，狂暴16 ±4；每次出手只采样一次，按实际2tick战斗调度向上取偶数，最低12tick。普通实际范围0.7–1.1秒，狂暴0.6–1.0秒；Boss间隔基数×1.35（普通约1.1–1.5秒）。nextAttackTick给GUI显示真实剩余冷却。近战暴击25%，倍率1.5；暴击后仍过统一减伤。
- 技能机会（达到技能CD后按攻击轮次判定，不是每tick）：普通24%、狂暴36%，CD80tick；Boss至少50%，CD60tick。Boss按最大生命值严格>400判断，不会掉血至400以下就丢失Boss策略。成功技能取代该轮普通攻击，失败回落普攻，不增加每次召唤实体数。
- 配置迁移只把旧默认值迁移为新默认，已有自定义值保持；schema2/3/4迁移、schema5幂等与自定义伤害/跟随/概率保留检查：/tmp/CombatBalanceMigrationCheck.java，运行结果BALANCE_MIGRATION_PASS。
- 新增 combat/AbsurdSkills.java：25套不同投影轨迹+推/拉/轻抬/减速/虚弱组合；全部用物品头盔投影、粒子和轻量脚本实现，不是真载具、真方块装置或新版本原生紫晶。每次最多3个隐形Marker盔甲架，锁装备、无碰撞、无重力、无敌、静音、supercow_quirk_actor标记及SkillCleanup TTL；每主人最多4任务、新引擎全局最多48任务（不代表旧引擎也有相同全局上限）。每任务最多80tick，3次伤害脉冲，每脉冲最多8个合法非玩家目标；无新增燃烧/中毒DOT。离线、跨维度、宠物失效、区块不可用或任务异常均回收。
- 技能总数92→117，5页27/27/27/27/9，全部有简介并自动加入动态命令和技能权重；QuirkSkills路由/tick/清理/activeTasks均接入新引擎。
- 新技能：史莱姆弹簧床 (slime_trampoline)、磁铁牛奶 (magnet_milk)、红石迪斯科 (redstone_disco)、纸飞机快递 (paper_airplane)、芝士陨石 (cheese_meteor)、雨伞大风车 (umbrella_spin)、发条胡萝卜 (clockwork_carrot)、发射器爆米花 (dispenser_popcorn)、橡皮鸭舰队 (rubber_duck_fleet)、豆腐切切机 (tofu_guillotine)、重力悠悠球 (gravity_yo_yo)、奶茶龙卷风 (milk_tea_tornado)、钟声节拍器 (bell_metronome)、南瓜保龄球 (pumpkin_bowling)、面包飞盘 (bread_frisbee)、钓一轮月亮 (fishing_moon)、书架多米诺 (bookshelf_domino)、土豆卫星 (potato_satellite)、地毯魔术秀 (carpet_magic_show)、西瓜投石机 (watermelon_catapult)、灯笼水母 (lantern_jellyfish)、漏斗吸尘器 (hopper_vacuum)、蛋糕传送带 (cake_conveyor)、紫晶交响乐 (amethyst_orchestra)、袜子黑洞 (sock_black_hole)。
- 完整构建 /tmp/sc-wave4-build2.log BUILD SUCCESSFUL（Java tests NO-SOURCE）；测试服 /tmp/sc-wave4-server.log启动Done3.105s，无新增Mixin启动异常。构建/测试jar SHA256 7d92947a2db326976c22cc75b39bc43a52a0966102a37bf8e884347af687928a；生产jar仍9d609fecd85ca40b92ca41f482c65d3837e9bc30a1fe747131674bccf0135509。
- /tmp/sc-wave4-smoke.log：WAVE4_SMOKE_PASS，25次均有实际伤害（200HP护甲husk剩199.4456–199.5569），117条JSON简介、五页与过期实体0通过；/tmp/sc-wave4-cap.log CAP_PASS四次成功/12实体，LOGOUT_CLEANUP_PASS。首次断线断言失败是临时脚本正则\s转义丢失，不是实测实体残留，修正后重测通过。
- /tmp/sc-wave4-balance.log BALANCE_SMOKE_PASS：基础10对400maxHP目标变2，401maxHP目标变1.5（当前血量掉至400以下仍保持Boss倍率），强制暴击3；普通间隔699–1000ms、Boss1099–1500ms。/tmp/sc-balance-guard3.log SOURCE_GUARD_PASS：相同静止目标首次水平速度包4160→831（约19.976%），垂直400→80；玩家自己空手攻击仍伤害1，未被减伤。早期控制脚本按旧协议读velocityX失败，修正为velocity.x后通过。
- /tmp/sc-wave4-ui.log UI_COMBAT_SMOKE_PASS：三连右键/双包去重、超时、Lore、头顶最近技能及恢复、五页117条、远程保持打开与关闭按钮通过；普攻夹杂箭效果的UI样本不用于纯攻击间隔统计。
- 可复现脚本：scripts/balance-smoke.js、scripts/balance-guard-smoke.js、scripts/balance-skill-smoke.js、scripts/wave4-smoke.js、scripts/wave4-cap-smoke.js；均仅用于隔离测试服，需SC_TEST_MUTATIONS=1、OP、既有宠物与平坦测试地板，会改库存/模式/竞技场/配置，须外部备份恢复。不要运行在生产世界。
- 技能频率完整构建重测 /tmp/sc-wave4-frequency.log BALANCE_SKILL_PASS：仅moo_wave可抽的隔离池，强制概率1时普通14秒3次（间隔4.5/4.8秒）、Boss14秒4次（约3.5–3.7秒）；默认概率样本普通30秒4次、Boss30秒6次，只是短期样本，不宣称证明长期概率或总体DPS。/tmp/sc-wave4-regressions2.log ALL_REGRESSIONS_PASS。
- 测试properties/config原备份 /tmp/sc-balance-original.properties、/tmp/sc-balance-original-config.json；回归后恢复原world/default，再加载测试构建并迁移schema5默认。恢复日志 /tmp/sc-wave4-restored-server.log，启动结果以该日志及后续核验为准。生产/GH仍未变更。
- 恢复核验完成：/tmp/sc-wave4-restored-server.log 10:59:47 Done(1.931s)，25566监听、PID1817238；level-name=world、level-type=default。原配置逐字段比较无非预期变化，仅schema5指定默认迁移与8个新平衡字段，临时baseDamage=10/critChance=1/禁用所有技能等均不残留。原自定义跟随距离、技能开关保留。回归与重启后台进程均已结束；生产artifact仍为正式1.0.2，未重启生产。

## 9. 1.0.3 正式发布（2026-10-20）
- 用户已授权生产部署与发布；版本号递增1.0.3。离线构建 /tmp/sc-103-build.log BUILD SUCCESSFUL；/tmp/sc-103-compare.py核验93个class与此前完整实测构建逐字节相同，仅版本元数据变化。正式JAR SHA256 6e9ef7c108efbf2b79f22a756a137c1fef3de2e011d37e7b342efbfbb20b5b73。
- 生产部署脚本 /tmp/sc-deploy-1.0.3.sh，日志 /tmp/sc-deploy-1.0.3.log；先公告、save-all flush、stop，等tmux16:0.0返回bash，再备份旧JAR与配置、替换并./start.sh。实际备份路径/启动结论见后续核验记录。
- Release计划tag1.0.3-1.16.5，保留旧标签；上传binary与sources，说明直接减伤与后续DOT/环境伤害的边界。正式发布结果以GitHub及后续核验为准，§8的未发布状态为本节前的历史。
- 生产核验完成：/opt/hf/final-paradox-1.16.5，PID1844642，25565监听，latest.log确认supercow1.0.3，11:18:15 Done(4.209s)。备份 /opt/hf/final-paradox-1.16.5/backups/supercow-pre-1.0.3-20261020-111747/ 含正式1.0.2 JAR与更新前配置，并保留1.0.3发布JAR。生产与发布构建SHA256相同。schema5按预期迁移，followDistance=3自定义保留；回滚应平滑stop，移出1.0.3 JAR，恢复备份1.0.2及原配置，再./start.sh，不能同时放两个supercow JAR。

## 10. 未发布：战斗周旋与等级同轮多技能（2026-10-20）

- 对应新需求：攻击后走位、提高技能概率、随等级同轮释放多个技能。仅测试服验证，生产/GH仍为上一节1.0.3，当前工作区不递增版本、不部署生产。
- schema6：普通技能概率.24→.40，狂暴.36→.55，Boss.50→.70；只精确迁移旧默认。schema5的其他自定义历史值不再重过schema5迁移。攻击间隔/随机范围、暴击、直接伤害*.2、额外击退*.2及Boss谨慎规则不变。概率是特殊冷却允许时按轮判定，并非每tick/每次必出；CD仍普通80/Boss60 tick。
- 新配置：combatEvasionEnabled=true、combatRetreatTicks=8、combatRetreatDistance=2.5、multiSkillEnabled=true、multiSkillLevelStep=5、multiSkillMaxCount=4、combatSkillEntityBudget=128。GUI44展示等级容量、概率和限制。
- CombatManager：成功攻击/技能轮后后斜撤步，冷却中近距离继续侧退，接近下次攻击时恢复接敌；短路径跨战斗tick复用。候选两侧斜退/侧移，检查失败则保留普通战斗，不强制穿墙/传送。目标变更、死亡、被动、召回、登出清理战术状态。审查发现的clearTarget和定期target迭代清理遗漏均已修正，战术路径用stopCombatStep独立停止，不误删主人跟随路径。
- PathfindingManager：tactical短程刹车带，不受原8格交战带限制、不叠加旧随机横漂。沿线每.4格检查完整碰撞箱、九点占地支撑/液体/火/岩浆块/营火/仙人掌/浆果/凋零玫瑰与已加载区块。战术及开启周旋的战斗禁旧随机搞笑跳跃，真正一格障碍仍可跳。不是全局寻路规划或无敌闪避，动态地形/敌方击退不能保证零受伤。
- 同轮容量min(clamp(maxCount,1,4),1+max(0,level)/max(1,levelStep))：Lv1/5/10/15→1/2/3/4。一次概率判定、一个共享特殊冷却；启用池不放回加权抽不同ID，失败技能不占成功槽，最多遍历技能总数。普通arrow从特殊池排除，仍保留为远程基础攻击。legacySkillsEnabled=false保留距离池，额外校验开关。飞天独立触发、原任务上限仍生效，显示的容量不是每轮无条件全出。
- SkillCleanup.track统一最终实体准入，所有登记的箭/召唤/延迟波次共享上限（配置限16..512）；已满则移除新实体，因此大批次也不突破预算。粒子与无需实体的即时效果不由实体数衡量，不能据此保证所有CPU负载上限。预算小/已满时可减少该轮实际技能实体，追踪前后的短暂构造成本仍存在。
- 构建 /tmp/sc-tactics-final-build.log BUILD SUCCESSFUL；Java测试NO-SOURCE。/tmp/TacticsMigrationCheck.java 检查schema2/3/4/5→6、定制概率/间隔/暴击不覆盖及幂等，TACTICS_MIGRATION_PASS。
- 集成：/tmp/sc-tactics-test5.log和/tmp/sc-tactics-final-test.log 四档同轮实体3/6/9/12（四种新技能各3投影），关闭multi为3，预算16时小组合12仍允许；活动实体最后归0。初轮测试配置只关闭已有json键、漏掉缺省启用技能，曾误观测其他随机技能，补齐全部117个键后重测通过。activity不是合法命令，清理统计必须/supercow skills。
- /tmp/sc-tactics-budget.log HARD_BUDGET_PASS16：cowRainCount40、预算16，连续两次手动技能均不超过16。
- 周旋最终必须看 /tmp/sc-tactics-true-movement.log：主人GUI38传送确认实际宠物，再加sc_owned_TacticMove短标签，PET_IDENTITY记录实体UUID。9秒12次伤害、距离2..7.224格、全程Y=201；目标死亡后250ms至850ms间水平移动0（TARGET_DEATH_STOP_PASS）。早期新测试身份重登后宠物重建丢临时tag，普通牛观测不能作为周旋结论；已用owner-only GUI校验解决。旧随机跳跃确实干扰过贴地断言，已禁用并复测。
- 可复现脚本 scripts/tactics-smoke.js / scripts/tactics-budget-smoke.js，均需SC_TEST_MUTATIONS=1和外部备份，固定25566；不得生产使用。技能脚本预设四个等级身份及TacticMove为OP，GUI38用于权威选择宠物。来源安全/原版减伤、Boss定义等前轮回归仍有效，未宣称所有117技能在同轮组合下穷举或玩家客户端FPS测试。

- 最终恢复完成：/tmp/sc-tactics-restore.log TACTICS_RESTORE_COMPLETE；/tmp/sc-tactics-restored-server.log 12:07:17 Done4.195s，PID1919169/25566。原world/default、原TestBot单宠物存档与原OP列表已恢复，测试身份数据已撤销；配置仅预期schema6迁移，follow20保留。工作区/测试JAR均SHA256 e8575112141c87419e98b3ea8bb88a31284707cb5fba343dce39ec723e70ff52；生产仍SHA256 6e9ef7c108efbf2b79f22a756a137c1fef3de2e011d37e7b342efbfbb20b5b73。

## 11. 生产部署 1.0.4（2026-10-20；不更新 Release）

- 用户本次授权部署生产与提交推送GitHub；消息尾部未完整，不据此创建Release或移动旧标签。§10“未发布”为历史状态，本节上线核验后覆盖。mod_version递增1.0.4，保留正式1.0.3 Release。
- 离线构建 /tmp/sc-104-build.log BUILD SUCCESSFUL（5秒，test NO-SOURCE）；/tmp/sc-104-compare.py 核验全部94个class与完整实测战术构建逐字节一致，唯一变化为fabric.mod.json版本。正式JAR SHA256 43422eaf417d8e8526070da05fadccfee312240f88e7e23cfeb451f8787906d2。
- 部署脚本 /tmp/sc-deploy-1.0.4.sh，日志 /tmp/sc-deploy-1.0.4.log：公告30秒、save-all flush、stop；仅原生产PID退出且tmux16:0.0返回bash后备份并替换。备份包含旧1.0.3、新1.0.4、原supercow-config.json与supercow-pets.json。保留followDistance=3等自定义，仅精确迁移旧默认概率和schema6；不向生产注入测试宠物/配置。
- 生产核验完成：2026-10-20 14:21:08，latest.log确认supercow1.0.4、初始化成功、Done(3.861s)；PID2116033，25565监听。构建与生产JAR SHA256一致。无新插件异常，只有已有advancement资源报错。
- 备份路径 /opt/hf/final-paradox-1.16.5/backups/supercow-pre-1.0.4-20261020-142019/。配置逐键核对仅三项默认概率、configVersion5→6及七项新增战术/预算配置变化；followDistance=3、伤害与间隔等自定义项均保持。
- Git提交范围：五个战斗/寻路/配置/GUI Java文件、版本号、两份测试脚本及本交接文档，推送fabric-1.16.5；不创建tag/Release。仓库origin只跟踪main，核对该分支须git fetch origin fabric-1.16.5后比较FETCH_HEAD，推送须显式HEAD:refs/heads/fabric-1.16.5。
- 回滚：平滑stop并等Java退出，移出mods内1.0.4，恢复该备份1.0.3 JAR和supercow-config.json，再./start.sh；不要同时保留两个supercow JAR。宠物数据已备份，但不要无故覆盖更新后的正常宠物进度。
