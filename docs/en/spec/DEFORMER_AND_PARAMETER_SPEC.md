# Deformer and parameter reference

[Documentation](../../README.md) · [中文](../../zh/spec/DEFORMER_AND_PARAMETER_SPEC.md) · [日本語](../../ja/spec/DEFORMER_AND_PARAMETER_SPEC.md) · [PSD preparation](PSD_LAYER_SPEC.md)

This page describes generated defaults and editing conventions. Actual objects depend on artwork, configuration and edits. Read the hierarchy, parameter panel or MCP inspect output for the current project.

## Structure

The body's root is `DeformBodyXY`, which spans only the body's own parts (torso, clothes, arms, skirt, tail) and the neck the head turns on, not the hair or the legs. Body X turns the upper body above the waist as one solid about its centre line, the waist as far as the chest, easing out over the hips: the front of the chest moves toward the turn, the shoulder coming forward is drawn a little larger and lower, and the neck, near the axis, hardly moves, so the head stays in the middle of the body. Body Y opens the shoulders a little going down and straightens the back going up. The whole body also moves with the hips: a little sideways on Body X, down onto knees turned in on Body Y down, and a little higher on straightened legs on Body Y up. Below it come `DeformBodyLean` (two parameters. Body Lean: the body bows in three dimensions as a solid with depth and is drawn in perspective, the pelvis tilting a share of the lean about the hip joints and carrying the skirt, the spine bending along an arc across the waist to the full lean, so the waist is not squeezed on its own; leaning in, the chest foreshortens and comes down, a little larger, and the head rides the neck down keeping its shape while the face turns down; leaning back, the body is drawn smaller and the face turns up a little. Proportion (Chibi): at positive values the head grows from the neck as one piece, the chest widens, the torso, the arms, the legs and the skirt shorten and the upper body comes down onto them, toward a chibi; at negative ones the other way, toward a taller figure; the feet stay. Without a skeleton each arm also gets `DeformArmHang_L/R` on both, so it moves and scales with its shoulder as one hanging piece instead of bending with the body) and `DeformBodyZBreath` (body Z / breath), with head rotation and the head-follow container below those. A standing full-body figure has a second root beside it, `DeformLegs` (on body X, body Y and Proportion), which carries only the legs and shoes; the feet never move under any body parameter. Facial lattice, contour and feature displacement organize eyes, brows, nose, mouth and ears. Front/back hair have independent follow and physics branches. Region groups, iris preservation/gaze and optional lips add further nodes; the live hierarchy is authoritative.

## Coordinates and keyforms

Canvas coordinates start at the top left, with X right and Y down. Geometry is evaluated in parent-local space; normalized Warp coordinates and Rotation-local coordinates differ. Rest meshes and keyforms also have conversion-specific conventions, handled by restMeshesToCanvasSpace and format conversion.

Direct bindings define an object’s own axes; ancestor motion is inherited. A keyform is a shape at parameter coordinates, not an animation time frame. Motions and physics drive parameter values. A newly created parameter needs bindings and shapes to produce motion.

## Generated deformation

Head X/Y endpoint and midpoint combinations form nine poses, with estimated initial head tilt as a local reference. A facial lattice and regional corrections handle features, iris preservation, masks and mouth/ear behavior. The legs bend by two-bone IK through hip, knee and ankle, the knee bending toward the viewer so a front-facing thigh shortens instead of swinging out, so legs drawn on one layer still bend at their own knees. The head rotation and skeleton rotations hung straight from the body take Body Lean and Proportion as axes and scale with them. Legs split and skinned to leg bones get a stance warp on body X/Y under each leg bone that carries meshes, placing them where the legs warp puts the legs: a rotation deformer passes on only its pivot and angle, so the legs warp's bend never reaches below it. Tilt and breath are separated: body Z leans everything above the waist about it, each row only shifting so it keeps its width; breath is placed by the drawn shoulder and waist lines, lifting the shoulders with the head and arms they carry and widening the chest a little, with nothing below the waist moving. Hair follows the head independently of strong facial deformation; blink physics may drive iris form.

These are rigging presets, not general 3D reconstruction. Layering, anchor estimates and extreme angles need inspection. Exact curve constants and lattice divisions belong to the implementation and should be checked there rather than copied into an unversioned mathematical promise.

## Default parameters

The table follows RigParameters. Mesh-only mode, disabled deformers or missing parts may change the actual set. User parameters and variants can extend it.

| ID | Range | Default |
| --- | --- | --- |
| `ParamAngleX` | -45…45 | 0 |
| `ParamAngleY`, `ParamAngleZ` | -30…30 | 0 |
| `ParamBodyAngleX`, `ParamBodyAngleY`, `ParamBodyAngleZ`, `ParamBodyLean`, `ParamProportion` | -10…10 | 0 |
| `ParamEyeLOpen`, `ParamEyeROpen` | 0…1 | 1 |
| `ParamEyeBallX`, `ParamEyeBallY`, `ParamEyeBallForm` | -1…1 | 0 |
| `ParamBrowLY`, `ParamBrowRY` | -1…1 | 0 |
| `ParamMouthForm` | -1…1 | 0 |
| `ParamMouthOpenY`, `ParamBreath` | 0…1 | 0 |
| `ParamHairFront`, `ParamHairBack` | -1…1 | 0 |

## Validation

Inspect neutral, endpoints, combined angles and intermediate values, including parent/local interactions. Pipeline geometry diagnostics and export readback are checks, not proof of all poses or identical editor behavior. Target versions may require feature reduction.

[RigBuilder / RigParameters](../../../src/main/kotlin/io/github/psd2live/core/RigBuilder.kt) · [Pipeline](../../../src/main/kotlin/io/github/psd2live/core/PSD2LivePipeline.kt) · [PuppetModel](../../../src/main/kotlin/org/umamo/runtime/model/PuppetModel.kt) · [Architecture (中文)](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md)
