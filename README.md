<p align="center">
  <img src="omnitome_icon_512.png" alt="OmniTome" width="140">
</p>

<h1 align="center">OmniTome</h1>

<p align="center"><b>Your personal Calibre library, readable on any Android device.</b></p>

<p align="center">
  <a href="https://github.com/jlourcey/OmniTome/releases/latest"><b>⬇ Download the latest APK</b></a>
</p>

---

OmniTome is an Android e-reader that connects straight to your own **Calibre Content Server**. No third-party accounts and no cloud service: your books stay on your server and your phone.

## Features

- **Your Calibre library on your phone**: browse and download over your home network or remotely (OPDS)
- **Read Offline**: download a book to your phone and read it away from home Wi-Fi
- **Notes & position sync**: highlights, notes, and reading position made offline sync back to Calibre when you reconnect
- **Remove download**: clear a book off your phone, with a prompt to sync your notes and page first
- **Multiple libraries**: one login, switch between the Calibre libraries your account can see
- **EPUB and PDF** reading, powered by the Readium SDK
- **Customizable reading**: font, size, line spacing, margins, light/dark themes
- **Highlights** in four colors, typed annotations, bookmarks, and chapter navigation
- **Export** your highlights and notes as a PDF
- **Import** local EPUB/PDF files and upload them to Calibre
- **Library tools**: series grouping, search with tag filters, and a Favorites shelf
- **Tablet two-page layout**

## Requirements

- Android **8.0** or newer
- A **Calibre Content Server** you can reach from your phone ([Calibre](https://calibre-ebook.com) is free)
  - In Calibre: **Preferences → Sharing over the net → Start server** (default port `8080`)
  - To sync notes back to Calibre, your Calibre user needs **write access** to the library

## Install

1. On your Android phone, open the [latest release](https://github.com/jlourcey/OmniTome/releases/latest).
2. Under **Assets**, tap the `.apk` file to download it.
3. Open the downloaded file. If Android asks, allow **Install unknown apps** for your browser or Files app.
4. Tap **Install**, then open OmniTome.

To update, download the newer APK from the Releases page and install it over the old one. Your books and settings are kept.

## First-time setup

1. Open OmniTome and enter your Calibre server address, for example `http://192.168.1.100:8080`.
2. Enter your Calibre username and password (if your server uses one).
3. Pick which library to show. You can switch later in **Settings**.

## Building from source

1. Clone this repo and open it in **Android Studio**.
2. Copy `gradle.properties.template` to `gradle.properties`.
3. **Build → Make Project**, or run on an emulator/device.

Release builds need your own signing key in a `keystore.properties` file, which is not included in this repo.

## Privacy

OmniTome only talks to the Calibre server you set up. It has no ads, no analytics, and no third-party accounts. See [omnitome_privacy_policy.html](omnitome_privacy_policy.html).
