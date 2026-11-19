<div align="center">

  <img src="app/src/main/res/drawable-nodpi/ic_launcher_logo.png" alt="Antigravity Logo" width="88" height="88" style="border-radius: 18px;" />

  # Antigravity for Android

  <p align="center">
    <a href="README.md"><img src="https://img.shields.io/badge/English-007ACC?style=for-the-badge" alt="English" /></a>
    &nbsp;
    <a href="README.id.md"><img src="https://img.shields.io/badge/Bahasa%20Indonesia-DC3545?style=for-the-badge" alt="Bahasa Indonesia" /></a>
  </p>

  [![Android](https://img.shields.io/badge/Android-11.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android.com)
  [![Architecture](https://img.shields.io/badge/Architecture-ARM64-blue?style=flat-square)](https://arm.com)
  [![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
  [![Language](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
  [![License](https://img.shields.io/badge/License-AGPL_3.0-blue?style=flat-square)](LICENSE)

</div>

Antigravity for Android runs Google Antigravity AI coding agents on Android devices. It provides a standalone ARM64 Linux userspace with a native Jetpack Compose UI, enabling the agent to inspect workspace files, edit code, and run terminal commands (`git`, `bash`, `python`, `pkg`) directly on your phone without a PC.

## Features

- **Jetpack Compose UI**: Lightweight chat interface and code editor built for mobile.
- **Local Linux Userspace**: ARM64 environment with `bash`, `python3`, and CLI tools.
- **Official Agent Core**: Runs the Antigravity `language_server` daemon authenticated via Google OAuth 2.0 PKCE.
- **Package Manager (`pkg`)**: Built-in Bash/AWK package manager for installing CLI tools.
- **Background Daemon (`agy-daemon`)**: Background service handling cron tasks, file watchers, and notifications.
- **Skills & Plugins**: Fetch community skills and plugins directly from the catalogue.

## Related Repositories

- [KuatoDev/antigravity-bootstrap](https://github.com/KuatoDev/antigravity-bootstrap) — Pre-built Linux ARM64 rootfs extracted on first run.
- [KuatoDev/antigravity-catalogue](https://github.com/KuatoDev/antigravity-catalogue) — Skills and plugins catalogue. Submit a pull request here to share custom skills :)

## Requirements

- Android 11+ (`arm64-v8a`)
- Storage & notification permissions

## Community

- **Telegram Channel**: [t.me/vernkuato](https://t.me/vernkuato)
- **Telegram Group**: [t.me/kuatodevprojects](https://t.me/kuatodevprojects)

## Credits

- **[Werdik (werdio325-png)](https://github.com/werdio325-png)** — `pkg`, `agy-daemon`, and userspace scripts.
- **[Termux](https://github.com/termux)** — Android ARM64 rootfs base and `libtermux-exec`.
- **[Google DeepMind](https://deepmind.google)** — Antigravity core `language_server`.

---

<div align="center">
  <sub>Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).</sub>
</div>
