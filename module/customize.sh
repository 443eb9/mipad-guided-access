#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "Install from the Magisk app while Android is running."
[ "$(getprop ro.product.device)" = pipa ] || abort "This build supports pipa (小米平板 6)."
[ "$(getprop ro.build.version.sdk)" = 34 ] || abort "This build supports Android 14."
[ -d /data/adb/modules/zygisk_lsposed ] || abort "Install and enable LSPosed first."
[ ! -f /data/adb/modules/zygisk_lsposed/disable ] || abort "Enable LSPosed first."

INSTALL_APK="/data/local/tmp/mipad-guided-access-$$.apk"
cp "$MODPATH/guided-access.apk" "$INSTALL_APK" || abort "APK copy failed."
chmod 644 "$INSTALL_APK"
RESULT=$(pm install -r "$INSTALL_APK" 2>&1)
rm -f "$INSTALL_APK"
case "$RESULT" in
  *Success*) ui_print "Companion APK installed." ;;
  *) abort "$RESULT" ;;
esac
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
ui_print "Enable '平板引导式访问' in LSPosed."
ui_print "Select Android / System Framework and System UI, then reboot."
