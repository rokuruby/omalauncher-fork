# omalauncher (Rokuruby's unofficial fork)

This is my unofficial fork of Omalauncher based on version 0.17, later updated to 0.18, 0.19.0, 1.0.0 and now 1.4.0, AI was used to vibe code my customized needs(deal with it). All credits go to @simonschubert on X/Twitter & ToS0 for their fork of Android widgets on Omalauncher..

I'm not responsible for any bugs or security issues, as I'm just an average guy with limited technical knowledge.

You can find the original project here: https://github.com/SimonSchubert/omalauncher

**Disclaimer:** if you are against AI, then you're welcome not to use it, but you're welcome to go through my added features and manually code them in your own fork. Show me how its done instead of criticizing my slop.

This fork will never be on the Play Store as it violates Big G's policies. Pray they don't take sideloading away.

## Features I added (vibe coded to suit my needs)

- **AI that works with my files:** the coding agents and voice chat can read and edit files on the phone, like my Obsidian notes.
- **Android widgets** on the home screen, next to the launcher's own.
- **Voice chat in other languages:** fixed so I can use it as a Chinese tutor.
- **Offline AI:** Gemma 4 runs on the phone itself, as a voice chat and a coding agent, with no internet needed. It uses the GPU, even on older phones like mine, and gives its memory back as soon as I'm done with it.
- **Voice typing in the terminal:** a mic key, so I can talk to any coding agent instead of typing.
- **An alarm widget the AI can set:** up to five alarms with a reason each, set from voice chat or the terminal.
- **Desktop mode:** like Samsung DeX for any phone. A tile opens a Linux desktop with windows, a taskbar and real desktop apps like Chromium and LibreOffice, with GPU acceleration.
- **Monitor support:** a sharp picture on a monitor, TV or glasses, with the phone as a touchpad and a menu to change resolution.
- **Android apps on the desktop:** each in its own window next to Linux apps, plus shortcuts on the desktop.
- **AI that can control the phone:** through adb and Shizuku, coding agents can open apps and change settings when I ask.
- **Coding agents set up for me:** MCP servers for a web browser (Playwright) and maps (OpenStreetMap) are added to Claude Code and OpenCode automatically.

## How to use the Linux desktop

Think of it as Samsung DeX for non-Samsung phones. It runs on older phones without a desktop mode (mine is on Android 14), but it is built on workarounds, so it is slower than the real thing.

It runs desktop-class apps: the full Chromium browser with extensions and developer tools, and LibreOffice for documents, spreadsheets and slides. Install them from a terminal with `pacman -S chromium libreoffice-still`, and they show up under **Apps**.

What to install first: tap the **Desktop** tile on the home screen and let it install what it asks for (the desktop programs, plus `scrcpy` and `android-tools` for the Android app windows).

