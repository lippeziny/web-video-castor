package com.castor.mobile;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

/**
 * Sistema de design do Castor 3.0: modos Sistema/Claro/Escuro/AMOLED combinados com
 * uma cor de destaque (fortes e foscas) ou a cor dinâmica do Android 12+.
 * Os campos são lidos por MainActivity, CastUi e PlayerActivity.
 */
public final class ThemePalette {
    public static int BG = 0xFF101114;
    public static int PANEL = 0xFF1A1B1F;
    public static int PANEL_LIGHT = 0xFF25262B;
    public static int OUTLINE = 0xFF30323A;
    public static int TEXT = 0xFFF3F4F6;
    public static int MUTED = 0xFF9A9CA6;
    public static int ACCENT = 0xFFE5484D;
    public static int ON_ACCENT = 0xFFFFFFFF;
    public static int ACCENT_SOFT = 0xFF3A2528;
    public static int GREEN = 0xFF4CC38A;
    public static int DANGER = 0xFFFF6B6B;
    public static boolean LIGHT = false;

    public static final int ACCENT_DYNAMIC = 8;
    public static final String[] ACCENT_NAMES = {
            "Vermelho", "Laranja", "Verde", "Ciano", "Azul", "Roxo", "Rosa", "Pêssego", "Dinâmica"
    };
    private static final int[] ACCENT_DARK = {
            0xFFE5484D, 0xFFEE8A2B, 0xFF3FB56C, 0xFF27B3CC, 0xFF4C86F7, 0xFF9168F5, 0xFFE5508A, 0xFFF2AE86, 0xFF4C86F7
    };
    private static final int[] ACCENT_LIGHT = {
            0xFFC9303A, 0xFFC76A0C, 0xFF228A4B, 0xFF0C8DA6, 0xFF2A5FD9, 0xFF6F45D8, 0xFFC22C68, 0xFFB55B35, 0xFF2A5FD9
    };

    private ThemePalette() {}

    /** Modo efetivo: light, dark ou amoled (resolve "system" pelo modo noturno do Android). */
    public static String mode(Context c) {
        String m = new Store(c).theme();
        if ("system".equals(m)) {
            int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_NO ? "light" : "dark";
        }
        return m;
    }

    public static void apply(Context c) {
        Store store = new Store(c);
        String mode = mode(c);
        LIGHT = "light".equals(mode);
        int accent = accentColor(c, store.accent(), LIGHT);
        float tint = 1f;
        if (LIGHT) {
            BG = 0xFFF3F3F6;
            PANEL = 0xFFFFFFFF;
            PANEL_LIGHT = blend(0xFFEBEBF0, accent, 0.05f * tint);
            OUTLINE = blend(0xFFD9DAE1, accent, 0.04f * tint);
            TEXT = 0xFF15161A;
            MUTED = 0xFF686A74;
            GREEN = 0xFF1F8A55;
            DANGER = 0xFFC62828;
        } else if ("amoled".equals(mode)) {
            BG = 0xFF000000;
            PANEL = blend(0xFF0E0E10, accent, 0.03f * tint);
            PANEL_LIGHT = blend(0xFF1B1B1F, accent, 0.05f * tint);
            OUTLINE = blend(0xFF2A2A2F, accent, 0.05f * tint);
            TEXT = 0xFFF5F5F7;
            MUTED = 0xFF8E8E98;
            GREEN = 0xFF4CC38A;
            DANGER = 0xFFFF6B6B;
        } else {
            BG = 0xFF101114;
            PANEL = blend(0xFF1A1B1F, accent, 0.04f * tint);
            PANEL_LIGHT = blend(0xFF25262B, accent, 0.06f * tint);
            OUTLINE = blend(0xFF30323A, accent, 0.05f * tint);
            TEXT = 0xFFF3F4F6;
            MUTED = 0xFF9A9CA6;
            GREEN = 0xFF4CC38A;
            DANGER = 0xFFFF6B6B;
        }
        ACCENT = accent;
        ON_ACCENT = onColor(accent);
        ACCENT_SOFT = blend(PANEL, accent, LIGHT ? 0.16f : 0.22f);
    }

    /** Cor de destaque de um índice; a "Dinâmica" usa a cor do sistema no Android 12+. */
    public static int accentColor(Context c, int idx, boolean light) {
        if (idx < 0 || idx >= ACCENT_DARK.length) idx = 0;
        if (idx == ACCENT_DYNAMIC && Build.VERSION.SDK_INT >= 31) {
            try {
                return c.getColor(light ? android.R.color.system_accent1_600 : android.R.color.system_accent1_300);
            } catch (Exception ignored) {
            }
        }
        return light ? ACCENT_LIGHT[idx] : ACCENT_DARK[idx];
    }

    public static int blend(int base, int over, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(Color.red(base) * (1f - a) + Color.red(over) * a);
        int g = Math.round(Color.green(base) * (1f - a) + Color.green(over) * a);
        int b = Math.round(Color.blue(base) * (1f - a) + Color.blue(over) * a);
        return Color.rgb(r, g, b);
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static double luminance(int color) {
        double r = channel(Color.red(color));
        double g = channel(Color.green(color));
        double b = channel(Color.blue(color));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** Branco ou quase preto, o que tiver mais contraste sobre a cor dada. */
    public static int onColor(int bg) {
        double l = luminance(bg);
        double withWhite = 1.05 / (l + 0.05);
        double dark = (l + 0.05) / (luminance(0xFF15161A) + 0.05);
        return withWhite >= dark ? 0xFFFFFFFF : 0xFF15161A;
    }

    public static int dialogTheme(Context context) {
        return "light".equals(mode(context))
                ? AlertDialog.THEME_DEVICE_DEFAULT_LIGHT : AlertDialog.THEME_DEVICE_DEFAULT_DARK;
    }

    public static String label(String mode) {
        if ("system".equals(mode)) return "Automático (sistema)";
        if ("light".equals(mode)) return "Claro";
        if ("amoled".equals(mode)) return "AMOLED (preto puro)";
        return "Escuro";
    }

    public static String accentName(int idx) {
        return idx >= 0 && idx < ACCENT_NAMES.length ? ACCENT_NAMES[idx] : ACCENT_NAMES[0];
    }
}
