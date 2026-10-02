package app.autospiegel;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

/** Stored settings for both modes. */
final class Prefs {
    static final String MODE_PHONE = "phone";
    static final String MODE_RADIO = "radio";

    /**
     * Phone content is shown sideways, so it fills the wide radio screen. The phone itself can
     * stay upright in its holder.
     */
    static final String ORIENTATION_LANDSCAPE = "landscape";
    /** Phone content stays upright; the radio shows a narrow, tall picture. */
    static final String ORIENTATION_PORTRAIT = "portrait";
    /** Leave rotation to the phone. */
    static final String ORIENTATION_FREE = "free";

    /** Radio fills the screen, squeezing the picture a little. */
    static final String DISPLAY_STRETCH = "stretch";
    /** Radio fills the screen, cutting off the picture's edges. */
    static final String DISPLAY_ZOOM = "zoom";
    /** Radio shows the whole picture with black bars. */
    static final String DISPLAY_FIT = "fit";

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

    String orientation() {
        return sp.getString("orientation", ORIENTATION_LANDSCAPE);
    }

    void setOrientation(String orientation) {
        sp.edit().putString("orientation", orientation).apply();
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

    String displayMode() {
        return sp.getString("display_mode", DISPLAY_STRETCH);
    }

    void setDisplayMode(String mode) {
        sp.edit().putString("display_mode", mode).apply();
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
}
