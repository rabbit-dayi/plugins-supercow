# 超级牛 · Fabric 1.16.5 移植说明

本分支（`fabric-1.16.5`）把原先基于 Spigot / Bukkit 的 Kotlin 插件 `mycow2` 全量重写为
**Fabric 1.16.5** 模组，源码位于 `src/main/java/net/dabbit/supercow/`，Maven 的 Kotlin 版本仍在
`src/main/kotlin/` 保留（1.19.4 原版构建不受影响）。

## 构建

```bash
./gradlew build --offline     # 产物：build/libs/supercow-fabric-1.16.5-1.0.0.jar
```

- Minecraft 1.16.5 + fabric-loader 0.19.3 + fabric-api 0.42.0+1.16
- Mojang 官方映射（`officialMojangMappings()`），Java 8 目标字节码
- 开发环境沿用 `net.fabricmc.fabric-loom-remap`，Gradle 9.5.1

## 目录结构

| 路径 | 作用 |
| --- | --- |
| `SuperCowMod.java` | 模组入口：事件注册、命令、tick 主循环、持久化 |
| `config/SuperCowConfig.java` | 全部可调数值（写盘为 `config/supercow-config.json`） |
| `data/PetData.java` | 单只宠物的等级/经验/血量/模式，存 `config/supercow-pets.json` |
| `combat/CombatManager.java` | 目标选择、近战/远程、怒气、模式切换 |
| `combat/TargetValidator.java` | 合法目标判定（怪物、玩家、友伤、已死实体） |
| `combat/SpecialAttackManagers.java` | 箭雨/火球/药水/烟花/鸡轰炸/音波/爆炸牛 |
| `combat/SummonManager.java` | 召唤小牛、干草捆消耗、召唤物 AI |
| `combat/CustomProjectiles.java` | 自定义箭/蛋/烟花/火球实体与命中效果 |
| `pathfinding/PathfindingManager.java` | 追击移动、卡位处理、朝向 |
| `mixin/LivingEntityMixin.java` | 拦截 `hurt` / `die`，做友伤过滤与击杀归属 |

## 移植时修掉的原插件 BUG

1. **爆炸牛技能调错方法**：`CombatManager.kt:647` 的 `exploding_cow` 分支调用的是
   `projectileManager.shootPotion(...)`，导致该技能实际丢药水。移植版调用真正的爆炸牛逻辑。
2. **鸡轰炸永远不生效**：`ChickenBombardmentManager.kt:192-221` 的 `handleEggImpact` 从未注册到
   任何 `@EventHandler`，且内部 `if (entity.type != EntityType.PLAYER) return` 只打玩家（含主人）。
   移植版在 `CustomProjectiles` 里注册蛋命中，并对敌对生物生效。
3. **音波攻击把自己人打死**：`MusicAttackManager.kt:106` 是 `if (entity != target) applyMusicEffects`，
   等于打所有人除了目标；`185` 行的速度向量是自己减自己，得到 0 向量后 `normalize()` 出 NaN。
   移植版改为只影响目标、并按合法目标过滤，击退向量改为从牛指向目标。
4. **召唤物刷屏**：`SummonManager.kt:118-125` 的 `consumeHayBale` 第一行就是 `return true`（消耗逻辑
   是死代码）；`Config.ATTACK_COOLDOWN = 1L` 让 10 只召唤物每秒射出 200 支箭。移植版恢复干草消耗
   （`consumeHayBaleForSummon`），召唤物射击冷却改为 40 tick。
5. **宠物死亡后战斗状态不清理**：`SuperCow.kt:282-283` 先把主人从 `activePets` 移除再调
   `combatManager.onPetDeath(ownerName)`，后者第一行 `?: return` 必然提前返回，发光、目标、
   召唤物全部残留。移植版先清理再移除。`recallPet` 也补上了 `onPetRecall`。
6. **经验溢出计算错误**：`SuperCow.kt:801-808` 把 `expNeeded` 算在 `while` 外面，升级后不再重算。
   移植版在循环内重算，支持一次升多级。
7. **击杀归属丢失**：`SuperCow.kt:1047-1051` 对 `Projectile` 的 shooter 只看 `Cow`，导致箭矢/召唤物
   击杀不给经验。移植版用 `DAMAGE_OWNER_BY_VICTIM` + 实体 tag 做归属。
8. **异步线程碰世界数据**：`PathfindingManager.kt:85/104/197/344` 在异步任务里读方块、做
   `rayTraceBlocks`；`stopPathfinding` 先 `cleanup` 再 `cancel`，任务从未被取消；卡位时对零向量
   `normalize()`。移植版全部在主线程、按 UUID 正确取消，零向量有长度判断。
