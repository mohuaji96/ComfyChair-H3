# Contributing

## Ground rules

- This project is GPL-3.0. By opening a pull request you agree your contribution is released under
  the same licence.
- Keep changes focused. One topic per pull request; unrelated formatting churn makes review harder
  than it needs to be.
- Never commit signing material. `*.jks`, `*.keystore`, `keystore.properties` and
  `COMFYCHAIR_KEYSTORE_PROPERTIES` targets stay outside the repository.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Requirements: JDK 21, Android SDK 36. On Windows, the Android Gradle Plugin refuses project paths
containing non-ASCII characters — build from a path such as `C:\build\ComfyChairH3` if your checkout
lives under a Chinese-named folder.

## Tests

Unit tests live in `app/src/test`. Anything that can be tested without an Android device belongs
there: prompt rewriting, workflow parsing, cache policy, resolution maths. Tests are expected with a
bug fix — the regressions this project has hit were all reachable from a plain unit test.

## Style

- Kotlin, 4-space indent, trailing commas in multi-line argument lists.
- Comments explain *why*, not *what*; a comment that restates the code is noise.
- Prefer editing existing files over introducing new abstractions.
- Keep Chinese and English strings in step: `res/values/strings.xml` and
  `res/values-zh/strings.xml`.

## Reporting bugs

Include: app version (Settings → About), Android version, the ComfyUI server version if known, and
whether the build is debug or release. Debug builds collect in-memory logs you can copy from the
diagnostics screen.
