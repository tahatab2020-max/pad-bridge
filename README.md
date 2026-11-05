# PadBridge

Bluetooth controller -> phone -> USB cable -> PC (shows up as a virtual Xbox 360 controller).

The phone app reads the controller and sends tiny 12-byte frames over a TCP socket
that travels through the USB cable (`adb reverse`). TCP_NODELAY is on, so there is no
batching delay. USB adds well under 1 ms. The main latency left is the controller's
own Bluetooth link to the phone, which this app cannot change.

## One-time setup

**PC (Windows)**
1. Install Python 3.9+ (tick "Add to PATH").
2. Install the ViGEmBus driver: https://github.com/nefarius/ViGEmBus/releases
3. Install Android platform-tools (adb) and add it to PATH.

**Phone (Android)**
1. Settings > About phone > tap Build number 7 times.
2. Settings > Developer options > turn on USB debugging.

**Build the app**
1. Open the `android` folder in Android Studio.
2. Let Gradle sync, then Run (or Build > Build APK) to install on the phone.

## Every time you play

1. Pair the controller with the phone over Bluetooth.
2. Plug the phone into the PC with USB and accept the debugging prompt.
3. Double-click `pc/start_pc.bat`.
4. Open PadBridge on the phone and keep it in the foreground (screen stays on).
5. Press a button. Test at https://hardwaretester.com/gamepad or in joy.cpl.

## Notes

- Layout is Xbox 360. Games that expect PlayStation prompts may need Steam Input.
- If the right stick is wrong for your controller, edit the axis picks in
  `dispatchGenericMotionEvent` in `MainActivity.kt`.
- Linux PC: swap `vgamepad` for `python-uinput` or `evdev` UInput in `receiver.py`.
- iPhone is not supported (no equivalent of adb reverse).