9. **爆炸无差别破坏地形**：原版投射物爆炸 `breakBlocks = true`、`setFire = true`；爆炸牛
   `MAX_LIFETIME_TICKS = 5`（0.25 秒后就在宠物脚下炸）。移植版爆炸不破坏方块、不起火，引信 30 tick、
   最长存活 60 tick。

10. **宠物实体是"幽灵"**：宠物在玩家入服/复活/召回时生成，`ServerLevel.addFreshEntity()` 在
   目标区块尚未加载完成时返回 `false`，旧代码没有检查返回值，于是宠物牛只是一个游离对象：
   打怪、放技能都正常，但怪物看不到它、`@e` 选不中它、`/kill` 也杀不掉。移植版先强制加载区块、
   校验返回值，并在每 tick 检查"宠物是否真的在世界实体表里"，掉了就重新生成。
11. **宠物卡在方块里 / 不会移动**：出生点按"主人 +1.5 格"硬编码，遇到台阶、半砖平台时牛会被埋进方块；
   而且 1.16.5 的 `noAI` 实体不会执行 `travel()`，写进速度向量的值根本不会被应用（既不走也不掉）。
   移植版会先在主人周围九宫格里找一个不与方块相交的落点，并由"抽象移动器"自己调用 `move()`
   实现位移和重力/跳跃。

## 与原插件一致：技能种类、抽象移动、"抽象"BUG

原插件的技能表与权重被完整保留（权重 = 相对概率）：

| 技能 | 权重（普通/狂暴） | 说明 |
| --- | --- | --- |
| `arrow` 箭矢 | 950 / 900 | 主输出，命中后有 35% 附加箭矢效果（冰冻/闪电/爆炸/漩涡/箭雨/弹射） |
| `firework` 烟花 | 15 / 30 | 朝目标上方发射烟花，范围伤害 |
| `potion` 药水 | 15 / 30 | 投掷药水，落点范围伤害 + 负面效果 |
| `fireball` 火球 | 5 / 15 | 小火球，命中点燃（不破坏方块） |
| `exploding_cow` 自爆牛 | 5 / 10 | 发射一只会爆炸的牛，引信 30 tick |
| `chicken` 轰炸鸡 | 3 / 5 | 头顶刷 2~3 只鸡，边飞边下蛋 |
| `music` 音乐 | 3 / 5 | 音波范围伤害 + 减速/虚弱 |
| `summon` 护卫小牛 | 2 / 2 | 召唤 2~3 只小牛协战 20 秒 |
| `summon_parrots` 鹦鹉 | 1 / 2 | 环绕啄击的鹦鹉 |
| `summon_rabbits` 兔子 | 1 / 1 | 冲向目标啃咬的兔子 |

技能**不再按距离分档**（原插件就是一张权重表随机抽），近战距离也可能甩出自爆牛、远处也可能开音乐会。

**被保留成"技能"的原插件怪癖**（都可用配置关掉）：

- `explodingCowPotionBugChance`：原版 `exploding_cow` 分支错调 `shootPotion()` 的 BUG，现在
  有 20% 概率真的丢出药水（玩笑式的"技能"）。
- `arrowHealAmount`：原插件箭矢配置里的 `HEAL_AMOUNT = 2.5`，每轮箭雨后宠物自愈。
- `musicInvertedTargeting`：原版音波"打到所有人，除了目标"，保留为只对非目标上 debuff。
- `musicZeroKnockback`：原版 `entity.location - entity.location` 的零向量击退（NaN），保留为"不击退"。
- `eggQuirkPlayersOnly`：原版鸡蛋只打玩家（含主人）；BUG 版复刻这一条，修复版打所有非友方（不误伤主人）。
- `summon` 自动释放不消耗干草块（原版 `consumeHayBale` 首行 `return true` 的死代码），
  只有玩家手动 `/supercow attack summon` 才扣干草块与冷却。

**抽象寻路**（`legacyMovementEnabled`）：宠物不再走原版 `PathNavigation`，而是每 tick 重算一个
速度向量：距离分档给基础速度、视线被挡时按 45° 试探 8 个方向、以 +10°/tick（10% 概率 ±45°）的
漂移角度永远侧移（所以它会绕圈、贴墙、走 Z 字）、前方有方块就跳、还有 3% 概率的无意义小跳。
移动器自己调用 `move()` 并维护重力，因此 noAI 的牛也能正常走路、上台阶、掉落。

