#!/system/bin/sh
MODDIR=${0%/*}
until [ "$(getprop sys.boot_completed)" = 1 ]; do
  sleep 1
done
[ -f "$MODDIR/disable" ] && exit 0
[ -f "$MODDIR/remove" ] && exit 0
RESULT=$(am broadcast --user 0 -a dev.mipad.guidedaccess.CONTROL --es command enable 2>&1)
printf '%s\n' "$RESULT" > "$MODDIR/service.log"
