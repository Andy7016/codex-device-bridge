# Third-party components

The project has not yet selected its own open-source license. Third-party components retain their own copyright and license terms. Release attachments include supplementary license texts from the installed build dependencies; keep those notices when redistributing those components.

| Component | Used by | Upstream |
| --- | --- | --- |
| AndroidX / Compose / Kotlin / kotlinx.coroutines | Android UI and lifecycle | [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Material Design icons (path data) | Android navigation and action icons (`ic_nav_*`, `ic_refresh`, `ic_send`, `ic_expand_more`, `ic_codex` drawables; Apache-2.0) | [upstream](https://github.com/google/material-design-icons) |
| ZXing Android Embedded 4.3.0 | QR scanning | [upstream](https://github.com/journeyapps/zxing-android-embedded) |
| Java-WebSocket 1.6.0 | Android WSS | [upstream](https://github.com/TooTallNate/Java-WebSocket) |
| Python / Tcl/Tk | Windows interpreter and UI | [Python](https://www.python.org/), [Tcl/Tk](https://www.tcl.tk/) |
| cryptography 46.0.3 / cffi | Windows crypto bindings | [cryptography](https://github.com/pyca/cryptography), [cffi](https://foss.heptapod.net/pypy/cffi) |
| Pillow 12.0.0 / qrcode 8.2 | Windows QR generation/display | [Pillow](https://github.com/python-pillow/Pillow), [qrcode](https://github.com/lincolnloop/python-qrcode) |
| websockets 16.0 | Windows WSS | [upstream](https://github.com/python-websockets/websockets) |
| PyInstaller 6.16.0 | Windows packaging | [upstream](https://github.com/pyinstaller/pyinstaller) |
| ws 8.22.0 / Wrangler 4.156.0 | Node relay / Cloudflare tooling | [ws](https://github.com/websockets/ws), [workers-sdk](https://github.com/cloudflare/workers-sdk) |

Pinned inputs are in `android/app/build.gradle.kts`, `companion/requirements.txt` and `relay/package-lock.json`. The source tree and this summary are not a complete bill of materials for OS, SDK or transitive dependencies. Existing license resources inside APK/ZIP are preserved; supplementary notices are provided separately without modifying the already tested binaries.
