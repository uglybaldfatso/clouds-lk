# clouds-lk

A [Cloudstream](https://github.com/recloudstream/cloudstream) extension repository,
hosting plugin modules for the [CloudStream](https://github.com/recloudstream/cloudstream)
Android streaming app.

## Layout

Each top-level directory containing a `build.gradle.kts` is an independent plugin module
(automatically included by `settings.gradle.kts`).

```
<PluginModule>/
  build.gradle.kts             plugin metadata (version, description, status, tv types)
  src/main/AndroidManifest.xml
  src/main/kotlin/             provider / extractor sources
  src/test/kotlin/             unit tests
```

## Building

Requires JDK 17+.

```sh
./gradlew make              # produces <Module>/build/*.cs3 plugin packages
./gradlew makePluginsJson   # produces build/plugins.json (repository index)
./gradlew :<Module>:test    # unit tests
```

CI (`.github/workflows/build.yml`) builds both artifacts on every push to `main`
and publishes them to the `builds` branch.

## Installing in CloudStream

In the app: Settings → Extensions → Add repository → point it at this repository's
`repo.json` (raw URL of `repo.json` on the `main` branch), then install plugins
from the list.

## License

MIT — see [LICENSE](LICENSE).
