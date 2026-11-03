# omalauncher (Rokuruby's unofficial fork)

This is my unofficial fork of Omalauncher based on version 0.17, later updated to 0.18, AI was used to vibe code my customized needs(deal with it). All credits go to @simonschubert on X/Twitter & ToS0 for their fork of Android widgets on Omalauncher.

I'm not responsible for any bugs or security issues, as I'm just an average guy with limited technical knowledge.

You can find the original project here: https://github.com/SimonSchubert/omalauncher

**Disclaimer:** if you are against AI, then you're welcome not to use it, but you're welcome to go through my added features and manually code them in your own fork. Show me how its done instead of criticizing my slop.

This fork will never be on the Play Store as it violates Big G's policies. Pray they don't take sideloading away.

## Features that were vibe coded by me to suit my needs

- File read and write access, so that the coding agents and voice chat can work with and edit my Obsidian markdown vault.
- Access to Android widgets, because I still prefer using some of the Android widgets in my home screens.
- Fixing the voice chat foreign language funactionality: when I set it to a foreign language, it still detected my spoken language as English. I want to use the voice chat as a language tutor focusing on Chinese.
- A local, on-device Gemma 4 LLM coding agent and voice chat, in case I don't have internet access to a cloud LLM.
- Making sure Gemma 4 runs GPU accelerated on my outdated Snapdragon 8 Gen 1, because the latest Google LiteRT has issues supporting my older GPU.
- Tweaking & reducing Gemma 4's Opencode tokens of instructions for faster response.

## Risks you should know about before using it

- **All files access:** the launcher asks Android for permission to read and change every file in your phone's shared storage (Download, Documents, photos and so on). The Linux system inside it, and any coding agent or program you run there, can then read, change or delete those files. Only install it if you're OK with that, and don't keep passwords, keys or other secrets in shared storage.
- **Gemma 4 local server:** the launcher runs a small AI server on the phone (127.0.0.1, port 9379) with no password. Other devices can't reach it, but any app on the phone, or a website open in its browser, can send it requests. That uses your battery and memory, and a very large request can crash the launcher. The server can't read your files or conversations. The 2.6 GB model is downloaded from Hugging Face and only checked by its size, not a checksum and has the risk of being replaced by a malicious version.