**`sky_spiral` 转圈飞天**（原插件"能把自己寻路到天上"的 BUG，保留为技能）：
锁定一个新目标的瞬间按 `skySpiralChance`（默认 3%）掷一次骰，中招后宠物会一边绕圈一边往天上爬，
持续 `skySpiralDurationTicks`（50 tick）或爬到 `skySpiralMaxHeight`（10 格）为止，然后自动掉回地面
（不摔伤、不会飘在空中不落）。触发时主人会收到 `§d[超级牛] 糟了，它的寻路把自己送上天了……`。
冷却 `skySpiralCooldownTicks`（900 tick），同一个目标只掷一次骰。强制演示：`/supercow skill sky_spiral`。

**技能清理保证**（用户要求：不能有"技能放完还留在世界里的东西"）：

- 所有技能生成的实体都会被 `SkillCleanup` 登记（tag `supercow_skill` + `supercow_skill:<技能>`），
  到期自动移除：箭矢 200 tick、鸡蛋 200 tick、轰炸鸡 80+60 tick、自爆牛 60+40 tick、召唤物 400 tick、
  鹦鹉/兔子 300 tick。
- 技能自己结束时（轰炸结束、自爆牛引爆、音波结束）会主动 `untrack` 并清理。
- 宠物死亡、被召回、主人下线时，`SkillCleanup.cleanupForOwner` / `MinionManager.cleanupForOwner`
  一次性清空该主人的全部残留。
- 兜底：每 40 tick 对每个维度做一次 `SkillCleanup.sweep()`，把"带 tag 但登记表里没有"的实体删掉。

## 技能风格：修复版 / 混合 / 原版BUG版（`quirkMode`）

这次更新之前的逻辑（所有技能都按"修好"的方式生效）被完整保留，只是不再是唯一选项。
每次释放技能时，`combat/Quirks.roll()` 决定这一发走"修复版"还是"原版BUG版"：

| 模式 | 行为 |
| --- | --- |
| `safe` | 全部技能使用修复版（= 上一次更新的手感，稳定、不误伤、召唤扣干草） |
| `mixed`（默认） | 每次释放按 `quirkChaosChance`（默认 50%）掷骰，两版都会出现 |
| `chaos` | 全部技能使用原版 BUG 版（特色拉满） |

切换命令（权限 2，写入 `config/supercow-config.json`）：

```
/supercow quirk            # 查看当前风格
/supercow quirk safe       # 全修复版
/supercow quirk mixed      # 混合（默认 50%）
/supercow quirk chaos      # 全 BUG 版
/supercow quirk off        # 等价于 safe（legacyQuirksEnabled=false）
```

受影响的每个技能（都是"发"级别的掷骰，同一发技能内部行为一致）：

- **箭矢**：掷中 BUG 版时，一轮箭雨之后宠物自愈 `arrowHealAmount`。
- **自爆牛**：掷中 BUG 版时，有 `explodingCowPotionBugChance` 的概率丢成药水（原版调错方法的复刻）。
- **音波**：掷中 BUG 版时，debuff 只给"非主目标"（`musicInvertedTargeting`），并且不击退（`musicZeroKnockback`）。
- **轰炸鸡**：掷中 BUG 版时，鸡蛋只伤害玩家（`eggQuirkPlayersOnly`），对怪物无效；主人依然被仇恨保护筛掉，
  所以实际只会误伤旁边的其他玩家——`/supercow quirk chaos` 时别站在队友旁边。
- **召唤**：掷中 BUG 版时，技能触发的召唤不消耗干草块、不走冷却（原版 `consumeHayBale` 首行 `return true`）。
  手动 `/supercow attack summon` 无论什么模式都照常扣干草块。

注意 `sky_spiral`（转圈飞天）不受"每次释放掷骰"影响，它用的是同一套 `Quirks.roll()`，
但掷骰时机是"锁定新目标"、每个目标只掷一次：`safe` 永不触发，`mixed` 50% × 3%，
`chaos` 3%。想调概率就改 `skySpiralChance` / `skySpiralCooldownTicks`。

`/supercow skills` 会显示当前风格，例如 `风格：mixed 50%`，以及转圈飞天的当前触发概率。

## 宠物牛面板（箱子 GUI）

对自己存活的宠物牛**右键**即可打开一个 54 格箱子面板（标题 `§6§l超级牛 · 面板`）。
拿着干草块右键仍然是喂食、潜行右键仍然是文字信息，不会被面板抢走。
如果在面板里改了东西，关闭面板时统一写盘（`removed(Player)` 里保存），不会每点一下就刷磁盘。

