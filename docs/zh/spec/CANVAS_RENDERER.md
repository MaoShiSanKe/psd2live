# 画布渲染器重写：架构设计

状态：草案，待评审
范围：编辑画布（选择 / 变形 / 编辑 / 绘画 / 模拟）的贴图与辅助层绘制；预览画布的 Cubism 原生路径不在本次范围内。

## 1. 结论

编辑画布的卡顿来自**在 CPU 上逐像素填充**：贴图由 Skia 按三角形从 4096 贴图页采样，辅助层由 Java2D 画进整张 `BufferedImage`，两者都随画布尺寸和缩放倍数线性变贵。形变计算本身只有 0.3–0.7 ms，不是瓶颈。

新架构把像素工作全部移到 GPU：

- 独立渲染线程持有一个 OpenGL 3.3 core 离屏上下文。
- 仓库里已有、但从未接入应用的 `org.umamo.render.puppet.PuppetRenderer` 负责画贴图。它的形变在顶点着色器里完成，还支持遮罩、混合、粘合和按差异增量上传。
- 新写一个 GL 辅助层渲染器替代 Java2D。
- 结果经 PBO 异步回读成一张 Skia 图交给 Compose 显示，与目前流畅的 Cubism 预览路径相同。
- 平移和缩放时，Compose 先把上一帧按新镜头变换显示，零延迟；GPU 随后补上精确帧。
- 现有 `SkiaRigPainter` 保留为没有 GL 时的兜底。

## 2. 现状与测量

测量条件：示例 `tml`（24 个网格、29 个变形器、3467 个顶点、一张 4096² 贴图页），画布 1600×1000，CPU 栅格。

| 每帧工作 | 适配缩放 | 4× 缩放 |
| --- | --- | --- |
| CPU 形变整模（`RigCanvasSupport.evaluate`） | 0.3–0.7 ms | 同左 |
| 录制贴图绘制指令 | ~1 ms | ~1 ms |
| **绘制贴图（`SkiaRigPainter`，CPU）** | **26 ms** | **82 ms** |
| **Java2D 画全部网格线框** | **12 ms** | **16–20 ms** |
| `BufferedImage` → Compose 图像（画布大小） | 4 ms（原 10 ms） | 同左 |

各模式的表现：

- **静止或悬停**：上一轮已把贴图栅格化缓存，每帧约 1 ms，所以界面动画不再卡。
- **缩放、编辑拖动、改参数**：缓存失效，每次变化都要重做上表中的加粗项。4× 缩放时一帧超过 100 ms。缓存方案在稳定下来那一帧还会再做一次 CPU 栅格化，所以缩放每停一下就多一次约 80 ms 的卡顿。
- **预览画布不卡**：贴图由 Cubism 原生库在 GPU 上画，Java 侧只回读并显示一张图。这条"GPU 渲染、回读、显示"的路径在本应用里已被证明可行。

结论：在 Compose 的 Skia 画布上做 CPU 像素填充无法优化到可用，必须换渲染路径，而不是继续加缓存。

## 3. 目标

| 指标 | 目标 |
| --- | --- |
| 平移 / 缩放 | 每一步 UI 线程 ≤ 2 ms，画面即时跟手；精确帧在 1–2 帧内到达 |
| 编辑拖动（单网格形变、移点、笔刷） | UI 线程每帧 ≤ 4 ms；GPU 每帧 ≤ 4 ms（4K 画布、整模） |
| 静止 | 不产生任何渲染工作 |
| 画质 | 与 Cubism 预览一致：线性过滤、正确遮罩与混合；支持 HiDPI 与可选超采样 |
| 兜底 | 拿不到 GL 3.3 时自动回退现有 Skia 路径，功能不缺 |

## 4. 总体架构

```
UI 线程（Compose）                                渲染线程（GL 3.3 core，离屏上下文）
┌──────────────────────────────┐   CanvasScene   ┌──────────────────────────────────────┐
│ CanvasViewport               │ ─────────────▶ │ CanvasRenderService                  │
│  · 镜头、交互、编辑器         │  (不可变快照,  │  ├ ModelResidency（每个模型一份）     │
│  · CanvasFramePresenter       │   最新者胜)    │  │   PuppetRenderer（贴图，GPU 形变）│
│     画最近一帧 + 镜头重投影   │                │  ├ ViewportRenderer（每个画布一份）  │
│  · 轻量交互层（Skia 矢量）：  │ ◀───────────── │  │   SupersampledSurface 渲染目标   │
│     手柄、光标、笔刷、文字    │  RenderedFrame │  │   OverlayRenderer（辅助层，GL）   │
└──────────────────────────────┘ (Skia 图 + 镜头)│  └ AsyncReadback（PBO 三缓冲）       │
                                                 └──────────────────────────────────────┘
```

