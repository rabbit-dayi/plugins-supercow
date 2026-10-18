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

## 测试记录（/opt/hf/final-paradox-test-1.16.5）

- 启动日志：`[SuperCow] Fabric 1.16.5 port initialized.` → `Done (2.576s)!`
- 命令：`supercow help` / `supercow reload` 正常；配置文件与宠物存档正常读写。
- 战斗（无客户端，用临时自测工具驱动，工具已在提交前删除）：
  近战 3.0 伤害对 2 点护甲的僵尸结算为 2.94（符合 1.16.5 护甲公式），箭矢 2.5 伤害，
  击杀计数 +1 / 经验 +10，生命 60/60 并持续回血，宠物死亡后 120 秒自动复活。
- 日志中除世界自带的 `Couldn't load advancement` 之外无任何 Exception / ERROR。

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