| 槽位 | 物品 | 作用 |
| --- | --- | --- |
| 0 | 生牛刷怪蛋 | 名字 = `§6§l超级牛 §eLv.N`，lore 显示生命 / 经验 / 击杀 / 模式 |
| 1 | 附魔之瓶 | 技能总开关数量（已启用 N/M） |
| 2 | 金苹果 | 当前生命 / 最大生命 |
| 3 | 书 | 经验进度（`x/y`，下一级所需） |
| 4 | 盾牌 | 战斗模式（保护 / 主动 / 防御 / 被动），点击循环 |
| 5 | 小麦 | 召唤模式（跟随 / 主动 / 防御 / 守卫），点击循环 |
| 6 | 酿造台 | 技能风格（safe / mixed / chaos），点击循环并启用怪癖系统 |
| 9~29 | 染料 | 21 个技能各一格：`§a✔` 开 / `§7✘` 关，点击切换 |
| 36 | 金胡萝卜 | 治疗宠物到满血 |
| 37 | 拴绳 | 召回（关闭面板 + `recallPet`） |
| 38 | 末影之眼 | 把宠物传送到主人身边 |
| 39 / 40 | 黄绿 / 灰色染料 | 一键全开 / 全关 |
| 53 | 屏障 | 关闭面板 |
| 其余 | 灰色玻璃板 | 占位 |

实现要点（都是踩过的坑）：

- **每次点击都有反馈**：面板里每个能点的格子点了都会在动作栏（action bar）给出结果——信息格显示格名、
  模式格显示切换后的模式、技能格显示"技能 X 开启/关闭"、治疗/传送/全开全关也都有提示，不会出现
  "点了没反应"的错觉。
- **光标物品同步**：重推面板后还会补发 `ClientboundContainerSetSlotPacket(-1, -1, carried)`
  （槽位 -1 = 光标），避免客户端在光标上留下一个"鬼影"物品。
- **渲染期间不打扰客户端**：`refresh()` 重建 54 格时不逐格触发监听，重建完统一推一次，图标不会闪。
- **同步包**：面板是 `SimpleContainer` + 自定义 `ChestMenu`，每次点击后自己构造
  `ClientboundContainerSetContentPacket`（90 = 54 容器格 + 玩家背包 27 + 快捷栏 9）重推一遍，
  客户端不会出现"点了没反应"的鬼影物品。
- **点击必须重新解锁同步**：1.16.5 的 `ServerGamePacketListenerImpl.handleContainerClick` 在
  `ItemStack.matches` 失败时会 `setSynched(player, false)`，之后 `handleContainerClick` 直接 return。
  所以 `clicked()` 末尾一定要 `setSynched(sp, true)`，否则面板只接受**一次**点击就彻底失灵。
- **面板只吃自己的格子**：`slotId in [0,54) && sp.containerMenu == this` 才处理，玩家背包半区
  的点击原样放过（不会吞物品、不会复制）。
- **打开方式走自己的 mixin**：本服的 fabric-api 0.42 里 `UseEntityCallback` 不触发
  （`MixinServerPlayNetworkHandler` 在这台服务器上是哑的，实测右键牛奶桶能挤奶但事件不回调），
  因此新增 `mixin/ServerGamePacketListenerMixin`：在 `ServerGamePacketListenerImpl.handleInteract`
  的 `HEAD` 读 `ServerboundInteractPacket`，`Action.INTERACT` + `Cow` 才交给
  `SuperCowMod.handleCowRightClick`，被消费时 `ci.cancel()`。
- `stillValid`：主人 UUID 匹配、宠物存活、距离 ≤ 24 格，否则面板自动关闭。

## 十个"抽象"技能（quirk skills）

在原来 10 个技能之外再加 10 个（合计 21 个，面板里每一个都能单独开关，权重都是 8 / 14）：

