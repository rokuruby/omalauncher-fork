# omalauncher (unofficial fork)

This is an unofficial fork which was vibe coded with AI to suit my customization needs.

I'm not responsible for any bugs or security issues, as I'm just an average guy with some technical knowledge.

You can find the original project here: https://github.com/SimonSchubert/omalauncher

**Disclaimer:** if you are against AI, then you're welcome not to use it, but you're also welcome to show me how it's done and manually code the features that I need properly in your own fork.

## Features that were vibe coded

- File read and write access, so that the coding agents and voice chat can work with and edit my Obsidian markdown vault.
- Access to Android widgets, because I still prefer some of them.
- Fixing the voice chat: when I set it to a foreign language, it still detected my spoken language as English. I want to use the voice chat as a language tutor focusing on Chinese.
- A local, on-device Gemma 4 LLM to power the coding agent and voice chat, in case I don't have internet access to a cloud LLM.
- Making sure Gemma 4 runs GPU accelerated on my outdated Snapdragon 8 Gen 1, because the latest Google LiteRT has issues supporting my older GPU.

## Risks you should know about

- **All files access:** the launcher asks Android for permission to read and change every file in your phone's shared storage (Download, Documents, photos and so on). The Linux system inside it, and any coding agent or program you run there, can then read, change or delete those files. Only install it if you're OK with that, and don't keep passwords, keys or other secrets in shared storage.
- **Gemma 4 local server:** the launcher runs a small AI server on the phone (127.0.0.1, port 9379) with no password. Other devices can't reach it, but any app on the phone, or a website open in its browser, can send it requests. That uses your battery and memory, and a very large request can crash the launcher. The server can't read your files or conversations. The 2.6 GB model is downloaded from Hugging Face and only checked by its size, not a checksum.