### 4.1 渲染线程与 GL 上下文：`RenderHost`

- 新增依赖 `lwjgl-glfw`（及各平台 natives）。用一个**隐藏的 GLFW 窗口**创建 3.3 core 上下文，只用它的 FBO，不显示。
- 一个专用线程（`psd2live-canvas-gl`）独占这个上下文，全部 GL 调用都在这里；对外只暴露投递任务的接口，与 `CubismSdkPreviewSession` 的原生线程模式一致。
- 启动时检测能力：GL 3.3、变换反馈、纹理缓冲。失败就记录原因，并让 `CanvasRenderService.available = false`，画布回退软件路径。
- 平台：
  - Windows 原生可用。
  - Linux 需要 X11/GLX，与 Cubism 原生预览的要求相同，XWayland 可用。
  - macOS 上 GLFW 必须在主线程创建窗口：首版在 macOS 直接走兜底路径，后续再解决。
- 与 Cubism 原生预览互不干扰：两个上下文、两个线程，不共享资源。

### 4.2 `CanvasRenderService`：场景、驻留与调度

- **一个应用一个服务**，所有编辑画布共享 GL 上下文和贴图驻留。
- **`ModelResidency`**：按 `PuppetModel` 身份持有一个 `PuppetRenderer`。
  - 文档换模型时，调用 `updateModel(newModel)`，按 `ModelDiff` 的四档增量处理：
    - 不变：什么都不传。
    - 改了静止位置：只传 positions。
    - 改了 UV：只传 UVs。
    - 拓扑或关键形网格变了：只重传该网格。
  - 编辑路径本来就是写时复制，没碰的网格不产生任何 GPU 工作。
  - 贴图页更新走 `setAtlasPages`，源图层显示走 `setSourceLayerPlan` / `deliverSourceLayerRasters`。
- **`CanvasScene`**：UI 线程每次变化时生成的**不可变快照**，包含：
  - 模型引用和姿势（参数值）；
  - 镜头（`ViewportCamera`）与画布像素尺寸、像素密度；
  - 可见集合、绘制顺序覆盖、选择 / 悬停 / 变暗设置；
  - 辅助层描述（见 4.4）、背景设置。

  快照只放引用和小值，构造成本应远低于 1 ms。
- **调度**：每个画布一个"最新者胜"的邮箱。渲染线程取到最新快照才画，旧的直接丢弃，不排队、不积压。静止时没有快照投递，也就没有渲染。

### 4.3 贴图：直接复用 `PuppetRenderer`

| 编辑画布需要的功能 | `PuppetRenderer` 现状 | 需要做的 |
| --- | --- | --- |
| GPU 关键形与变形器级联、粘合 | 已有（顶点着色器、变换反馈两遍） | 无 |
| 遮罩、反向遮罩、混合模式、部件合成 | 已有 | 无 |
| 显示 / 隐藏 | `setShownDrawables` | 无 |
| 选中着色（普通色、激活色） | `setSelection`、两种高亮色 | 无 |
| 悬停着色（组件色） | 无 | 每网格着色色与强度，扩展现有高亮 uniform |
| 未选中变暗 | 无 | 每网格不透明度乘数 |
| 绘制顺序覆盖 | 无（按模型顺序） | 解析绘制顺序时应用覆盖 |
| 模拟预览直接给出的顶点位置 | 无 | "直接给世界坐标"的绘制路径：跳过形变，用 CPU 位置 |
| 姿势快照的半透明幽灵 | 无 | 同一模型以 0.6 不透明度画进一个合成层 |
| 绘画中的实时笔触图块 | 无 | 首版保留 Compose 覆盖；后续改为 `glTexSubImage2D` 局部更新贴图 |

`RigCanvasSupport` 世界坐标与 `ViewportCamera` 之间的换算集中放进一个纯函数 `CanvasCamera`，并有单元测试，交互和渲染两侧共用。