- **Open and close it:** tap the Desktop tile; tap it again, or the red Exit button on the taskbar, to close it.
- **Open a Linux app:** tap **Apps** on the taskbar and type a few letters.
- **Open an Android app in a window:** first connect adb (on the ADB tile: turn on Wireless debugging in Android's Developer options, pair once, then tap Connect). Then tap **Phone** on the taskbar and pick the app. "Home screen" at the top of the list opens an empty phone window. Right-click **Phone** to close all the phone windows. In a phone window, right-click is Back and middle-click is Home.
- **Phone windows too small or too big inside:** run `phone-window zoom 1.3` (any number; 1 is the same as on the phone) and open the window again.
- **Put an app on the desktop:** double-click **Add shortcut**, pick a Linux or Android app, and its icon appears. To remove an icon, right-click it and choose Delete.
- **Change resolution and scale:** tap **Display** on the taskbar (or the Display icon on the desktop) and pick from the list. Resolution is how many pixels the picture has; scale is how big things are drawn in it. Scale 1 is the sharpest; smaller numbers fit more on screen but make text softer. The phone's screen and a monitor each remember their own choice. From a terminal, `desktop-scale 0.7` and `desktop-resolution 1920x1080` do the same for the phone's screen.
- **With a monitor:** plug it in and open the desktop. It shows on the monitor, and the phone shows a touchpad: drag to move the pointer, tap to click, two fingers to scroll or right-click, tap then drag to hold the button down. Move a mouse once over the touchpad screen to use it instead. **Show on phone** puts the desktop back on the phone's screen; unplugging the monitor does the same.
- **Monitor resolutions:** "auto" uses the monitor's own resolution, or 1440p on a monitor with more. In the Display list, a size marked "monitor signal" is one the monitor accepts directly, the way a PC sends it: the monitor switches to it. Any other size is drawn and stretched over the whole screen. After a change the desktop asks "Keep this resolution?"; without an answer within 15 seconds it goes back to the one before, in case the screen went black.

Limits I know of: sound from Android app windows plays on the phone, not through the window. The same Android app can't be open in two windows. On the monitor the picture is at most 1440p, because the phone draws Linux without its graphics chip and 4K was too much. All Android app shortcuts on the desktop share one green phone icon.

## Risks you should know about before using it

- **All files access:** the launcher asks Android for permission to read and change every file in your phone's shared storage (Download, Documents, photos and so on). Linux sees all of it at `/sdcard`. The Linux system inside it, and any coding agent or program you run there, can then read, change or delete those files. Only install it if you're OK with that, and don't keep passwords, keys or other secrets in shared storage.
- **adb and the Android app windows:** the phone windows on the desktop work through adb, the same developer connection a computer uses to control a phone. While it is connected, any program in Linux (and any coding agent) can open apps, read the screen and change settings on the phone. Turn Wireless debugging off when you don't use it.
- **Desktop shortcuts open without asking:** so that a double-click on a desktop icon opens the app straight away, the desktop's file manager no longer asks "execute this file?". That also goes for a script you double-click in the file manager.
- **Gemma 4 local server:** the launcher runs a small AI server on the phone (127.0.0.1, port 9379) with no password. Other devices can't reach it, but any app on the phone, or a website open in its browser, can send it requests. That uses your battery and memory, and a very large request can crash the launcher. The server can't read your files or conversations. The 2.6 GB model is downloaded from Hugging Face and only checked by its size, not a checksum and has the risk of being replaced by a malicious version.

## From the original launcher: the `omalauncher` command

Since 1.0.0, scripts and agents in Linux reach the phone through `omalauncher` (`phone` still works as another name):

```sh
omalauncher location          # approximate {lat, lon, accuracy, time}
omalauncher calendar 7        # the events of the next 7 days
omalauncher battery           # level, charging, temperature, health...
omalauncher notify "build done" "all tests passed"
omalauncher share report.pdf  # Android's share sheet
omalauncher version           # the launcher's version and the commands it has
```

`omalauncher --help` has the rest. In this fork, Linux sees the whole phone storage at `/sdcard` thanks to all files access, so phone folders don't need picking one by one.

## Versions

- The APK's name shows the original launcher's version it's built on (now 1.4.0), the build number and its newest change, e.g. `omalauncher-1.0.0-b60-nowatcher.apk`. Up to build 59 the name listed every feature, until it outgrew the 255 characters a file name may have.
- Build 60 has: androidwidgets, fileaccess, chinesevoice, localgemma4, gpufallback, fastcode, voicefiles, serverswitch, alarmtasks, mic, alarmautooff, alarmtapfix, gemmaseparate, alarmrestartfix, androidsystem (Android's system folders shared with Linux, for Shizuku's `rish`), desktopkit (the Linux desktop, adb and rish setup and my tiles), linkerconfig, rishapk, centerdesktop (a Linux window shrunk to fit is centred) and nowatcher (no background resolution check). Its rollback, build 61 (`omalauncher-1.0.0-b61-rollback.apk`, branch `rollback-build58`), is build 58: the same without nowatcher.
- Build 62 adds: monitor (with a monitor plugged in, the Linux desktop is drawn straight onto it at up to 1440p instead of being a blurry copy of the phone's screen; the phone becomes its touchpad and catches a mouse; "Show on phone" on the touchpad screen puts it back).
- Build 64 adds: desktopapps (the Android app list and phone windows on the desktop, desktop icons with "Add shortcut", and their settings, all shipped with the launcher so another phone or a fresh install gets them too). Its rollback, build 65 (`omalauncher-1.0.0-b65-rollback.apk`, branch `rollback-build62`), is build 62.
- Build 66 adds: displaymenu (on a monitor the launcher switches it to a real signal of the chosen resolution, picked by itself or from the new Display button's list of resolutions and scales; the phone's screen and the monitor keep separate settings, set again whenever the desktop moves between them). Its rollback, build 67 (`omalauncher-1.0.0-b67-rollback.apk`, branch `rollback-build64`), is build 64.
- Build 68 adds: desktopgpu (with GPU acceleration on in Settings › Linux, apps on the Linux desktop use the GPU too; before, they could stay on the CPU when the desktop was started from a home tile). Its rollback, build 69 (`omalauncher-1.0.0-b69-rollback.apk`, branch `rollback-build66`), is build 66.
- Build 70 (`omalauncher-1.4.0-b70-upstream140.apk`, branch `upstream-1.4.0`) is build 68 plus the small desktop fixes after it, merged with the original launcher's 1.4.0 (faster Linux commands, keyboard suggestions in terminals, icon packs, 3D widgets, pacman fix and more). Its rollback, build 71 (`omalauncher-1.0.0-b71-rollback.apk`, branch `rollback-build68`), is the 1.0.0 fork as on `desktop-gpu`.
- Build 72 adds: Gemma frees its memory as soon as nothing uses it (the server tile switched off, or Voice closed for a minute), instead of 10 minutes later; and `rish` works with the original launcher's fast system calls on.
- Build 74 adds: `agent-mcp`, which gives Claude Code and OpenCode MCP servers (Playwright, OpenStreetMap) after they're installed (and fixes an OpenCode config that hid them). The public release of it is build 77 (`omalauncher-1.4.0-b77-public.apk`): build 74 with a general MCP list (Playwright and OpenStreetMap) instead of my own. **This is the current build.**
- My own rollback is the 1.0.0 fork from before the 1.4.0 merge (build 76); it isn't published here.
- Each build has a higher version code than the one before, so it installs over it. The 1.0.0 build is version code 43.
- If 1.0.0 gives me trouble, the rollback APK (`omalauncher-0.19.0-...-alarmtapfix-rollback.apk`, version code 44, from the `rollback-0.19.0` branch) is the 0.19.0 build with all the features above, and it installs over 1.0.0. Widgets that use 1.0.0-only things (`fps:`, `home.home-page`) and the `omalauncher` command stop working on it.
- The older 0.18.0 rollback (version code 41, branch `rollback-0.18.0`) can't be installed over builds 42 and up any more.
