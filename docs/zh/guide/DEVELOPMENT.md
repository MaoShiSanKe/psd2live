# 开发与命令行

[文档目录](../../README.md) · [English](../../en/guide/DEVELOPMENT.md) · [日本語](../../ja/guide/DEVELOPMENT.md)

本页面向从源码运行、使用命令行或参与开发的用户。发布包自带运行时，普通使用不需要本页内容。

## 环境

- JDK 21。Gradle 使用仓库自带的 Wrapper，无需单独安装。
- Windows 用 `.\gradlew.bat`，Linux / macOS 用 `./gradlew`。下文示例统一写作 `./gradlew`。
- 官方 Cubism SDK 不是构建前提。源码构建默认使用内置渲染；原生预览见 [Cubism Native 预览](CUBISM_SDK_SETUP.md)。

## 运行

```bash
./gradlew run                     # 不带参数：启动 GUI
./gradlew run --args="--help"     # 查看 CLI 帮助
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

Windows 也可以直接运行根目录的 `run-gui.bat`。不带参数启动 GUI；带参数进入 CLI，此时必须提供 `--input`（`--help` 除外）。CLI 从 PSD 直接生成导出文件，不产生可继续编辑的 `.psd2live` 工程。

## CLI 参数

| 参数 | 默认值 | 作用 |
| --- | --- | --- |
| `--input <path>` | 必填 | 输入的分层 PSD |
| `--output <path>` | PSD 同目录下的 `psd2live-output` | 导出目录 |
| `--lang <zh\|en\|ja>` | 系统语言 | 日志语言 |
| `--atlas <size>` | 4096 | 纹理图集尺寸 |
| `--mesh-spacing <px>` | 64 | 网格间距 |
| `--head-strength <value>` | 1.0 | 头部形变幅度 |
| `--body-strength <value>` | 1.0 | 身体形变幅度 |
| `--mesh-only` | 关闭 | 仅生成网格 |
| `--no-deformers` | 关闭 | 不生成变形器 |
| `--no-motions` | 关闭 | 不输出动作 |
| `--no-physics` | 关闭 | 不生成物理 |
| `--no-cmo3` | 关闭 | 不输出 CMO3 |
| `--no-moc3` | 关闭 | 不输出 MOC3 |
| `--no-json` | 关闭 | 不输出诊断 JSON |
| `--upscale <1\|2\|4>` | 1 | 纹理高清化倍率，1 为关闭 |
| `--upscale-python <path>` | `python` | 装有 nunif 依赖的 Python |
| `--nunif-dir <path>` | 空 | nunif 源码目录 |
| `--upscale-model <path>` | 空 | 权重目录 |
| `--upscale-tile <64..512>` | 256 | 推理分块大小 |
| `--upscale-noise <-1..3>` | 1 | 降噪等级，-1 为不降噪 |
| `--no-upscale-neural-alpha` | 关闭 | 改用双线性放大 Alpha |

说明：

- 默认值以 [`Main.kt`](../../../src/main/kotlin/io/github/psd2live/Main.kt) 为准。GUI 的初始网格间距来自 `PipelineConfig`（40），与 CLI 的 64 不同。
- CMO3、MOC3 和诊断 JSON 至少保留一种输出。
- 神经 Alpha 默认开启；`--upscale-neural-alpha` 仅为兼容旧命令保留。
- 高清化的准备与示例见[纹理高清化](TEXTURE_UPSCALE.md)。

## 测试与打包

```bash
./gradlew test                                   # 全部测试（CI 在 Ubuntu 与 Windows 上运行）
./gradlew test --tests "io.github.psd2live.core.SwingDeformerTest"   # 单个测试类
./gradlew createDistributable                    # 带运行时的应用目录
./gradlew packageDistributionForCurrentOS        # 当前平台的安装包
```

- 两种打包都只包含当前构建平台的原生库，并在应用资源目录附带 `LICENSE`、`THIRD_PARTY_NOTICES.md` 与 `licenses/`。
- 官方 SDK 资源（`src/main/resources/cubism/`）默认不打包，只有传入 `-Ppsd2live.includeCubism=true` 或设置 `PSD2LIVE_INCLUDE_CUBISM=true` 时才包含。含 SDK 的包不得公开分发，发行流程见 [CI 与发行](CUBISM_CI_RELEASE.md)。
- Linux 也可用 `./native/package_linux.sh` 生成需要系统 JDK 21 的本地启动包（输出到 `dist/linux-<时间戳>/`），详见 [native/README.md](../../../native/README.md)。
- 项目没有独立的 lint 任务，代码风格为 `kotlin.code.style=official`。

## 开发工具

`src/test/kotlin/io/github/psd2live/tools/` 中是用于目视检查和测量的开发工具。它们写成测试，以便调用流水线内部接口；只有设置 `PSD2LIVE_TOOLS=1` 时才运行，平时的 `./gradlew test` 会跳过。输出写到 `build/tools/`。

```bash
PSD2LIVE_TOOLS=1 PSD2LIVE_SAMPLE=ds ./gradlew test --tests "io.github.psd2live.tools.MotionSheetTool.body"
```

| 工具 | 内容 | 输出 |
| --- | --- | --- |
| `MotionSheetTool.motions` | 每个预设动作按时间展开的拼图、待机循环、呼吸前后与差分图、身体 Z | `motion-sheet/<示例>-*.png` |
| `MotionSheetTool.body` | 身体 X × 身体 Y、腿部和上半身特写、前后倾、大小变、腿部姿势，分别为无骨架和自动骨架 | `motion-sheet/<示例>-{stance,lean,size,legposes}*.png` |
| `MotionSheetTool.tracking` | 指针在画面上慢速绕行 12 秒，头和身体按预览的增益与速度跟随 | `motion-frames/<示例>-track/` |
| `MotionSheetTool.idle` | 待机 12 秒的逐帧图 | `motion-frames/<示例>-idle/` |
| `ModelProfileTool.cmo3` | `.cmo3` 的参数、变形器树（网格轴与范围）、图形网格、分段运动剖面、身体参数下各网格的位移，身体 X × 身体 Y 的剪影，以及物理组 | `model-profile/<名称>.txt`、`.png`、`-physics.txt` |
| `ModelProfileTool.sample` | 生成模型（无骨架和自动骨架）的分段运动剖面、身体图层与自动骨骼 | `model-profile/<示例>.txt` |
| `SimBakeBenchmark` | 在 `tml` 后发上按几组设置烘焙模拟，在未参与拟合的动作上对比模拟与导出结果，见[模拟与烘焙](SIMULATION.md) | 标准输出 |

| 环境变量 | 作用 |
| --- | --- |
| `PSD2LIVE_SAMPLE` | 示例名（`tml`、`ds`）或 PSD 路径，默认 `tml` |
| `PSD2LIVE_CMO3` | `ModelProfileTool.cmo3` 的输入：`.cmo3` 文件或其所在目录 |
| `PSD2LIVE_PROBES` | 运动剖面探测的参数，`id=值,...`；默认身体 X、Y、Z 的端点 |
| `PSD2LIVE_SHEET_PARAM` | 剪影改为沿此参数展开，代替身体 X × 身体 Y |
| `PSD2LIVE_BONES` | `MotionSheetTool.body` 中修正自动骨架的骨骼位置，`id=头x,头y,尾x,尾y;...`（画布像素） |
| `PSD2LIVE_BIND_LEGS` | 设为 `1` 时把腿和鞋的网格绑定到第一根大腿骨 |
| `PSD2LIVE_ZOOM` | 腿部特写的范围，`左,上,右,下`，按画布比例 |
| `PSD2LIVE_VERBOSE` | 设为 `1` 时 `motions` 另外打印每条曲线 |
| `PSD2LIVE_BAKE_CONFIGS` | `SimBakeBenchmark` 的设置，`模态数:键数,...`，默认 `2:5,2:7,1:5` |

## 代码结构

源码位于 `src/main/kotlin/`，分为两个顶层包：

| 包 | 职责 |
| --- | --- |
| `org.umamo.runtime` | `PuppetModel`、关键形插值与求值 |
| `org.umamo.format` | PSD、CMO3、MOC3 及图像格式的读写 |
| `org.umamo.interop` | `PuppetModel` 与 CMO3 / MOC3 的相互转换 |
| `org.umamo.render` | LWJGL / OpenGL 预览 |
| `org.umamo.edit` | 对模型的不可变编辑原语 |
| `io.github.psd2live.core` | 生成流水线（`PSD2LivePipeline`、`LayerClassifier`、`AdaptiveMeshGenerator`、`RigBuilder`、`MotionGenerator`、`PhysicsGenerator`）与各类可重放编辑 |
| `io.github.psd2live.project` | `.psd2live` 归档、会话与工作区状态序列化 |
| `io.github.psd2live.history` | 分支式撤销 / 重做 |
| `io.github.psd2live.agent` | 本地 MCP 服务与公开工具定义 |
| `io.github.psd2live.ui` | Compose 界面：`state`（ViewModel、快捷键注册表）、`views`（工作区与面板）、`components`（对话框与控件）、`tutorial`（交互教程）；画布编辑与绘画位于 `ui` 根包 |
| `io.github.psd2live.i18n` | 界面文案，资源在 `src/main/resources/i18n/` |

生成与导出逻辑放在 `core` / `project`，不要写进 Compose 界面代码。

## 核心约束：重建与重放

`PuppetModel` 不是持久状态。工程保存的是源图、图层分类与设置，以及一份可序列化的编辑记录（`RigEditOverlay`）。每次打开工程或发生修改时：

1. `RigBuilder` 从源图重新生成基础 Rig；
2. `RigEditOverlay.applyTo` 按固定顺序重放编辑：参数删除 / 创建 → Warp 与结构 → 关键形 → 按实际顺序记录的编辑日志 → 最后生成摇摆。

因此新增编辑功能时：

- 修改必须写入 `RigEditOverlay` 的可序列化字段（新命令优先作为编辑日志中的 JSON 命令），并在工作区状态编解码中保存与恢复。只改 `PuppetModel` 的修改会在重建后丢失。
- 界面与 MCP 应调用同一套编辑命令。`org.umamo.edit` 中的底层方法不自动等同于公开接口。
- 验收链路：领域数据 → 历史重放 → 工程保存与恢复 → 目标 Cubism 版本处理 → 导出读回 → 视觉检查。详见[运行时与导出边界](../spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)。

界面文案新增时，`Messages.properties`、`Messages_zh_CN.properties`、`Messages_ja.properties` 三个文件需要同时添加，保持键数一致。

## 检查导出结果

- `.model3.json` 引用的所有文件都要随模型交付。
- 查看日志面板与诊断 JSON 中的警告。
- 需要继续编辑时保存 `.psd2live` 工程；`.psd2live.json` 只是报告。

相关：[Cubism Native 预览](CUBISM_SDK_SETUP.md) · [CI 与发行](CUBISM_CI_RELEASE.md) · [纹理高清化](TEXTURE_UPSCALE.md) · [`build.gradle.kts`](../../../build.gradle.kts)
