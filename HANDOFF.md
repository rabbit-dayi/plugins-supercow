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
