# 变形器与参数参考

[文档目录](../../README.md) · [English](../../en/spec/DEFORMER_AND_PARAMETER_SPEC.md) · [日本語](../../ja/spec/DEFORMER_AND_PARAMETER_SPEC.md) · [PSD 素材与命名](PSD_LAYER_SPEC.md)

本页描述自动生成的默认结构与编辑约定。实际对象取决于素材、模型预设和后续编辑，应以层级树、参数面板或 MCP inspect 返回值为准。

## 结构

身体的根是 `DeformBodyXY`，只覆盖身体自己的部件（躯干、衣服、手臂、裙子、尾巴）和头部转动的脖子，不覆盖头发和腿。身体 X 让躯干像实体一样绕中线转向：胸前向转向一侧移动，转向前方的肩膀略大略低，脖子靠近转轴、几乎不动，头始终在身体中间；身体 Y 向下时肩膀略张开、向上时背部伸直。身体同时随髋部移动：身体 X 时略微横移，身体 Y 向下时下沉、膝盖内扣，向上时腿伸直、略微升高。其下依次是 `DeformBodyLean`（参数“身体前后倾”，上身绕腰前倾时变矮变宽、头变大，后仰时略高略窄）和 `DeformBodyZBreath`（身体 Z / 呼吸），头部旋转与头壳跟随位于其下。站立的全身角色另有并列的根 `DeformLegs`（腿部），只承载腿和鞋，脚在任何身体参数下都不动。面部经纬网、脸部轮廓和五官位移承接眼眉鼻嘴耳；前后发各有头壳跟随与物理分支。实际层级还包含区域组、瞳孔保持 / 视线及可选嘴唇等对象；具体结构以层级树或 MCP `inspect` 的返回为准。

## 坐标与关键形

画布原点在左上，X 向右、Y 向下。对象几何在父级局部空间中求值；Warp 的归一化局部坐标与 Rotation 局部坐标不可混用。静息网格和关键形在转换时还存在不同的坐标约定，导出通过 restMeshesToCanvasSpace 等转换处理。

直接绑定决定对象自己的形状轴，父级参数通过层级继承。关键形是参数坐标处的对象形状，不是动画时间帧；动画文件随时间驱动参数，物理也输出参数值。新增参数后仍需绑定形状才会有动作。

## 自动形变

头部 X/Y 以端点与中点组合生成九姿态；原画头部倾斜作为局部基准估计。面部采用经纬网和分区修形，眼眉、瞳孔、嘴和耳朵有独立补偿或遮罩逻辑。腿按髋、膝、踝两骨 IK 弯曲，膝盖朝观众方向弯，正面看大腿变短而不是向外甩；腿画在一张图层上也能分别屈膝。头部旋转和直接挂在身体上的骨骼旋转变形器另以身体前后倾为轴随前倾缩放。腿已拆分并绑定到腿骨时，每根挂着腿部网格的腿骨下另有一个以身体 X/Y 为轴的站姿 warp，把网格放到腿部 warp 给出的位置：旋转变形器只传递支点和角度，腿部 warp 的弯曲传不到它下面。平面倾斜与呼吸分层处理：身体 Z 让腰线以上绕腰倾斜，各行只平移、宽度不变；呼吸按原画的肩线和腰线定位，抬起肩部和它带着的头、手臂，胸口略微扩张，腰以下不动。前后发分离于强面部形变，并分别输出摆动参数；眼球形变可由眨眼物理驱动。

这些是预设算法，不是通用 3D 重建。原画分层、锚点误差、宽发片、极端角度与重叠区域都可能需要人工修形。精确曲线常数和网格分割随实现演进，维护时直接核对源码，不复制脱离版本的公式。

## 默认参数

下表来自 RigParameters。网格模式、关闭变形器或缺少相应部件时，实际参数集合可能不同；用户也能增加参数与差分。

| ID | Range | Default |
| --- | --- | --- |
| `ParamAngleX` | -45…45 | 0 |
| `ParamAngleY`, `ParamAngleZ` | -30…30 | 0 |
| `ParamBodyAngleX`, `ParamBodyAngleY`, `ParamBodyAngleZ` | -10…10 | 0 |
| `ParamEyeLOpen`, `ParamEyeROpen` | 0…1 | 1 |
| `ParamEyeBallX`, `ParamEyeBallY`, `ParamEyeBallForm` | -1…1 | 0 |
| `ParamBrowLY`, `ParamBrowRY` | -1…1 | 0 |
| `ParamMouthForm` | -1…1 | 0 |
| `ParamMouthOpenY`, `ParamBreath` | 0…1 | 0 |
| `ParamHairFront`, `ParamHairBack` | -1…1 | 0 |

## 验证

检查中立姿态、端点、组合角和中间值；检查父级与局部形状是否重复施加运动。导出流水线包含几何诊断与读回检查，但警告不是“所有姿态已验证”的证明；运行时目标不支持的功能还可能被降级。

[RigBuilder / RigParameters](../../../src/main/kotlin/io/github/psd2live/core/RigBuilder.kt) · [Pipeline](../../../src/main/kotlin/io/github/psd2live/core/PSD2LivePipeline.kt) · [PuppetModel](../../../src/main/kotlin/org/umamo/runtime/model/PuppetModel.kt) · [Architecture (中文)](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)