### 4.4 辅助层：`OverlayRenderer`（新，GL）

把 Java2D 辅助层（`CanvasViewportComposable` 中的 3b–3e 段）和 `CanvasEditorOverlay` 里成批的几何搬到 GPU。

- **几何来源**：
  - 不再每次在 CPU 上形变整模再投影到屏幕。
  - 网格线框直接用 GPU 上已形变的顶点：把现有的变换反馈捕获（`DeformCapturePipeline`，目前只为粘合网格捕获）扩展到需要画线框的网格，线框直接读这个缓冲。
  - 变形器网格、旋转参考、变形路径、包围框点数很少，由 CPU 生成后作为小批次上传。
- **图元**：
  - 粗线：每条线段展开成一个四边形，实例化绘制，可调线宽、颜色和端点样式，抗锯齿在片元里按距离计算。
  - 点：实例化圆点，带描边。
  - 每种样式（颜色、线宽、虚线）一个批次，每帧个位数次绘制调用。
- **留在 Compose 的部分**：手柄、光标、笔刷圈、文字标签、右键菜单等少量且需要字体或即时跟手的元素，用 Skia 矢量直接画（不经 `BufferedImage`）。它们的数量与画面大小无关，几十个以内。

### 4.5 回读与显示：`AsyncReadback` + `CanvasFramePresenter`

- 渲染目标沿用 `SupersampledSurface`：按历史最大尺寸只增不减地分配，可选 2× 超采样后缩小。
- 回读用三个 PBO 轮转：第 N 帧发起异步读取，第 N+1 帧取回第 N 帧，不阻塞 GPU。
- 取回的像素按 RGBA 原样整块装进 Skia `Bitmap`，用 `asComposeImageBitmap()` 包装，不再做逐像素转换，也不再经过 `BufferedImage`。
- 每帧附带渲染时的镜头。Compose 侧显示时，若当前镜头和该帧镜头不同，就按差值对图像做平移和缩放后再画。这样平移、缩放在 UI 线程上即时跟手，GPU 帧到达后无缝替换。
- **代价**：显示比输入晚 1–2 帧（约 16–33 ms），与现在的预览画布相同。拖点时手柄由 Compose 即时绘制，跟手不受影响。

### 4.6 拾取与编辑器读数

- CPU 形变只需 0.3–0.7 ms，拾取继续在 UI 线程用 CPU 几何。
- 改为复用 `PuppetRenderer.pickGeometry()` 同一份姿势输入：按 `(模型, 姿势)` 只算一次并缓存，与渲染使用同一套形变输入，不会出现"画的和点到的不一致"。
- 后续可选：渲染时额外输出一张网格 ID 缓冲，鼠标悬停直接读一个像素。

### 4.7 编辑拖动的一帧

1. 指针移动：`CanvasEditor` 生成写时复制的 `preview` 模型（现有逻辑）。
2. UI 线程投递 `CanvasScene`，指向新模型、当前姿势、当前镜头。
3. 渲染线程依次处理：
   - 调用 `updateModel`：通常只是一个网格的位置更新，或一个网格的关键形纹理重传，微秒到亚毫秒级；
   - 调用 `setPose`；
   - 画贴图和辅助层；
   - 发起回读。
4. 下一帧，Compose 显示新图并画手柄。UI 线程只做投递和贴图，不做任何像素填充。

### 4.8 兜底：`SoftwareCanvasRenderer`

把现有 `SkiaRigPainter`、`CachedSkiaPicture` 和 Java2D 辅助层收拢到同一接口后面：

```kotlin
interface CanvasRenderer : AutoCloseable {
    val available: Boolean
    fun submit(viewId: String, scene: CanvasScene)
    fun frames(viewId: String): StateFlow<RenderedFrame?>
}
```

`CanvasViewport` 只依赖这个接口。GL 不可用时（拿不到上下文、macOS 首版、驱动故障）用软件实现，行为与今天相同。设置里提供"强制软件渲染"开关，用于排查问题。

## 5. 模块与代码位置

