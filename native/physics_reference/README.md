# physics_reference

Runs a `physics3.json` through the official Cubism Native Framework on a real `.moc3` and prints the driven
parameters frame by frame. `PhysicsReferenceTest` compares `PhysicsEngine` with its output, stored in
`src/test/resources/physics-reference/` (numbers only; no SDK code or binaries are committed).

Build against a locally extracted Cubism SDK for Native 5-r.5 and the Framework library the preview bridge builds
(`native/live2d_renderer`):

```bash
SDK=/path/to/CubismSdkForNative-5-r.5
g++ -std=c++17 -O2 -I$SDK/Framework/src -I$SDK/Core/include native/physics_reference/physics_reference.cpp \
  native/live2d_renderer/build/Framework/libFramework.a native/live2d_renderer/build/lib/libGLEW.a \
  $SDK/Core/lib/linux/x86_64/libLive2DCubismCore.a -lGL -o physics_reference
```

Regenerate the fixtures with a model exported from `examples/tml/psd-input/tml.psd`:

```bash
cd src/test/resources/physics-reference
for v in fps60 nofps; do
  /path/to/physics_reference /path/to/tml.moc3 $v.physics3.json schedule.csv | grep -v '^Live2D' > $v.expected.csv
done
```
