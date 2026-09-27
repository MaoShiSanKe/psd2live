# Development and CLI

[Documentation](../../README.md) · [中文](../../zh/guide/DEVELOPMENT.md) · [日本語](../../ja/guide/DEVELOPMENT.md)

This page is for running from source, using the command line or contributing code. Release packages bundle their own runtime and do not need anything here.

## Requirements

- JDK 21. Gradle runs through the bundled wrapper; no separate install is needed.
- Use `.\gradlew.bat` on Windows and `./gradlew` on Linux / macOS. Examples below use `./gradlew`.
- The official Cubism SDK is not required. Source builds use the built-in renderer; see [Cubism native preview](CUBISM_SDK_SETUP.md) for the native bridge.

## Running

```bash
./gradlew run                     # no arguments: start the GUI
./gradlew run --args="--help"     # CLI help
./gradlew run --args="--input examples/tml/psd-input/tml.psd --output build/example-output"
```

On Windows, `run-gui.bat` in the repository root also starts the GUI. Without arguments the GUI starts; with arguments the CLI runs and requires `--input` (except for `--help`). The CLI writes export files directly from a PSD and does not create an editable `.psd2live` project.

## CLI options

| Option | Default | Meaning |
| --- | --- | --- |
| `--input <path>` | required | Layered input PSD |
| `--output <path>` | `psd2live-output` next to the PSD | Output directory |
| `--lang <zh\|en\|ja>` | system language | Log language |
| `--atlas <size>` | 4096 | Texture atlas size |
| `--mesh-spacing <px>` | 64 | Mesh spacing |
| `--head-strength <value>` | 1.0 | Head deformation strength |
| `--body-strength <value>` | 1.0 | Body deformation strength |
| `--mesh-only` | off | Generate meshes only |
| `--no-deformers` | off | Skip deformers |
| `--no-motions` | off | Skip motions |
| `--no-physics` | off | Skip physics |
| `--no-cmo3` | off | Skip CMO3 |
| `--no-moc3` | off | Skip MOC3 |
| `--no-json` | off | Skip the diagnostics JSON |
| `--upscale <1\|2\|4>` | 1 | Texture upscale factor; 1 disables it |
| `--upscale-python <path>` | `python` | Python with nunif dependencies |
| `--nunif-dir <path>` | empty | nunif source directory |
| `--upscale-model <path>` | empty | Weights directory |
| `--upscale-tile <64..512>` | 256 | Inference tile size |
| `--upscale-noise <-1..3>` | 1 | Denoise level; -1 disables denoising |
| `--no-upscale-neural-alpha` | off | Upscale alpha bilinearly instead |

Notes:

- Defaults come from [`Main.kt`](../../../src/main/kotlin/io/github/psd2live/Main.kt). The GUI's initial mesh spacing comes from `PipelineConfig` (40), not the CLI's 64.
- Keep at least one of CMO3, MOC3 or the diagnostics JSON.
- Neural alpha is on by default; `--upscale-neural-alpha` remains only for older commands.
- Upscaling setup is described in the [texture upscale guide](../../zh/guide/TEXTURE_UPSCALE.md) (Chinese).

## Tests and packaging

```bash
./gradlew test                                   # all tests (CI runs them on Ubuntu and Windows)
./gradlew test --tests "io.github.psd2live.core.SwingDeformerTest"   # one test class
./gradlew createDistributable                    # application directory with runtime
./gradlew packageDistributionForCurrentOS        # installer for this platform
```

- Both packaging tasks include only the build host's native libraries and ship `LICENSE`, `THIRD_PARTY_NOTICES.md` and `licenses/` in the application resources.
- Official SDK resources (`src/main/resources/cubism/`) are excluded unless you pass `-Ppsd2live.includeCubism=true` or set `PSD2LIVE_INCLUDE_CUBISM=true`. Packages with the SDK must not be distributed publicly; see [CI and releases](CUBISM_CI_RELEASE.md).
- On Linux, `./native/package_linux.sh` builds a local launcher package that needs a system JDK 21 (written to `dist/linux-<timestamp>/`); see [native/README.md](../../../native/README.md).
- There is no separate lint task; code style is `kotlin.code.style=official`.

## Code layout

Sources live in `src/main/kotlin/` under two top-level packages:

| Package | Responsibility |
| --- | --- |
| `org.umamo.runtime` | `PuppetModel`, keyform interpolation and evaluation |
| `org.umamo.format` | Reading and writing PSD, CMO3, MOC3 and image formats |
| `org.umamo.interop` | Conversion between `PuppetModel` and CMO3 / MOC3 |
| `org.umamo.render` | LWJGL / OpenGL preview |
| `org.umamo.edit` | Immutable editing primitives on the model |
| `io.github.psd2live.core` | Generation pipeline (`PSD2LivePipeline`, `LayerClassifier`, `AdaptiveMeshGenerator`, `RigBuilder`, `MotionGenerator`, `PhysicsGenerator`) and replayable edits |
| `io.github.psd2live.project` | `.psd2live` archives, sessions and workspace state serialization |
| `io.github.psd2live.history` | Branching undo / redo |
| `io.github.psd2live.agent` | Local MCP server and public tool definitions |
| `io.github.psd2live.ui` | Compose UI: `state` (ViewModel, shortcut registry), `views` (workspaces and panels), `components` (dialogs and controls), `tutorial`; canvas editing and painting live in the `ui` root package |
| `io.github.psd2live.i18n` | UI strings, with resources in `src/main/resources/i18n/` |

Keep generation and export logic in `core` / `project`, not in Compose code.

## Core rule: rebuild and replay

`PuppetModel` is not persisted. A project stores the source artwork, layer classification and settings, plus a serializable edit record (`RigEditOverlay`). On every open or change:

1. `RigBuilder` regenerates the base rig from the source artwork;
2. `RigEditOverlay.applyTo` replays edits in a fixed order: parameter deletion / creation → warps and structure → keyforms → the authoring journal in recorded order → swing generation last.

For new editing features this means:

- Store the change in serializable `RigEditOverlay` fields (preferably as a JSON command in the authoring journal) and save / restore it in the workspace state codec. Changes made only to `PuppetModel` are lost on rebuild.
- The UI and MCP should use the same editing commands. Low-level methods in `org.umamo.edit` are not automatically public interfaces.
- Acceptance path: domain data → history replay → project save and reopen → target Cubism version handling → export read-back → visual check. See the [runtime and export reference](../../zh/spec/RUNTIME_EXPORT_ARCHITECTURE_AND_GAPS.md) (Chinese).

New UI strings go into `Messages.properties`, `Messages_zh_CN.properties` and `Messages_ja.properties` together so the key counts stay equal.

## Checking exports

- Deliver every file referenced by `.model3.json`.
- Review warnings in the Log panel and the diagnostics JSON.
- Save a `.psd2live` project to keep editing; `.psd2live.json` is only a report.

Related: [Cubism native preview](CUBISM_SDK_SETUP.md) · [CI and releases](CUBISM_CI_RELEASE.md) · [`build.gradle.kts`](../../../build.gradle.kts)
