package app.autospiegel;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Stored settings for both modes. */
final class Prefs {
    static final String MODE_PHONE = "phone";
    static final String MODE_RADIO = "radio";

    private static final int MAX_DESTINATIONS = 6;

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("autospiegel", Context.MODE_PRIVATE);
    }

    /** {@link #MODE_PHONE}, {@link #MODE_RADIO} or null if not chosen yet. */
    String mode() {
        return sp.getString("mode", null);
    }

    void setMode(String mode) {
        sp.edit().putString("mode", mode).apply();
    }

    // Phone side

    /** The pairing code of the radio this phone accepts. */
    String trustedRadioCode() {
        return sp.getString("trusted_code", "");
    }

    void setTrustedRadioCode(String code) {
        sp.edit().putString("trusted_code", code).apply();
    }

    /** Whether the phone service should come back after a reboot or an update. */
    boolean autoStart() {
        return sp.getBoolean("auto_start", false);
    }

    void setAutoStart(boolean value) {
        sp.edit().putBoolean("auto_start", value).apply();
    }

    // Radio side

    /** This radio's own pairing code, generated on first use. */
    synchronized String radioCode() {
        String code = sp.getString("radio_code", null);
        if (code == null) {
            code = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
            sp.edit().putString("radio_code", code).apply();
        }
        return code;
    }

    String manualPhoneIp() {
        return sp.getString("manual_ip", "");
    }

    void setManualPhoneIp(String ip) {
        sp.edit().putString("manual_ip", ip).apply();
    }

    String lastPhoneIp() {
        return sp.getString("last_ip", "");
    }

    void setLastPhoneIp(String ip) {
        sp.edit().putString("last_ip", ip).apply();
    }

    /** Read new messages aloud on the radio as they arrive. */
    boolean autoRead() {
        return sp.getBoolean("auto_read", false);
    }

    void setAutoRead(boolean value) {
        sp.edit().putBoolean("auto_read", value).apply();
    }

    /** Recently used navigation destinations, newest first. */
    List<String> destinations() {
        String stored = sp.getString("destinations", "");
        List<String> result = new ArrayList<>();
        if (stored.length() > 0) {
            result.addAll(Arrays.asList(stored.split("\n")));
        }
        return result;
    }

    void addDestination(String destination) {
        List<String> list = destinations();
        list.remove(destination);
        list.add(0, destination);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < list.size() && i < MAX_DESTINATIONS; i++) {
            if (i > 0) {
                joined.append('\n');
            }
            joined.append(list.get(i));
        }
        sp.edit().putString("destinations", joined.toString()).apply();
    }
}
