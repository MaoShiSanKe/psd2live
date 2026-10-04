# AI / MCP 重构交接

用户要求：完成当前冲突处理后暂停，准备交给 Claude。完整目标仍是“完成重构”，没有缩小为创建 PR 或通过编译。暂停期间不要继续实施后续功能；用户恢复工作后，再按下面的状态接续。

## 工作区与 PR

- 工作目录：`D:\code\live2d\psd2live`；分支：`refactor/agent-mcp-application`。
- 草稿 PR：[#19](https://github.com/tsunehimatoi/psd2live/pull/19)。第一份检查点提交为 `26f31137`。
- 已处理与上游 `master` 的 20 个提交的合并，目标提交 `f66636ce`。保留 GPU 画布、指南、路径、参数快照 ghost、局部画笔上传和网格单位功能，没有恢复已退休的 Agent/ViewModel 接口。
- 本次合并验证结果与最终暂停提交以本页末尾记录及 `git log -2` 为准。不要沿用 PR 初始描述中的“上游尚未整合”。
- `build/` 中的日志、XML、图片及辅助脚本是本机证据，未随源码提交。原始测试证据应保留，不能用后续专项覆盖旧全量结果。

## 已确定的目标与不可变式

面向外部 Agent，不内置聊天或模型服务。GUI 全部业务能力须有可调用的 MCP 入口，主题、布局、快捷键和纯显示用的变形器辅助线除外；本轮不增加独立无界面服务器。直接替换旧 API，不增加兼容别名。

先阅读根目录 `AGENTS.md`、`CLAUDE.md`，架构改动还要核对源码。遵守仓库保密与 SDK 排除规则，验证使用合成或公开测试数据。架构规则集中在 `CLAUDE.md`；中立 `project` 不依赖 `application`，中立 `application/core` 不依赖 Compose、GUI、Agent 或 MCP。业务端口必须为抽象必需方法，不添加默认抛异常、空结果或委托 fallback。

持久状态是 `WorkspaceDocument`、序列化编辑日志与辅助数据，`PuppetModel` 是重建结果。新编辑通过候选计算 → 重建/重放 → 起始 state CAS；必须覆盖历史、保存重开、导出读回和视觉。保持 v1 编解码、原历史节点和 ID，读取旧文档不能自动升级 revision。文档批量 1–128 项顺序读取前序候选，失败/取消不发布前缀，成功最多一个 Rig 历史节点。作者姿态和临时求值帧分开。

修改携带工程、加载代次及持久版本组成的不透明 state，作者来自可信上下文。不能在手势确认时重新取 state 绕过冲突。后台任务由进程拥有，请求去重、查询、等待、取消及严格终态契约共用；CAS 后迟到取消或刷新异常不能冒充回滚。保存、导出和复合查询使用一次捕获，不混入后续状态。

## 已完成与当前实现位置

- 应用注册、状态、命令和任务：`application/WorkspaceOperations.kt`、`WorkspacePorts.kt`、`WorkspaceRuntime.kt`、`WorkspaceDocumentCommands.kt`、`WorkspaceDocumentEdits.kt`、`WorkspaceJobs.kt`；桌面适配为 `ui/state/DesktopWorkspace.kt`。
- `project/WorkspaceDocument.kt`、`WorkspaceSettingsCodec.kt`、`WorkspaceStore.kt`、`ProjectRepository.kt` 拥有中立文档、编解码、历史及归档。GUI 编解码和控制器已移至 `ui/state`。
- 导入、保存、导出、素材、图片放置、栅格绘画、分区与深度拆分、网格/生成迁移、姿态/FK/IK/自动打键、时间线/动作、播放/跟踪、物理预设、摆动草稿、PaintRasterSession 等已有中立业务与相关回归。
- 第二组已实现实时模拟会话、CanvasDeformStroke、Warp 拓扑/Bezier 控制和绘制顺序。当前注册表契约基线为 **158 项公开操作、58 项后台操作、79 项批量成员**，来自最近全量中的严格契约测试；不要把未来设计工具计入。
- 当前合并补齐 `meshUnits`：应用 schema/校验、中立设置编解码、旧设置和旧生成基线按 `PIXELS` 读取、新基线保存实际单位、全局/语义迁移及隐藏图层查询的 unitScale。GUI 单位开关进入窄设置端口。
- GPU 合并把核心指南的屏幕点统一为 `RenderPoint`，没有把 Compose `Offset` 引入 core。`PaintSession` 继续依赖中立 handle，GPU 读取其锁定快照，脏块队列和上传版本不会覆盖后来的笔触。
- 骨架首次进入已改为提交成功后续接；保留 expectedState、实际 committedState、加载/工作区/画布身份及进入序号，显式离开后不抢回旧工具。**完整骨架草稿提交边界仍未迁移。**
- 新增 `core/PhysicsAudition.kt` 与 `PhysicsAuditionTest.kt`：选中组试听的共享候选核心，复制粒子、drag、输出、峰值和时钟；取消不发布部分步进。**尚未接 GUI、应用会话或公开工具，不代表物理试听域已经完成。**

## 最近测试基线与仍需复验的修复

最近一次实际全量为 Windows / JDK 21，196 个类、976 项：**947 通过、19 失败、10 跳过**。日志 `build/parallel-refactor-checkpoint-full-3.log`，完整 XML `build/parallel-refactor-checkpoint-full-3-results/`，失败摘要 `build/parallel-refactor-checkpoint-full-3-failures.json`。绘制顺序新增 4 项、Warp/Bezier 新增 7 项在该次通过；合成图片已目视检查。

随后三个组已处理以下问题，但最终必须用新全量确认，不能宣称 19 项全部消除：

- Sim start/restart 省略 values 时复用持久作者姿态；取消测试核对真实 restart 检查点和场景回滚。
- 旧姿态/锁、Swing、播放和跟踪集成测试等待正确异步边界；多画布测试使用真实 Backend。
- 形变 fixture 从错误的内部 journal 入口改为公开批量入口；导出几何按 Drawable ID 对齐，另外严格检查实际 drawOrder/renderOrder。
- 导入模型深度拆分测试改为验证已实现功能；非法目标仍验证无前缀提交。
- meshOnly 下不生效的 head/body strength 变化不触发生成迁移；启动预设在没有对应物理实体时仍保存开关选择。
- CMO3 替换测试逐次等待姿态和锁持久化后再捕获请求 state，并保留完整 job JSON 错误。不要通过接受新 state 掩盖旧令牌冲突。
- 新骨架入口 6 项真实 Backend 回归覆盖首次进入、外部修改、换模式/工具、换工程和工作区隔离。

上游合并后的首次全量尝试 `build/refactor-upstream-full-1.log` 在主源码编译失败，**未运行测试**；原因是自动合并留下指南的 `Offset` 类型，已改为 `RenderPoint`。不能把旧 XML 当作这次全量结果。

## 最后五个业务缺口（待用户恢复后实施）

1. **Skeleton 业务与草稿。** `CanvasEditor` 的批量变换、复制/镜像、细分/消解、链生成及手动权重绘制/清理/映射/转移仍在 GUI 准备。公开写最终 spec 不等于调用同一算法。建议中立 typed intents、纯候选处理器和应用拥有的草稿会话。特别注意 `openSkeletonDraft` 自己会异步重置 rest pose；会话只能承接自己成功的 pose CAS，之后保留这个 lineage，外部姿态/文档/重开仍冲突。不要简单保存 reset 前 token 或确认时 fresh capture。新工具命名和数量仍是设计，尚未注册。

2. **局部画布显隐/隔离与 GUI reparent。** 此处审计已纠正：`layerVisibility/isolation` 是每 workspace/canvas/mode 的持久呈现状态；`PSD2LiveState.buildConfig` 明确不把 canvas visibility 写进共享模型，`MultiCanvasIsolationTest.visibilityAndSoloStayLocalAndNeverEnterModelConfiguration` 有直接证据。应增加中立 address/visibility processor、辅助 CAS 及 `canvas_visibility` 控制/查询，原 v1 presentation 字段保持原位读取保存；不能让局部 solo 改变其他画布或导出。GUI reparent 改为既有 structure journal 的 bind/move 候选，旧 v1 parentOverrides 继续按原序读取。

3. **设置联动与作者姿态原子边界。** VM 的 meshOnly、动作子项及 generatePhysics 仍有 GUI-only reset/依赖，公开 generateDeformers/exportMotions 还可能被配置读取覆盖。建议完整 patch 一次解析中立设置 intent，明确 raw/effective policy，显式字段优先。批量每成员在私有候选上更新 document/model/aux，最后一次 CAS 发布，不能文档先提交再更新姿态。按真实 Parameter.default、各工作区 locks 处理，保留每 workspace 的持久作者姿态；Nod/Shake 不能依赖 GUI 的 processActiveMotion。复合预设/字段草稿的 off→on 顺序不能被最终 diff 吞掉。设计详见本机交接上下文，仍需源码核对后实现及文档说明。

4. **选中物理组试听与实测拟合。** `PendulumCanvas` 仍自己拥有 engine/drag/peaks/clock；现 preview_physics 是模型级预览。将 GUI 与应用私有会话接到已新增的 `PhysicsAudition`，暴露控制/步进/读帧，查询不推进时钟，文档变化/加载切换有陈旧语义。`WorkspacePhysicsIntent.FitObserved` 已能共享 GUI 实测拟合，但公开 `physics_fit` 仅标准 trace；应支持严格 observed peaks 并共用候选、验证索引/有限值/无响应。不要泄漏可变引擎、顶点数组或给取消步进留下前缀。

5. **多次输入画布草稿的起始捕获。** Warp/Rotation placement、knife、path 仍可能在确认前清 gestureState，随后重取 token 解释旧坐标/顶点索引。保留首点/放置开始的 state、加载身份、模型、pose、目标与坐标映射；失败保留可取消草稿，成功后再清理和选择。复用已有 canvas_warp/rotation/topology/path_put 候选即可，不要求新增视觉 ghost 会话工具。骨架与此组会修改同一 CanvasEditor，需按精确区域分工。

上述五组完成后，还要同步当前架构、接口及 UI/MCP 矩阵，审查历史/归档/导出/视觉、冲突/取消、请求/终态/认证契约；最终同一份源码分别运行 Windows 与 Ubuntu 全量。不能用此前阶段的两平台成功记录代替当前代码证明。

## 恢复与验收操作

Windows 使用 JDK 21，系统默认 JDK 25 不适合当前验收命令：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat test --offline *> build/claude-resume-full-test.log
```

所有源码/测试 owner 先冻结再编译或测试，运行时不允许另一智能体改文件。按结果定位实际失败，不削弱几何、状态、历史或异步断言。简单文档/格式修改不另写或运行镜像测试。Python 位于 `C:\Users\whx12\AppData\Local\Programs\Python\Python312\python.exe`，使用 `-X utf8`。

本机辅助脚本包括 `build/summarize-checkpoint.py`（只在确实运行本次 test 后复制 XML）、`build/test-discovery-audit.py`（检查所有 Test 方法返回 void）、`build/prepare-linux-files.py`、`build/prepare-refactor-final.py` 和 `build/finalize-refactor-final.py`。最后两个仍是未完成的辅助准备：视觉目录名、工具计数和 overall_complete 必须按最终实际证据更新，不能直接据其结果宣布完成。Ubuntu 验证使用独立 WSL rootfs/JDK 21 与显式 AWT headless；它不能证明桌面窗口启动或所有原生 GL 路径。

## 当前合并验收记录

本次暂停前的专项运行完成：Windows / JDK 21，8 个测试类、27 项，**26 通过、1 失败、无错误或跳过**。生产和全部测试源码均已编译成功。日志 `build/refactor-merge-handoff-targeted-2.log`，原始 XML `build/refactor-merge-handoff-targeted-2-results/`，失败摘要 `build/refactor-merge-handoff-targeted-2-failures.json`。编译产物审计为 1003 个带 Test 注解的方法，全部返回 void；这不是运行了 1003 项测试。

已通过：`GlCanvasParityTest` 5 项、`GpuPaintTest` 3 项、`MeshResolutionTest` 3 项、`WorkspaceMeshUnitsMigrationTest` 2 项、`WorkspaceSettingsCodecTest` 3 项、`WorkspaceSkeletonEntryIntegrationTest` 6 项、`PhysicsAuditionTest` 3 项，以及 CMO3 集成类的另 1 项。GPU 专项未跳过，但开发性能工具及桌面窗口验收没有执行。

唯一失败为 `WorkspaceCmo3ImportIntegrationTest.mcpImportAndReplacementSurviveReconnectAndShareGuiPersistenceAndExport`，源码第 88 行的保存重开历史一致性断言。替换任务已经 completed、对象集合与作者姿态/锁断言通过；测试在保存前捕获两节点历史，`saveProjectNow` 后产生额外的 `Save project` / USER 节点，重开读到三节点。**并非重复导入失败。** 下一位应核对持久文档与 GUI 保存投影为什么产生设置差异，修正真实无变化语义，不能直接弱化历史断言。没有执行该断言之后的图像/后续历史验收。

首轮专项 `build/refactor-merge-handoff-targeted-1.log` 在测试编译失败，未运行测试；上游的 `AdaptiveMeshTopologyTest` 和 `CanvasPerfTool` 仍引用旧 codec/adapter，已修为 `ui/state/WorkspaceStateCodec` 和真实 `DesktopWorkspace`，后者在 finally 关闭。本次之后不再修改源代码或启动新的功能迁移，按用户要求暂停。没有运行新的完整两平台验收；完整重构未完成。
