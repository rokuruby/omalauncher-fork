package dev.omalauncher.linux

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.LauncherData
import dev.omalauncher.PhoneStorage
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import kotlin.concurrent.thread

/** Paths and process launching for the proot'd Arch rootfs. */
class Proot(
    private val context: Context,
) {
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val filesDir: File = context.filesDir
    val rootfs = File(filesDir, "arch")
    val tmpDir = File(filesDir, "tmp")

    /** Bound to XDG_RUNTIME_DIR inside the guest; holds the Wayland socket and the bridges' FIFOs. */
    val runtimeDir = File(filesDir, "xdg")
    val waylandSocket = File(runtimeDir, "wayland-0")
    val keymap = File(filesDir, "keymap.xkb")

    /** The guest user's home, /root, and the launcher's places in it. */
    val home = File(rootfs, "root")
    val applications = File(home, ".local/share/applications")
    val launcherConfig = File(home, ".config/omalauncher")

    /**
     * A listable stand-in for /sys: apps may only read /sys/devices/system/cpu,
     * and tools like btop abort when they can't walk the directories above it.
     */
    private val sysDir = File(filesDir, "sys")

    /** Stand-ins for /proc files Android hides from apps. */
    val procShim = ProcShim(File(filesDir, "proc"))

    /** How guest apps render OpenGL and Vulkan: in software, or on the GPU once [Gpu] turns it on. */
    @Volatile var graphicsEnv: List<String> = SOFTWARE_ENV

    private val prefs = LauncherData.prefs(context)

    /**
     * What makes Linux's system calls fast: proot keeping itself and programs making many syscalls on one fast
     * CPU (patches/proot/0002), and the fast lane, [fastLane]. On unless the user turned it off; applies to what
     * starts after a change.
     */
    var cpuPinning by mutableStateOf(prefs.getBoolean(CPU_PINNING, true))
        private set

    fun changeCpuPinning(on: Boolean) {
        cpuPinning = on
        prefs.edit().putBoolean(CPU_PINNING, on).apply()
    }

    /**
     * The fast lane library (patches/proot/fastpath): preloaded into guest programs, it answers stat, access,
     * readlink and read-only open for plain rootfs paths itself, without a stop in proot (patches/proot/0003).
     * Built by scripts/fetch-proot.sh next to proot and copied into the guest by [managed], so the two always
     * come from the same APK; a build without it runs as before.
     */
    private val fastLaneSource = File(nativeDir, "libfastpath.so")
    private val fastLane = File(rootfs, FAST_LANE.removePrefix("/"))

    /** Launcher data the guest sees in its home, kept outside the rootfs; see [LauncherData]. */
    private val dataBinds =
        listOf(
            "$GUEST_HOME/.local/share/omarchy" to LauncherData.omarchyDir(context),
            "$GUEST_HOME/.config/omalauncher/widgets" to LauncherData.widgetsDir(context),
            "$GUEST_HOME/.config/omalauncher/style" to LauncherData.styleDir(context),
            "$GUEST_HOME/.config/omalauncher/home" to LauncherData.homeDir(context),
            "$GUEST_HOME/.config/omarchy/themes" to LauncherData.userThemesDir(context),
            "$GUEST_HOME/.config/omarchy/backgrounds" to LauncherData.userBackgroundsDir(context),
        )

    /**
     * Android's Chinese, Japanese and Korean font, which the guest has none of (Arch's is ~300 MB).
     * Absent on some devices; the guest then goes without, as before.
     */
    private val cjkFont = File("/system/fonts/NotoSansCJK-Regular.ttc")

    private val fixedBinds = listOf("/tmp" to tmpDir, "/dev/shm" to tmpDir, GUEST_RUNTIME_DIR to runtimeDir) + dataBinds

    /** The phone's shared storage, as far as Android lets the launcher see it ([PhoneStorage]). */
    private val storageBinds = if (PhoneStorage.supported()) listOf(PhoneStorage.GUEST to PhoneStorage.root) else emptyList()

    /** Host directories bound over guest paths by [command]. */
    private val binds = fixedBinds + storageBinds

    /**
     * Android's own system folders, shown inside Linux at the same paths.
     *
     * In plain terms: Linux here lives in a sealed box and normally can't see Android's programs at all.
     * Some tools, like Shizuku's `rish` (a terminal with adb-level powers that keeps working without
     * Wi-Fi), are Android programs: to start them, Linux has to reach Android's program launcher
     * (/system/bin/app_process) and everything it loads: the core system (/system, /system_ext, /product),
     * the phone maker's drivers (/vendor, /odm), Android's updatable parts (/apex), the map that tells
     * Android's loader where libraries are (/linkerconfig), and Android's precompiled Java code
     * (/data/dalvik-cache), and the installed apps' APKs (/data/app: rish loads its shell from Shizuku's
     * own APK, which it finds there). This shares those folders into the box, the way Termux's proot-distro does.
     *
     * Safety: Android itself still decides what the launcher may read or change; nothing here grants new
     * permissions, and these folders are read-only to apps anyway. They are kept out of [binds] on purpose,
     * so widgets and file pickers (which check [guestVisible]) still can't reach them.
     * Folders this phone doesn't have are skipped. /linkerconfig is handled apart, by [linkerConfig].
     */
    private val androidSystemBinds =
        listOf("/system", "/system_ext", "/product", "/vendor", "/odm", "/apex", "/data/dalvik-cache", "/data/app")
            .filter { File(it).exists() }

    /**
     * A copy of Android's /linkerconfig, bound there in the guest.
     *
     * Android lets apps read the files in /linkerconfig but not look up the folder itself (SELinux grants
     * only "search" on it), and proot needs that lookup to bind a folder, so it silently dropped
     * `-b /linkerconfig` and Android programs failed to start (libnativeloader.so not found). The files
     * can still be opened by name, so [prepare] copies the ones Android's loader reads into our own
     * folder. Android writes them once per boot, and the copy is renewed every time Linux starts.
     */
    private val linkerConfig = File(filesDir, "linkerconfig")

    private fun copyLinkerConfig(): Boolean {
        LauncherData.deleteTree(linkerConfig)
        for (name in LINKER_CONFIG_FILES) {
            runCatching {
                val target = File(linkerConfig, name)
                target.parentFile?.mkdirs()
                File("/linkerconfig", name).inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }.onFailure { File(linkerConfig, name).delete() }
        }
        return File(linkerConfig, "ld.config.txt").isFile
    }

    @Volatile private var linkerConfigBinds: List<String> = emptyList()

    /**
     * The settings Android's program launcher needs to start (where its core Java code is, and so on),
     * copied from the launcher's own environment into a small file Linux can read:
     * /run/user/0/android-env. Linux tools source it only when they start an Android program, so
     * ordinary Linux programs never see these settings.
     */
    private fun writeAndroidEnv() {
        val names =
            listOf(
                "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
                "ANDROID_RUNTIME_ROOT", "ANDROID_ASSETS", "ANDROID_STORAGE", "BOOTCLASSPATH",
                "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH",
            )
        val lines = names.mapNotNull { name -> System.getenv(name)?.let { "export $name='${it.replace("'", "")}'" } }
        // Shizuku asks "allow this app?" by package name; this is the launcher's own.
        val file = File(runtimeDir, "android-env")
        runCatching { file.writeText((lines + "export RISH_APPLICATION_ID='${context.packageName}'").joinToString("\n", postfix = "\n")) }
    }

    /**
     * Where an absolute guest path lives on the Android side, following the
     * directory binds in [command]: /tmp in the guest isn't rootfs/tmp.
     */
    fun hostFile(guestPath: String): File {
        for ((guest, host) in binds) {
            if (guestPath == guest || guestPath.startsWith("$guest/")) return File(host, guestPath.removePrefix(guest).removePrefix("/"))
        }
        return File(rootfs, guestPath.removePrefix("/"))
    }

    /** Where the guest can see: the rootfs and the binds, as real paths. */
    private val visible by lazy { (listOf(rootfs) + binds.map { it.second }).map { it.canonicalFile } }

    /** Whether [real], a canonical host file, is one the guest can see, rather than the launcher's own. */
    fun guestVisible(real: File) = visible.any { real.startsWith(it) }

    /** The empty directory in the rootfs that the phone's storage is bound over. */
    private val storageMount get() = File(rootfs, PhoneStorage.GUEST.removePrefix("/"))

    /** The guest's files as it sees them; see [GuestFs]. */
    val guestFs by lazy { GuestFs(::hostFile, ::guestVisible) }

    /**
     * Where a guest path that Linux or a widget names is on the Android side, as the guest sees it:
     * `..` stops at /, links are followed inside the guest. Null for a link in a loop or out of the
     * guest, so no path reaches the launcher's own files ([hostFile] is for paths of our own).
     */
    fun guestFile(guestPath: String): File? = runCatching { guestFs.host(guestPath) }.getOrNull()

    /** The guest path of a host file, the reverse of [hostFile]; null if the guest can't see it. */
    fun guestPath(host: File): String? {
        for ((guest, dir) in binds) {
            if (host.startsWith(dir)) return (guest + "/" + host.relativeTo(dir).path).trimEnd('/')
        }
        return if (host.startsWith(rootfs)) "/" + host.relativeTo(rootfs).path else null
    }

    /**
     * Resets what only lives as long as our process (guest processes die with
     * it): /tmp, the runtime dir, a stale pacman lock. Writes the host-side
     * stand-ins every guest command needs. Call once, before any guest runs.
     */
    fun prepare() {
        discardTmp()
        LauncherData.deleteTree(runtimeDir)
        runtimeDir.mkdirs()
        // proot skips a bind whose host directory is missing, and the guest would then write into the rootfs.
        // The empty mount points make the bound directories show up in listings of their parents.
        for ((guest, host) in dataBinds) {
            host.mkdirs()
            if (rootfs.isDirectory) File(rootfs, guest.removePrefix("/")).mkdirs()
        }
        // Its mount point, so / lists it; Android 10 has none (an empty one is all that's left there).
        if (rootfs.isDirectory) storageMount.let { if (PhoneStorage.supported()) it.mkdirs() else it.delete() }
        File(rootfs, "var/lib/pacman/db.lck").delete()
        // Empty folders where Android's system folders appear (see androidSystemBinds), so `ls /` lists them,
        // plus the file of settings Android programs need to start.
        if (rootfs.isDirectory) for (guest in androidSystemBinds) File(rootfs, guest.removePrefix("/")).mkdirs()
        linkerConfigBinds =
            if (copyLinkerConfig()) {
                if (rootfs.isDirectory) File(rootfs, "linkerconfig").mkdirs()
                listOf("-b", "${linkerConfig.path}:/linkerconfig")
            } else {
                emptyList()
            }
        writeAndroidEnv()
        // An empty stand-in, so fontconfig lists the bound font when it reads the directory.
        if (rootfs.isDirectory) {
            val stub = File(rootfs, GUEST_CJK_FONT.removePrefix("/"))
            if (cjkFont.isFile) {
                stub.parentFile?.mkdirs()
                stub.createNewFile()
            } else {
                stub.delete()
            }
        }
        for (sub in listOf("devices/system/cpu", "class", "block", "bus", "dev", "fs", "kernel")) File(sysDir, sub).mkdirs()
        // The stand-in was bound over /usr/bin/bwrap, which left proot's empty mount point there; no package owned
        // it, so pacman refused to install bubblewrap and everything needing it (glycin, so GTK apps). A real
        // bwrap is never empty.
        File(rootfs, "usr/bin/bwrap").takeIf { it.isFile && it.length() == 0L }?.delete()
        File(filesDir, "bwrap-shim").delete()
    }

    /** Old /tmp contents waiting to be deleted; see [discardTmp]. */
    private val discardedTmp = File(filesDir, "tmp-discarded")

    /**
     * Starts the guest with an empty /tmp. A day of agent work can leave hundreds of thousands of files there (a
     * cloned repo with node_modules), and deleting them takes long enough on a phone that Linux looked uninstalled
     * meanwhile. So the old one moves aside and is deleted in the background, along with any a killed process left.
     */
    private fun discardTmp() {
        discardedTmp.mkdirs()
        if (tmpDir.exists() && !tmpDir.renameTo(File(discardedTmp, System.nanoTime().toString()))) LauncherData.deleteTree(tmpDir)
        tmpDir.mkdirs()
        thread(name = "tmp-discard", isDaemon = true) {
            runCatching { LauncherData.deleteTree(discardedTmp) }.onFailure { Log.w("proot", "old /tmp", it) }
        }
    }

    /**
     * The bwrap stand-in (assets/bwrap-shim.sh): user namespaces are unavailable under proot, so the real one
     * fails. glycin and Codex look for bwrap on PATH, where /usr/local/bin precedes the package's /usr/bin.
     */
    fun managed(): List<Managed> =
        listOf(Managed.asset(context, "bwrap-shim.sh", File(rootfs, "usr/local/bin/bwrap"), Policy.Owned, executable = true)) +
            if (fastLaneSource.isFile) listOf(Managed(fastLane, Policy.Owned) { fastLaneSource.readBytes() }) else emptyList()

    private fun command(script: String): List<String> {
        return listOf(
            File(nativeDir, "libproot.so").path,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r",
            rootfs.path,
        ) + procShim.binds.flatMap { (guest, host) -> listOf("-b", "${host.path}:$guest") } +
            listOf(
                "-b", "/dev",
                "-b", "/proc",
                "-b", "${sysDir.path}:/sys",
                "-b", "/sys/devices/system/cpu",
            ) + (if (cjkFont.isFile) listOf("-b", "${cjkFont.path}:$GUEST_CJK_FONT") else emptyList()) +
            binds.flatMap { (guest, host) -> listOf("-b", "${host.path}:$guest") } +
            // Android's system folders at their own paths, so Android programs can start from Linux.
            androidSystemBinds.flatMap { listOf("-b", it) } + linkerConfigBinds +
            listOf(
                "-w", GUEST_HOME,
                "/usr/bin/env", "-i",
                "HOME=$GUEST_HOME",
                "USER=root",
                "LANG=C.UTF-8",
                // Android's zone name (e.g. Europe/Berlin); the guest otherwise runs on UTC.
                "TZ=${java.util.TimeZone.getDefault().id}",
                "TERM=xterm-256color",
                // mise-installed tools (coding agents, node) live in the last two.
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/root/.local/bin:/root/.local/share/mise/shims",
                // As (fake) root, GNU tar restores directory modes with fchmodat(AT_SYMLINK_NOFOLLOW),
                // which fails with ENOENT under proot on newer kernels and fails the extraction (mise.run).
                "TAR_OPTIONS=--no-same-permissions",
                // mise's default npm installer hardlinks packages out of its store in ~/.cache/mise; under proot
                // (--link2symlink) those become symlinks into the store, and `mise cache clear` after installs and
                // updates then breaks them (Grok: "no platform binary installed"). npm copies the files.
                "MISE_NPM_PACKAGE_MANAGER=npm",
                // Coding agents come from mise and update with the system (SystemUpdates); updating themselves
                // too would bypass that and leave a second copy on a phone that's short on storage.
                "DISABLE_AUTOUPDATER=1",
                "OPENCODE_DISABLE_AUTOUPDATE=1",
                "GROK_DISABLE_AUTOUPDATER=1",
                // Claude Code's cross-session messaging (its internal switch, undocumented): proot sends opens of
                // /proc/*/uid_map to /dev/null, for bwrap's writes, so Claude reads an empty map, takes it for a user
                // namespace without one, and greets every start with a warning that messaging is off.
                "CLAUDE_CODE_HARBOR_KITE=0",
                "BROWSER=xdg-open",
                // The launcher's editor; yazi and git open files with it. Its shim falls back to micro.
                "EDITOR=$EDIT_COMMAND",
                "VISUAL=$EDIT_COMMAND",
                "XDG_RUNTIME_DIR=$GUEST_RUNTIME_DIR",
                "WAYLAND_DISPLAY=${waylandSocket.name}",
                "XDG_SESSION_TYPE=wayland",
                "GDK_BACKEND=wayland",
                "QT_QPA_PLATFORM=wayland",
                "MOZ_ENABLE_WAYLAND=1",
                "SDL_VIDEODRIVER=wayland",
            ) + graphicsEnv +
            // Only once the library is there: the dynamic linker complains on every start about one it can't find.
            (if (cpuPinning && fastLane.isFile) listOf("LD_PRELOAD=$FAST_LANE") else emptyList()) +
            listOf("/bin/sh", "-c", script)
    }

    /**
     * Kills every proot we started and every guest process: before the rootfs
     * is deleted. A SIGKILLed proot doesn't take its tracees along
     * (--kill-on-exit needs a clean exit); they run on untraced and spin, so
     * they go too, found by their executable, proot's loader. Our own
     * processes are the only ones whose /proc entries we can read.
     */
    fun killAll() {
        val proot = File(nativeDir, "libproot.so").path
        val loader = File(nativeDir, "libproot-loader.so").path
        for (dir in File("/proc").listFiles().orEmpty()) {
            val pid = dir.name.toIntOrNull() ?: continue
            val argv0 = runCatching { File(dir, "cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().decodeToString() }.getOrNull()
            val exe = runCatching { android.system.Os.readlink(File(dir, "exe").path) }.getOrNull()
            if (argv0 == proot || exe == loader) runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
        }
    }

    /** A guest `sh -c [script]`, stderr merged into stdout. Needs [prepare] to have run. */
    fun process(script: String): ProcessBuilder =
        ProcessBuilder(command(script)).apply {
            environment().apply {
                clear()
                put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").path)
                put("PROOT_TMP_DIR", tmpDir.path)
                put("LD_LIBRARY_PATH", nativeDir.path)
                if (!cpuPinning) {
                    put("PROOT_HOME_CPU", "off")
                    put("PROOT_FASTPATH", "off")
                }
            }
            redirectErrorStream(true)
        }

    companion object {
        private const val CPU_PINNING = "proot_cpu_pinning"

        const val GUEST_HOME = "/root"

        /**
         * What [copyLinkerConfig] copies: the loader's map for programs in /system (app_process), the list of
         * libraries each APEX offers (read by libnativeloader), and the maps for the APEXes' own programs.
         * Ones this phone doesn't have are skipped.
         */
        private val LINKER_CONFIG_FILES =
            listOf("ld.config.txt", "apex.libraries.config.txt") +
                listOf("com.android.art", "com.android.runtime", "com.android.i18n", "com.android.conscrypt", "com.android.os.statsd")
                    .map { "$it/ld.config.txt" }

        /** XDG_RUNTIME_DIR in the guest, bound to [runtimeDir]. */
        const val GUEST_RUNTIME_DIR = "/run/user/0"

        private const val FAST_LANE = "/usr/local/lib/libfastpath.so"

        private const val GUEST_CJK_FONT = "/usr/share/fonts/android/NotoSansCJK-Regular.ttc"
    }
}