| id | 名称 | 表现 |
| --- | --- | --- |
| `cow_rain` | 奶牛雨 | 天上掉下 3 只（狂暴 4 只）无 AI 的牛砸目标，每只 2.0 伤害，落地化作烟 |
| `milk_bomb` | 牛奶炸弹 | 抛物投出，落点清除目标增益 + 缓慢，伤害很低 |
| `moon_walk` | 月球漫步 | 宠物自己获得跳跃提升 / 缓降 / 速度，一路弹跳 4 秒 |
| `moo_wave` | 哞叫波 | 两圈扩散音波把周围怪物推开 |
| `hay_tornado` | 干草旋风 | 干草粒子绕着目标转，把怪物慢慢吸向中心 |
| `butterfly_swarm` | 蝴蝶群 | 3 只蝙蝠围着目标乱飞，隔一会儿舔一下 |
| `zero_gravity` | 零重力 | 附近怪物（连宠物自己）一起飘起来 |
| `mirror_cow` | 镜像牛 | 刷一只无敌假牛吸引仇恨，时间到就散 |
| `teleport_swap` | 换位 | 宠物和目标的坐标瞬间对调 |
| `confetti` | 彩纸爆炸 | 一堆烟花粒子，伤害低、很吵 |

- 全部通过 `/supercow skill <id>` 可以强制释放（无视权重与冷却，但仍受"技能开关"约束：
  面板里关掉的技能，强制释放会提示 `§c已被关闭，可在面板中开启。`）。
- 持续时间统一由 `quirkSkillDurationTicks`（默认 80 tick = 4 秒）控制；刷出来的实体全部登记进
  `SkillCleanup`，宠物死亡 / 召回 / 主人下线时连同 `QuirkSkills.cleanupForOwner` 一起清空。
- 数值都按"保守"取：这批技能总伤害远低于箭矢，属于趣味技能。

## 数值（全部按"保守"取向下调）

| 项 | 原插件 | 本移植版 |
| --- | --- | --- |
| 基础生命 | 180 | 60（每级 +10） |
| 基础伤害 | 5.0 | 3.0（每级 +0.3） |
| 近战冷却 | 20 tick | 40 tick |
| 特殊技能冷却 | 60 tick | 120 tick（触发率 15%） |
| 经验/击杀 | 25 | 10 |
| 升级所需经验 | 100 起 | 100 起（每级 +60） |
| 召唤数量 | 5 | 2（怒 3，冷却 600 tick） |
| 狂怒伤害倍率 | 1.5 | 1.25 |

完整数值见运行后生成的 `config/supercow-config.json`，改动即时热重载（`/supercow reload`）。

## 新增的运行时保护

- **自动回血**：原插件有、移植初版漏掉的 `+2 HP / 100 tick` 恢复（`regenIntervalTicks`）。
- **不响应 tick 的目标会被放弃**：若目标的 `tickCount` 连续 3 秒不前进（实体被冻结，`hurt()`
  永远失败），宠物会解除锁定并把该实体列入 30 秒黑名单，避免原地空挥。
- **冻结尸体清理**：被打死但永远不播放死亡动画的实体（`deathTime` 卡住 40 tick）会被主动移除，
  防止尸体永久占位（掉落物在 `die()` 阶段已经生成，移除不影响掉落）。

- **面板点击不再"一点就死"**：`PetGui.clicked` 处理所有 `ClickType`，并在末尾 `setSynched(sp, true)`；
  面板关闭时（`removed(Player)`）才写配置，避免每次点击刷盘。
- **技能实体随主人清理**：`QuirkSkills.cleanupForOwner` 现在同时调用 `SkillCleanup.cleanupForOwner`，
  宠物死亡 / 召回 / 主人下线时，奶牛雨的牛、蝴蝶群的蝙蝠、镜像牛都不会留在世界里。
- **AoE 白名单只看真目标**：`nearby(...)` 拆分出带 `explicitTarget` 的重载，只有真正的技能目标
  才享受"无视 Monster 判定"的旁路（之前误把宠物自己当目标传进去，等于白名单恒真）。
- **换目标的记忆表不再泄漏**：`clearTarget` 会移除 `targetTickSnapshots` / `targetStuckSinceTick` /
  `untickableTargets` / `untickableSnapshots` 中该目标的记录。
- **玩家断线清理战斗态**：`DISCONNECT` 里除了存盘，还会 `onPetRemovedOrDied` + `SkillCleanup.cleanupForOwner`。
- **tick 计数器用 long**：`QuirkSkills.Task.startTick/endTick` 改为 `long`，长时间运行不会溢出。

## 测试记录（/opt/hf/final-paradox-test-1.16.5）

- 启动日志：`[SuperCow] Fabric 1.16.5 port initialized.` → `Done (2.576s)!`
- 命令：`supercow help` / `supercow reload` 正常；配置文件与宠物存档正常读写。
- 战斗（无客户端，用临时自测工具驱动，工具已在提交前删除）：
  近战 3.0 伤害对 2 点护甲的僵尸结算为 2.94（符合 1.16.5 护甲公式），箭矢 2.5 伤害，
  击杀计数 +1 / 经验 +10，生命 60/60 并持续回血，宠物死亡后 120 秒自动复活。
