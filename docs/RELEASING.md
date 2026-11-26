# Releasing Varde

Publishing a GitHub release (on GitHub, or from Varde's own **Releases** page) runs `.github/workflows/release.yml`. It tests the app, builds a shrunk release APK signed with the release key, and attaches it to the release as `varde-<tag>.apk`. Anyone can then install it from the release, or from Varde's **Install a build** page.

## One-time setup: the release key

The quick way, on your own computer with a JDK, `openssl` and the GitHub CLI (`gh auth login`):

```sh
scripts/create-release-key.sh            # creates ~/varde-release-key and sets the four secrets
```

It refuses to replace an existing key. Back up the folder it creates. The manual steps below do the same thing.

The release key signs every release. Android only installs an update when it is signed with the same key as the installed app, so **keep the key and its passwords safe and backed up**. If the key is lost, no future release can update an installed copy.

1. Create the key on your computer (needs a JDK):

   ```sh
   keytool -genkeypair -v -keystore varde-release.keystore -alias varde \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Add four repository secrets (Settings → Secrets and variables → Actions):

   | Secret | Value |
   |---|---|
   | `VARDE_RELEASE_KEYSTORE_B64` | output of `base64 -w0 varde-release.keystore` (macOS: `base64 -i varde-release.keystore`) |
   | `VARDE_RELEASE_STORE_PASSWORD` | the keystore password |
   | `VARDE_RELEASE_KEY_ALIAS` | `varde` |
   | `VARDE_RELEASE_KEY_PASSWORD` | the key password |

The key is decoded to a temporary file during the build and deleted afterwards. It is never committed. Without these secrets the workflow fails with a message naming the missing one.

## Making a release

Draft a release with a tag like `v0.2.0` (Varde suggests the next version), then publish it. The APK appears on the release a few minutes later. The app's version name is the tag without the `v`.

## Debug and release builds

CI also builds a debug APK on every push and pull request, signed with a public debug key (`app/debug-keystore.b64`). Debug and release builds use the same app ID but different keys, so Android can't update one with the other: switching from a debug build to a release build means uninstalling the debug build first, which removes its local data and requires signing in again.

R8 mapping files for each release are kept as a workflow artifact, for reading crash stack traces.
