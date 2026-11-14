#!/bin/bash
# Starts the labwc desktop as one window inside the launcher (nested Wayland).
# labwc gets its own runtime dir so it can never replace the launcher's wayland-0.
pgrep -x labwc >/dev/null && { echo "Desktop already running"; exit 0; }
PARENT=/run/user/0/wayland-0
[ -S "$PARENT" ] || { echo "The launcher's display ($PARENT) is missing: restart the launcher app"; exit 1; }
export WAYLAND_DISPLAY=$PARENT
export XDG_RUNTIME_DIR=/run/user/0/desktop
mkdir -p -m 700 "$XDG_RUNTIME_DIR"
export PULSE_SERVER=unix:/run/user/0/pulse/native
export WLR_BACKENDS=wayland WLR_RENDERER=pixman WLR_NO_HARDWARE_CURSORS=1
export ELECTRON_OZONE_PLATFORM_HINT=wayland QT_QPA_PLATFORM=wayland
# Apps on the GPU when the launcher's GPU setting is on. The launcher keeps the
# driver's ICD manifest only while the GPU is active (it deletes it when off or
# when its check fails), so its presence is the switch. Decided here because
# the widget shells that start this script may predate the GPU being switched on.
# labwc itself stays on pixman: the launcher only takes wl_shm buffers.
ICD=
for f in /usr/local/share/vulkan/icd.d/freedreno_icd.aarch64.json /usr/local/share/vulkan/icd.d/panfrost_icd.aarch64.json; do
	[ -f "$f" ] && { ICD=$f; break; }
done
if [ -n "$ICD" ]; then
	unset LIBGL_ALWAYS_SOFTWARE
	export VK_ICD_FILENAMES=$ICD VK_DRIVER_FILES=$ICD MESA_VK_WSI_DEBUG=sw
	export MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink
	export LIBGL_DRIVERS_PATH=/usr/local/omalauncher-mesa/lib/dri
	export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/local/share/glvnd/egl_vendor.d/50_mesa.json
else
	unset MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER LIBGL_DRIVERS_PATH __EGL_VENDOR_LIBRARY_FILENAMES MESA_VK_WSI_DEBUG
	export LIBGL_ALWAYS_SOFTWARE=1
	export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.json VK_DRIVER_FILES=/usr/share/vulkan/icd.d/lvp_icd.json
fi
exec dbus-run-session labwc >/tmp/labwc.log 2>&1
