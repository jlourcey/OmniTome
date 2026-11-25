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

## What's new in 2.0

- **Star ratings** — rate a book from its long-press menu, or when you finish it. Ratings sync both ways with Calibre's own Rating, and 5 stars shows the fire badge on the cover
- **Times Read** — every finish adds one to a "Times Read" column in Calibre
- **Smart Shelves** — save a Calibre search as a shelf that fills itself, right next to your own shelves
- **Read Tracking settings** — choose which Calibre columns OmniTome uses for Read, Date Read, Times Read and Shelves
- **Page slider** — drag to jump anywhere in a book
- **Built-in dictionary**, **search inside books**, **read aloud**, **custom shelves**, **MOBI/AZW3/comic support**, a new **PDF viewer**, **cover badges** and a **Continue Reading widget**

## Features

- **Your Calibre library on your phone**: browse and download over your home network or remotely (OPDS)
- **Read Offline**: download a book to your phone and read it away from home Wi-Fi
- **Notes & position sync**: highlights, notes, and reading position made offline sync back to Calibre when you reconnect
- **Remove download**: clear a book off your phone, with a prompt to sync your notes and page first
- **Multiple libraries**: one login, switch between the Calibre libraries your account can see
- **Read status**: finished books are faded with a ✓ on your shelves; mark books read or unread, synced with a "Read" column in Calibre
- **Reading streak**: a days-in-a-row counter that stays the same on all your devices, plus a "Finished this year" shelf of the books you've completed
- **Automatic sync**: notes, reading position and Read status go to Calibre by themselves once your phone can reach the server again
- **Book you're reading is saved offline automatically**, and removed once you finish it
- **Built-in dictionary**: select a word and tap Define for an offline definition card (WordNet), with an optional Wikipedia lookup
- **Page X of Y and time left** in the chapter and the book, learned from your own reading speed
- **Page slider**: drag to jump anywhere in the book
- **Custom shelves** alongside Favorites; syncs with a "Shelves" column in Calibre if the library has one
- **Smart Shelves**: save any Calibre search (for example `#read:false` or `rating:>=4`) as a shelf that fills itself; refreshed on every sync and usable offline
- **Star ratings**: rate books 1–5 stars from the long-press menu or when you finish them; synced both ways with Calibre's own Rating
- **Cover badges**: New, Read, and a fire badge for 5-star favorites (can be turned off in **Settings → App Appearance**)
- **Times Read**: every finish adds one to a "Times Read" column in Calibre, so re-reads are counted
- **Read Tracking settings**: pick which of your Calibre columns mean Read, Date Read, Times Read and Shelves
- **MOBI, AZW3 and comics (CBZ/CBR)**: converted to EPUB by your Calibre server the first time you open them
- **Search inside books** saved on your phone, with results by book and chapter
- **Read aloud** with speed control and a sleep timer
- **Full-screen reading**, volume-key page turns, a dyslexia-friendly font (OpenDyslexic) and a Continue Reading home-screen widget
- **EPUB and PDF** reading (EPUB via the Readium SDK, PDF via Android's built-in PDF engine)
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
  - To sync notes back to Calibre (and to convert MOBI/AZW3 books), your Calibre user needs **write access** to the library
  - OmniTome adds one small entry per user to your library, titled **OmniTome Reading Log (username)**. It keeps your reading streak in sync across devices, so please leave it in place
  - Smart Shelves are made by Calibre's own search, so creating or editing one needs a connection to your server; after that they work offline
  - To sync Read status, add a **Yes/No** column with lookup name `read` in Calibre (**Preferences → Add your own columns**). An optional **Date** column named `date_read` records when you finished each book, an optional **Integers** column named `times_read` counts how many times you've read it, and an optional **Comma separated text, like tags** column named `shelves` keeps your shelves in Calibre. Already use other names? Pick your columns under **Settings → Read Tracking**

## Install

1. On your Android phone, open the [latest release](https://github.com/jlourcey/OmniTome/releases/latest).
2. Under **Assets**, tap the `.apk` file to download it.
3. Open the downloaded file. If Android asks, allow **Install unknown apps** for your browser or Files app.
4. Tap **Install**, then open OmniTome.

To update, download the newer APK from the Releases page and install it over the old one. Your books, notes, shelves and settings are kept (this includes updating from 1.x to 2.0).

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
