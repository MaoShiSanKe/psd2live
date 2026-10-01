# PSD2Live

[中文](../../README.md) · [日本語](../ja/README.md) · [Download](https://github.com/tsunehimatoi/psd2live/releases/latest) · [Documentation](../README.md)

**Generate a Live2D model from a layered PSD, then refine, rig, animate, simulate and export it in one desktop workspace.**

![PSD2Live editing workspace: hierarchy on the left, the canvas in Deform mode showing the front-hair mesh, model presets and the layer classification table on the right](../imgs/overview.webp)

PSD2Live recognizes parts from layer names and generates meshes, a deformer hierarchy, head, body and facial parameters, basic motions and physics. The generated model is a starting point: keep shaping it on the canvas, cut and subdivide meshes, paint textures, build a skeleton and edit motion curves, then export a `.cmo3` for further work in Cubism Editor or a `.moc3` runtime bundle.

![The same model at different head and body angles: left, lower right, neutral, upper right, lower left](../imgs/poses.webp)

<sub>Automatic result for the bundled example PSD, without manual edits, driven by the head and body angle parameters.</sub>

## Features

| Area | Capabilities |
| --- | --- |
| Automatic rigging | Chinese / English / Japanese layer names, automatic splitting of paired parts, adaptive meshes, head and body deformer chains, eye, mouth, gaze and brow parameters, idle / blink / nod / shake motions, hair and eye-jelly physics |
| Canvas editing | Select / Deform / Edit / Paint modes; deformation brushes, mesh subdivision and cuts, Warp / Rotation creation, Glue and deform paths (experimental) |
| Skeleton | Inferred skeletons for limbs, tails and wings with FK / IK posing, baked on export into native Cubism deformers, parameters and corrective keyforms that run without PSD2Live |
| Swing and physics | Generated lateral / vertical sway with matching pendulums; visual pendulum editing, response curves and chained groups, evaluated to match the Cubism Native Framework |
| Artwork and variants | Transparent image placement, toggle and exclusive variants, layer painting and edge cleanup, optional 2× / 4× texture upscaling |
| Animation | Timeline, keyframe and curve editing with live preview; preset crouch, wave, cheer and other motions when a skeleton is available |
| Projects | Single-file `.psd2live` projects, branching history, tabs, six workspace presets (Edit, Mesh, Rigging, Animation, Preview, Physics), light and dark themes, Photoshop / Blender / Cubism keymaps |
| Agents | Authenticated local MCP server with 26 public tools for observation, shapes, artwork, parameters, skeletons, motions, physics, simulation, export and history |

<table>
<tr>
<td width="50%"><img src="../imgs/skeleton.webp" alt="Rigging workspace: the skeleton tab lists bones and bound meshes; both arms are raised with IK on the canvas"></td>
<td width="50%"><img src="../imgs/physics.webp" alt="Physics workspace: preview canvas, parameters and the physics panel with its pendulum canvas and response curve"></td>
</tr>
<tr>
<td align="center"><sub>Skeleton: inferred automatically, posed by dragging bone tips with IK</sub></td>
<td align="center"><sub>Physics: pull the pendulum directly and watch the response curve update</sub></td>
</tr>
</table>

![Animation workspace: motion list, preview canvas and parameters, with the animation editor showing nine parameter tracks of the idle motion](../imgs/animation.webp)

<sub>Screenshots show the Chinese interface; switch languages from the Language menu.</sub>

## Download

Get the latest version from [Releases](https://github.com/tsunehimatoi/psd2live/releases/latest):

| Platform | Package | Notes |
| --- | --- | --- |
| Windows 10 / 11 x64 | Portable ZIP, EXE, MSI | Bundles a Java runtime; extract or install and run |
| Linux x86_64 | Deb | Bundles the runtime and Cubism native preview; requires X11 / GLX (XWayland works) |
| Other | Run from source | Install JDK 21; see [Build from source](#build-from-source) |

The Linux native preview does not support pure Wayland without XWayland, aarch64 or musl (e.g. Alpine); those environments fall back to the built-in renderer. See [Cubism native preview](guide/CUBISM_SDK_SETUP.md).

## Quick start

1. **Import a PSD** with **File → Import PSD…** (`Ctrl+Shift+O`) or drop it on the window. If a layer holds several disconnected parts (such as both legs), PSD2Live offers to split it by mesh.
2. **Check the classification** in the Layers table: part type, side and variant settings. Correct anything that was misread.
3. **Preview and refine** in the Preview workspace, then adjust in the Edit, Rigging, Animation and Physics workspaces as needed.
4. **Save and export**: `Ctrl+S` saves a `.psd2live` project; `Ctrl+G` opens export settings for `.cmo3` and / or the `.moc3` bundle.

<img src="../imgs/import-split.webp" width="560" alt="Batch split dialog: legwear, footwear, eyelash and front hair are each detected as two parts">

**New to PSD2Live? Open Help → Tutorials… (`F1`).** The interactive tutorials highlight each control and use your current shortcuts. There is a beginner path (17 lessons) and a path for Cubism users (12 lessons); the [user guide](guide/USER_GUIDE.md) is the text companion.

## Preparing artwork

Layer structure matters most for the automatic result:

- Separate eye whites, irises and upper lashes, and keep the part of the iris hidden under the eyelid.
- Provide an open-mouth drawing, or separate upper teeth, lower teeth and tongue.
- Separate front and back hair and leave overlap under covered areas.
- Keep the body roughly upright; rasterize layer effects and text.

See [PSD preparation and naming](spec/PSD_LAYER_SPEC.md) for the full name table. Unrecognized layers are kept and can be classified manually.

## Output files

| File | Purpose |
| --- | --- |
| `.psd2live` | PSD2Live project: source PSD, artwork, settings, every edit and all history branches. Save this to keep working |
| `.cmo3` | Cubism Editor model project for inspection and refinement in the official editor |
| `.model3.json` + `.moc3` + textures, physics, motions | Runtime bundle; deliver together and load from `.model3.json` |
| `.psd2live.json` | Export diagnostics, not a project |

Export targets Cubism 3.0 – 5.0 (default 5.0); features the target cannot express are downgraded and reported. A successful export does not guarantee identical results in every runtime, so check the model in the target editor and runtime before delivery. Support boundaries are described in the [runtime and export reference](../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) (Chinese).

The built-in renderer needs no official SDK. [Cubism native preview](guide/CUBISM_SDK_SETUP.md) is optional and lets you compare against the official runtime's rendering and physics.

## Connecting an agent (MCP)

1. Keep PSD2Live running and open **Tools → MCP → MCP Connection & Setup…**.
2. Copy the configuration for your host. Hosts with Streamable HTTP connect directly; Stdio-only hosts use [`mcp_proxy.py`](../../mcp_proxy.py) in the repository root.
3. Have the agent call `inspect` first. Every write goes into the same history as UI edits and can be undone in the app.

The [MCP reference](../zh/agent/MCP_AUTHORING.md) (Chinese) lists requests and examples. The MCP server does not generate images; new artwork requires image generation in the host. A callable tool does not make a complex modeling task reliable; [recorded evaluations](../zh/STATUS.md) keep both successes and failures.

## Build from source

Requires JDK 21; Gradle runs through the bundled wrapper.

```bash
./gradlew run                      # start the GUI (Windows: .\gradlew.bat run or run-gui.bat)
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"   # generate from the command line
./gradlew test                     # run the tests
./gradlew packageDistributionForCurrentOS   # build an installer for this platform
```

Source builds use the built-in renderer. The official Cubism SDK must be obtained separately to build the native bridge. See [development and CLI](guide/DEVELOPMENT.md) for all CLI options, packaging and native builds.

## Documentation

| Using PSD2Live | Reference |
| --- | --- |
| [User guide](guide/USER_GUIDE.md) · [Development and CLI](guide/DEVELOPMENT.md) | [PSD preparation](spec/PSD_LAYER_SPEC.md) · [Deformers and parameters](spec/DEFORMER_AND_PARAMETER_SPEC.md) |
| [Cubism native preview](guide/CUBISM_SDK_SETUP.md) · [CI and releases](guide/CUBISM_CI_RELEASE.md) | [Project format](spec/PROJECT_FORMAT.md) · [Implementation overview](spec/IMPLEMENTATION_COMPARISON.md) |

Guides for canvas editing, skeletons, swing, physics and texture upscaling are currently in Chinese; see the [documentation index](../README.md). Example PSDs and outputs are in [examples](../../examples/readme.md).

## Contributing

Issues, reproducible PSDs, documentation fixes and code are welcome.

- **Bug reports**: include the version, operating system, steps to reproduce, expected and actual results, and relevant entries from the Log panel.
- **Code**: run the tests related to your change (`./gradlew test`). New editing features must persist in the project and replay after reopening; see [development](guide/DEVELOPMENT.md).
- **Agent evaluations**: also record the host, model, retries and cost, following the [evaluation format](../zh/STATUS.md).

## License

Code is released under [GPL-3.0](../../LICENSE); see [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) for third-party components. Example artwork has its own usage terms.

PSD2Live is an independent project, not affiliated with or endorsed by Live2D Inc. This repository does not contain or distribute proprietary Live2D Cubism SDK components. Live2D and Cubism are trademarks of Live2D Inc.
