# CI and releases

- `ci.yml`: lint, unit tests and a debug build on PRs to `development` and on pushes to `development`/`main`.
- `version.yml`: bumps the root `VERSION` file (never by hand). `versionCode` = MAJOR·10000 + MINOR·100 + PATCH.
  For every new version on `main`, it tags `vX.Y.Z` and publishes a **signed, minified release APK**
  on GitHub Releases.

## Signing secrets (GitHub → Settings → Secrets → Actions)

| Secret | Value |
|---|---|
| `WANDERER_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `WANDERER_KEYSTORE_PASSWORD` | keystore password |
| `WANDERER_KEY_ALIAS` | key alias |
| `WANDERER_KEY_PASSWORD` | key password |

Create the keystore once and keep it safe. Losing it means users can't update the app:

```bash
keytool -genkeypair -v -keystore release.jks -alias wanderer -keyalg RSA -keysize 4096 -validity 10000
```

A local signed build reads the same variables (see `.env.example`) and runs `./gradlew assembleRelease`.
Without them, `assembleRelease` produces an unsigned APK.
