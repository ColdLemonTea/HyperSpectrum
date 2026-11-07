#!/system/bin/sh
SKIPUNZIP=1
[ "$BOOTMODE" = true ] || abort "Install through the root manager"
[ "$ARCH" = arm64 ] || abort "Only arm64 is supported"
[ -d /data/adb/modules/zygisksu ] || abort "Zygisk Next 1.5.2 is required"
[ "$(getprop ro.build.version.incremental)" = OS4.0.6.0.XPBCNXM ] || abort "Unsupported ROM build"
unzip -o "$ZIPFILE" module.prop zn_modules.txt 'lib/*' LICENSE NOTICE -d "$MODPATH" >&2
set_perm_recursive "$MODPATH" 0 0 0755 0644
mkdir -p /data/adb/hyperspectrum-tap
[ ! -L /data/adb/hyperspectrum-tap ] || abort "Invalid control directory"
chmod 0700 /data/adb/hyperspectrum-tap
rm -f /data/adb/hyperspectrum-tap/command /data/adb/hyperspectrum-tap/status
ui_print "- Default off. Activation needs a fresh audioserver process."