| 模块 | 位置 | 说明 |
| --- | --- | --- |
| `RenderHost` | `ui/render/RenderHost.kt` | GLFW 隐藏窗口、上下文、渲染线程、能力检测 |
| `CanvasRenderService` | `ui/render/CanvasRenderService.kt` | 模型驻留、画布注册、邮箱调度、帧发布 |
| `CanvasScene` / `RenderedFrame` | `ui/render/CanvasScene.kt` | 不可变快照与输出帧（Skia 图 + 镜头 + 代号） |
| `ViewportRenderer` | `ui/render/ViewportRenderer.kt` | 单画布目标、背景、贴图、辅助层、回读 |
| `OverlayRenderer` + 着色器 | `org/umamo/render/overlay/` | 线段 / 点实例化批次，放在后端中立的设备接口之上 |
| `AsyncReadback` | `org/umamo/render/gl/` | PBO 轮转，返回 RGBA `Bitmap` |
| `CanvasFramePresenter` | `ui/views/CanvasFramePresenter.kt` | Compose 显示、镜头重投影 |
| `CanvasCamera` | `ui/render/CanvasCamera.kt` | 画布视口与 `ViewportCamera` 的纯函数换算 |
| `SoftwareCanvasRenderer` | `ui/render/SoftwareCanvasRenderer.kt` | 现有路径的封装 |

`PuppetRenderer` 的扩展（悬停着色、变暗、绘制顺序覆盖、直接位置、幽灵层）放在其所在包内，保持"类内不含 GL、全部经 `RenderDevice`"的约束。

## 6. 风险与验证

| 风险 | 对策 |
| --- | --- |
| `PuppetRenderer` 从未在本应用的模型上跑过，RigBuilder 产出的变形器 / 关键形组合可能有未覆盖的情况 | P0 先做**对照测试**：Xvfb + Mesa llvmpipe（支持 GL 4.5 core）下用 GPU 渲染示例模型的多组姿势，与 `SkiaRigPainter`（CPU 参考）逐像素比对，误差设阈值；同时比对变换反馈捕获的顶点与 `CpuDeformationEvaluator` 的结果 |
| 显示晚 1–2 帧 | 镜头重投影保证平移和缩放跟手，手柄和光标由 Compose 即时绘制 |
| 驱动 / 平台差异 | 启动能力检测、自动兜底、"强制软件渲染"开关；渲染错误时降级并写日志，不让画布空白 |
| 显存（4K、2× 超采样约 130 MB） | 超采样默认关闭；只增不减的分配沿用现有策略，后续可加空闲回收 |
| 多画布同时编辑 | 共享驻留，按画布分目标；同一 GL 线程串行，按画布限速 |

## 7. 分阶段计划

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| **P0 地基** | `RenderHost`、`AsyncReadback`；对照测试（GPU 与 CPU 逐像素、顶点比对）；画布性能测试工具（Xvfb 下驱动真实窗口，测缩放 / 拖动 / 平移的帧时间） | 示例 `tml`、`ds` 多组姿势对照通过；测试在 CI 的 Xvfb 下可跑 |
| **P1 贴图上 GPU** | `CanvasRenderService`、`ViewportRenderer`、`CanvasFramePresenter`、镜头重投影；`PuppetRenderer` 补齐悬停、变暗、顺序覆盖；设置开关，默认开启 | 4× 缩放连续滚轮、单网格拖动：UI 线程每帧 ≤ 4 ms；与旧路径截图一致 |
| **P2 辅助层上 GPU** | `OverlayRenderer`；迁移网格线框、变形器网格、旋转参考、变形路径、包围框；删除 Java2D 辅助层 | 显示全部网格线框时，4K 画布帧时间仍达标 |
| **P3 编辑增量化** | 画布只订阅自己需要的窄状态，文档无关更新不再触发画布重组；拾取复用同一份姿势输入；模拟预览直接位置、幽灵层 | 参数面板、时间轴等的状态更新不再引起画布重绘 |
| **P4 收尾** | 绘画笔触改为 GPU 局部贴图更新；软件路径只作兜底；评估把软件预览路径也迁到 GPU | 旧缓存类只在兜底实现中保留 |

每个阶段单独提交、可回退；P1 起画布渲染方式由设置开关控制，出现问题可立即切回软件路径。

## 8. 不在本次范围

- Cubism 原生预览路径（已经流畅）。
- 导出与 Agent 视图渲染（`AgentViewRenderer`），可在 P4 之后复用同一服务。
- Vulkan / Metal / Direct3D 后端。`RenderDevice` 已是后端中立接口，将来新增后端是新增一个设备实现，不必改渲染器。
