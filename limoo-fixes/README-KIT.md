# Limoo v0.3 kit

From the limoo-android repo root:

    python3 path/to/limoo-fixes/apply.py     # prints OK / SKIP per edit, safe to re-run
    python3 tools/preflight.py
    ./gradlew :app:testDebugUnitTest :app:assembleDebug
    git add -A && git commit -F path/to/limoo-fixes/COMMIT_MSG.txt && git push

## Release signing (optional)
Local: create `keystore.properties` in the repo root (git-ignored):

    storeFile=release.jks
    storePassword=...
    keyAlias=limoo
    keyPassword=...

CI: add repo secrets LIMOO_KEYSTORE_B64 (`base64 -w0 release.jks`), LIMOO_KEYSTORE_PASSWORD,
LIMOO_KEY_ALIAS, LIMOO_KEY_PASSWORD. Without them, release falls back to the debug key as before.

Note: switching from the debug key to a real key means existing installs must be uninstalled once
(Android refuses an update signed with a different key). Export a backup in Settings first.
