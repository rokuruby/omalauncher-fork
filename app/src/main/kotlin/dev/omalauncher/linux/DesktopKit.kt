package dev.omalauncher.linux

import android.content.Context
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File

/**
 * This fork's PC-style setup for Linux: the labwc desktop, the phone's own adb, and Shizuku's rish.
 *
 * In plain terms: these are the files that were set up by hand on the phone, now shipped with the
 * launcher so a fresh install (or a reinstall) gets them back by itself:
 *
 * - Commands in /usr/local/bin, always kept at this version (Owned):
 *   `linux-desktop` starts the desktop as one window (the Desktop home tile runs it),
 *   `desktop-scale` makes everything on it smaller or bigger, `desktop-resolution` sets its size (1920x1080), `adb-phone` connects Linux to the
 *   phone's own adb (the ADB home tile runs it), `rish` runs Shizuku's adb-level shell,
 *   `gemma-tile-switch` is what the Gemma server tile runs,
 *   `phone-window` opens an Android app (or the Android home screen) as a window on the desktop,
 *   `phone-apps` is the list of Android apps the desktop's Phone button shows,
 *   `desktop-shortcut` puts a Linux or Android app's icon on the desktop,
 *   `desktop-display` is the Display button's list of resolutions and scales, for the phone's screen and a monitor,
 *   `agent-mcp` gives Claude Code and OpenCode the MCP servers in /usr/local/share/omalauncher/mcp-servers.json
 *   (also Owned), adding only the ones missing; it runs after either agent is installed.
 * - The desktop's settings in ~/.config (labwc window manager, waybar taskbar, fuzzel app list,
 *   the file manager that draws the desktop's icons, Chromium's flags), the "Add shortcut" icon in
 *   ~/Desktop and the app list, and a tiny icon theme for shortcuts. These are Seeded: written only where there is no file yet, and left alone
 *   once changed, so your own tweaks are never overwritten.
 *
 * The programs themselves (labwc, waybar, ...) come from pacman, as the Desktop tile's `requires` lists.
 */
class DesktopKit(
    private val context: Context,
    private val proot: Proot,
) {
    fun managed(): List<Managed> =
        COMMANDS.map { bin ->
            Managed.asset(context, "desktop/bin/$bin.sh", File(proot.rootfs, "usr/local/bin/$bin"), Policy.Owned, executable = true)
        } +
            Managed.asset(context, "desktop/share/mcp-servers.json", File(proot.rootfs, "usr/local/share/omalauncher/mcp-servers.json"), Policy.Owned) +
            CONFIGS.map { path ->
                Managed.asset(context, "desktop/config/$path", File(proot.home, ".config/$path"), Policy.Seeded)
            } +
            HOME.map { (asset, path) ->
                Managed.asset(context, "desktop/home/$asset", File(proot.home, path), Policy.Seeded, executable = path.endsWith(".desktop"))
            }

    private companion object {
        val COMMANDS = listOf("linux-desktop", "desktop-scale", "adb-phone", "rish", "gemma-tile-switch", "desktop-resolution", "phone-window", "phone-apps", "desktop-shortcut", "desktop-display", "agent-mcp")

        val CONFIGS =
            listOf(
                "labwc/rc.xml", "labwc/menu.xml", "labwc/autostart", "labwc/scale", "labwc/resolution",
                "waybar/config.jsonc", "waybar/style.css",
                "fuzzel/fuzzel.ini",
                // Draws the desktop's icons; opens a shortcut without asking "execute this file?" first.
                "pcmanfm-qt/default/settings.conf",
                // Linux runs as root here, where Chromium only starts with --no-sandbox.
                "chromium-flags.conf",
            )

        /**
         * Files elsewhere in the home folder, as asset under desktop/home to path. (Assets can't keep the folders'
         * own names: the APK packer leaves out anything starting with a dot, like .local.)
         */
        val HOME =
            listOf(
                "add-shortcut.desktop" to "Desktop/add-shortcut.desktop",
                "display.desktop" to "Desktop/display.desktop",
                "display.desktop" to ".local/share/applications/desktop-display.desktop",
                "icons/96x96/apps/desktop-display.png" to ".local/share/icons/DesktopIcons/96x96/apps/desktop-display.png",
                "desktop-shortcut.desktop" to ".local/share/applications/desktop-shortcut.desktop",
                "icons/index.theme" to ".local/share/icons/DesktopIcons/index.theme",
                "icons/README" to ".local/share/icons/DesktopIcons/README",
                "icons/96x96/apps/android-app.png" to ".local/share/icons/DesktopIcons/96x96/apps/android-app.png",
                "icons/96x96/apps/desktop-add.png" to ".local/share/icons/DesktopIcons/96x96/apps/desktop-add.png",
                "icons/96x96/emblems/emblem-important.png" to ".local/share/icons/DesktopIcons/96x96/emblems/emblem-important.png",
            )
    }
}