- 日志中除世界自带的 `Couldn't load advancement` 之外无任何 Exception / ERROR。
- 面板（GUI）：`/supercow gui` 与右键打开，标题 `§6§l超级牛 · 面板`、90 格（54 + 36）；
  点技能格 9 在 `§7✘ 箭矢` ↔ `§a✔ 箭矢` 之间切换，点"全开"格 39 后槽 9 变为 `§a✔`，
  点玩家背包半区（槽 54）不产生任何变化、手持牛奶桶不变（无复制/无鬼影），点屏障格 53 正常关闭。
  切换后 `config/supercow-config.json` 的 `skillEnabled` / `quirkMode` 落盘正确。
- 十个抽象技能逐个强制释放（`/supercow skill <id>`）：奶牛雨当场刷出 4 只牛并砸死僵尸、
  哞叫波/干草旋风击杀僵尸并掉经验球、蝴蝶群刷 3 只蝙蝠、镜像牛让牛的计数 1→2、彩纸爆炸把克隆牛收走；
  释放后服务器日志除世界自带的 `Couldn't load advancement` 外无任何 Exception。
- 面板点击在客户端的视角会显示 `Server rejected transaction`（vanilla 的 ack/重同步路径），
  服务端状态是正确的，属于预期日志。



## 击杀归属：宠物击杀算作玩家击杀

原 Bukkit 插件里宠物的击杀不算玩家击杀：怪物死亡时 `getKillCredit()` 拿到的是宠物牛本身，
而 `Entity.awardKillScore` 是空实现，于是没有 `killed_by_player` 掉落、没有经验球、
没有统计与成就。

现在在 `SuperCowMod.onLivingEntityDie`（mixin 注入在 `LivingEntity.die` 的 `HEAD`）里：

- 若死亡实体没有真正的玩家击杀归属，且伤害来源能追溯到某玩家的宠物 / 小牛 / 投射物 / 特殊攻击，
  先调用 `self.setLastHurtByPlayer(owner)`：
  - `LivingEntity.createLootContext(hitByPlayer=true, ...)` 会写入 `LootContextParams.LAST_DAMAGE_PLAYER`，
    于是 16 个带 `minecraft:killed_by_player` 条件的战利品池（僵尸的铁锭/胡萝卜/土豆、蜘蛛眼、
    凋灵骷髅头、幻翼膜、恼鬼的铁剑等）正常判定；
  - `dropExperience()` 需要 `lastHurtByPlayerTime > 0`，因此会掉经验球并结算
    `getExperienceReward(player)`（含抢夺 / 幸运影响）。
- 随后 `ServerPlayer.awardKillScore(victim, 0, source)`：计分板击杀数（`totalKillCount`、
  `playerKillCount`）、`MOB_KILLS` 统计、`PLAYER_KILLED_ENTITY` 成就触发器、队伍击杀数
  全部按玩家击杀处理。
- 只在 `die()` 阶段写入，战斗过程中绝不设置"最近被玩家攻击"标记，避免怪物仇恨从宠物转到主人；
  若原版已经判定为玩家击杀（真人打了 ≥1/3 伤害）则整段跳过，不会重复计分。
- 宠物自身的死亡走 `handlePetDeath`，不进入该逻辑；友伤/自伤拦截不受影响。

A/B 对照（同一只 Lv.1 超级牛、同一批僵尸、忽略不计的世界自带怪物）：

| 版本 | 宠物击杀数 | 玩家 `totalKillCount` | 玩家经验 | 经验球 |
| --- | --- | --- | --- | --- |
| 打补丁前 | 9 | 7 → 7（不变） | 5522 → 5522（不变） | 无新增 |
| 打补丁后 | 6 → 8 | 5 → 7 | 5517 → 5519 | 9 → 10 |

`world/stats/<uuid>.json` 中 `minecraft:mob_kills` 也从 0 增长到 5，
说明统计 / 成就侧同样走了玩家击杀路径。

### 已知限制

- 服务器端没有真实玩家，驯服、`/supercow` 的玩家交互类子命令、友伤判定只能在有客户端时验证。
- 测试环境观察到一个与模组无关的现象：部分怪物会在死亡动画开始后停止 tick，永久卡在
  `health=0 / deathTime=0`。代码已做上述两项兜底，正常连接玩家时未复现。
