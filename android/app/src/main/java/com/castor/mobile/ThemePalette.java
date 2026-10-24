package com.castor.mobile;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;

/** Paletas coerentes para o navegador, configurações e diálogos. */
public final class ThemePalette {
    public static int BG = Color.rgb(23, 25, 21);
    public static int PANEL = Color.rgb(36, 39, 32);
    public static int PANEL_LIGHT = Color.rgb(47, 51, 42);
    public static int TEXT = Color.rgb(255, 250, 242);
    public static int MUTED = Color.rgb(179, 180, 166);
    public static int ACCENT = Color.rgb(242, 174, 134);
    public static int GREEN = Color.rgb(170, 205, 145);

    private ThemePalette() {}

    public static void apply(Context context) {
        String mode = new Store(context).theme();
        if ("light".equals(mode)) {
            BG = Color.rgb(248, 246, 241);
            PANEL = Color.rgb(255, 255, 255);
            PANEL_LIGHT = Color.rgb(235, 231, 222);
            TEXT = Color.rgb(37, 39, 35);
            MUTED = Color.rgb(104, 106, 98);
            ACCENT = Color.rgb(181, 91, 53);
            GREEN = Color.rgb(66, 126, 79);
        } else if ("amoled".equals(mode)) {
            BG = Color.BLACK;
            PANEL = Color.rgb(13, 13, 13);
            PANEL_LIGHT = Color.rgb(27, 27, 27);
            TEXT = Color.rgb(248, 247, 244);
            MUTED = Color.rgb(166, 166, 166);
            ACCENT = Color.rgb(255, 177, 132);
            GREEN = Color.rgb(170, 220, 145);
        } else {
            // Tema escuro original, com os tons oliva e pêssego do Castor.
            BG = Color.rgb(23, 25, 21);
            PANEL = Color.rgb(36, 39, 32);
            PANEL_LIGHT = Color.rgb(47, 51, 42);
            TEXT = Color.rgb(255, 250, 242);
            MUTED = Color.rgb(179, 180, 166);
            ACCENT = Color.rgb(242, 174, 134);
            GREEN = Color.rgb(170, 205, 145);
        }
    }

    public static int dialogTheme(Context context) {
        return "light".equals(new Store(context).theme())
                ? AlertDialog.THEME_DEVICE_DEFAULT_LIGHT : AlertDialog.THEME_DEVICE_DEFAULT_DARK;
    }

    public static String label(String mode) {
        if ("light".equals(mode)) return "Claro";
        if ("amoled".equals(mode)) return "AMOLED (preto puro)";
        return "Escuro";
    }
}
