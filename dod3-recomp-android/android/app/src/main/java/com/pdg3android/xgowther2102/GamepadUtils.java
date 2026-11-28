package com.pdg3android.xgowther2102;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Filtra dispositivos virtuales o internos de OEM (por ejemplo, uinput-xiaomi)
 * para reconocer unicamente mandos fisicos Bluetooth/USB reales.
 */
public final class GamepadUtils {

    private static final String[] KNOWN_REAL_KEYWORDS = {
            "xbox", "x-box", "xinput",
            "playstation", "dualshock", "dualsense", "wireless controller",
            "nintendo", "pro controller", "joy-con",
            "gamesir", "8bitdo", "backbone", "kishi", "razer", "gulikit",
            "flydigi", "ipega", "mocute", "steelseries", "logitech", "stadia", "luna"
    };

    private static final String[] INTERNAL_BLACKLIST = {
            "dod3 virtual pad", "uinput",
            "xiaomi-touch", "xiaomi_touch", "goodix", "fpc", "focal", "fingerprint",
            "gpio-keys", "gpio_keys", "qpnp", "pmic", "pwrkey", "resin",
            "headset", "accdet", "avrcp",
            "touchscreen", "touch_mt", "sec_touch", "synaptics", "novatek", "fts_ts",
            "sensor", "accelerometer", "gyroscope", "virtual", "hall_sensor", "lid_switch"
    };

    private GamepadUtils() {}

    public static boolean isRealPhysicalGamepad(InputDevice dev) {
        if (dev == null || dev.getId() < 0 || dev.isVirtual()) {
            return false;
        }
        int src = dev.getSources();
        boolean hasPadSrc = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (!hasPadSrc) {
            return false;
        }

        String name = dev.getName() != null ? dev.getName() : "";
        String lower = name.toLowerCase(Locale.ROOT);

        boolean knownController = false;
        for (String kw : KNOWN_REAL_KEYWORDS) {
            if (lower.contains(kw)) {
                knownController = true;
                break;
            }
        }
        if (!knownController) {
            for (String blocked : INTERNAL_BLACKLIST) {
                if (lower.contains(blocked)) {
                    return false;
                }
            }
        }

        boolean hasAxes = dev.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK) != null
                && dev.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_JOYSTICK) != null;

        int keyCount = 0;
        boolean[] keys = dev.hasKeys(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X,
                KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_START
        );
        if (keys != null) {
            for (boolean k : keys) {
                if (k) keyCount++;
            }
        }

        if (!hasAxes && keyCount < 2) {
            return false;
        }
        if (dev.getVendorId() == 0 && dev.getProductId() == 0 && !hasAxes) {
            return false;
        }
        return true;
    }

    public static boolean isRealGamepadEvent(KeyEvent event) {
        if (event == null) return false;
        InputDevice dev = InputDevice.getDevice(event.getDeviceId());
        return isRealPhysicalGamepad(dev);
    }

    public static String detectConnectedPadFamily() {
        for (int id : InputDevice.getDeviceIds()) {
            if (id < 0) continue;
            InputDevice dev = InputDevice.getDevice(id);
            if (!isRealPhysicalGamepad(dev)) continue;
            String name = dev.getName() != null ? dev.getName() : "";
            int vid = dev.getVendorId();
            int pid = dev.getProductId();
            String lower = name.toLowerCase(Locale.ROOT);
            if (vid == 0x045E || lower.contains("xbox") || lower.contains("x-box") || lower.contains("xinput")) {
                return "xbox";
            }
            if (vid == 0x054C) {
                if (pid == 0x0CE6 || pid == 0x0DF2) return "ps5";
                if (pid == 0x05C4 || pid == 0x09CC) return "ps4";
                return "ps3";
            }
            if (vid == 0x057E || lower.contains("nintendo") || lower.contains("pro controller")) {
                return "switch";
            }
        }
        return "ps3";
    }

    public static List<String> listConnectedControllers() {
        List<String> out = new ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            if (id < 0) continue;
            InputDevice dev = InputDevice.getDevice(id);
            if (!isRealPhysicalGamepad(dev)) continue;
            String name = dev.getName() != null ? dev.getName() : "Mando genérico";
            String vidPid = String.format(Locale.US, "%04x:%04x", dev.getVendorId(), dev.getProductId());
            String lower = name.toLowerCase(Locale.ROOT);
            String tag = (dev.getVendorId() == 0x045E || lower.contains("xbox"))
                    ? " · Mapeo Xbox One/Series activo" : "";
            out.add(name + " [" + vidPid + "]" + tag);
        }
        return out;
    }
}
