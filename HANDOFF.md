# 交接文档：SuperCow 1.16.5 Fabric 移植（截至 2026-10-19）

本文件记录当前版本的状态、已验证项、配置与构建部署方式。长期设计说明另见同目录 `FABRIC-1.16.5.md`。

---

## 0. 一句话现状

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
