package com.castor.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** Diálogos de transmissão: escolher a TV e controlar o que está tocando nela. */
public final class CastUi {
    private static final int PANEL_LIGHT = Color.rgb(47, 51, 42);
    private static final int TEXT = Color.rgb(255, 250, 242);
    private static final int MUTED = Color.rgb(179, 180, 166);
    private static final int PEACH = Color.rgb(242, 174, 134);
    private static final int BG = Color.rgb(23, 25, 21);

    private CastUi() {
    }

    /**
     * Abre a escolha de dispositivo para {@code item}. {@code startMs < 0} retoma da posição salva.
     * Com {@code item == null} abre direto o controle da transmissão ativa.
     */
    public static void show(final Activity a, final MediaItem item, final long startMs) {
        final CastManager cm = CastManager.get(a);
        if (item == null) {
            if (cm.isActive()) showControls(a);
            else Toast.makeText(a, "Nenhuma transmissão ativa", Toast.LENGTH_SHORT).show();
            return;
        }
        final Store store = new Store(a);
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 8));

        final TextView status = text(a, "Procurando TVs na rede Wi-Fi…", 13, MUTED);
        box.addView(status);
        final ProgressBar pb = new ProgressBar(a);
        pb.setIndeterminate(true);
        box.addView(pb, new LinearLayout.LayoutParams(dp(a, 28), dp(a, 28)));
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(a, 8), 0, 0);
        box.addView(list);

        TextView tip = text(a, "Dica: celular e TV precisam estar na mesma rede Wi-Fi. "
                + (store.remoteUrl().isEmpty() ? "" : "Dispositivos “via servidor Castor” convertem o vídeo no seu PC/NAS."), 11, MUTED);
        tip.setPadding(0, dp(a, 12), 0, 0);
        box.addView(tip);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        final AlertDialog[] dlg = new AlertDialog[1];
        AlertDialog.Builder b = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Transmitir para…")
                .setView(sv)
                .setPositiveButton("Buscar de novo", null)
                .setNeutralButton("Adicionar por IP", null)
                .setNegativeButton("Fechar", null);
        dlg[0] = b.create();
        dlg[0].setOnDismissListener(d -> {
            cm.setDevicesListener(null);
            if (!cm.isActive()) cm.setStateListener(null);
        });
        dlg[0].show();

        cm.setStateListener(new CastManager.StateListener() {
            @Override
            public void onState(String state, long posMs, long durMs) {
            }

            @Override
            public void onMessage(String message) {
                Toast.makeText(a, message, Toast.LENGTH_LONG).show();
            }
        });

        final CastManager.DevicesListener dl = (devices, searching) -> {
            pb.setVisibility(searching ? View.VISIBLE : View.GONE);
            status.setText(devices.isEmpty()
                    ? (searching ? "Procurando TVs na rede Wi-Fi…" : "Nenhuma TV encontrada. Use “Adicionar por IP” ou ajuste o servidor Castor nas configurações.")
                    : (searching ? "Procurando… toque na TV para transmitir:" : "Toque na TV para transmitir:"));
            list.removeAllViews();
            for (final CastDevice d : devices) {
                Button bt = button(a, d.label(), PANEL_LIGHT, TEXT);
                bt.setAllCaps(false);
                bt.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                bt.setOnClickListener(v -> {
                    dlg[0].dismiss();
                    cm.setStateListener(null);
                    cm.cast(d, item, startMs);
                    Toast.makeText(a, "Enviando para " + d.name + "…", Toast.LENGTH_SHORT).show();
                    showControlsSoon(a);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 46));
                lp.bottomMargin = dp(a, 6);
                list.addView(bt, lp);
            }
        };
        cm.setDevicesListener(dl);
        cm.discover();

        dlg[0].getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> cm.discover());
        dlg[0].getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            final EditText et = new EditText(a);
            et.setHint("192.168.0.20  ou  http://ip:porta/descricao.xml");
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            et.setTextColor(TEXT);
            et.setHintTextColor(MUTED);
            new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("Adicionar por IP")
                    .setMessage("IP de um Chromecast, ou a URL de descrição de uma TV DLNA.")
                    .setView(et)
                    .setPositiveButton("Adicionar", (d, w) -> cm.addManual(et.getText().toString()))
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
    }

    private static void showControlsSoon(final Activity a) {
        // dá um instante para a sessão começar antes de abrir o controle
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!a.isFinishing() && !a.isDestroyed()) showControls(a);
        }, 900);
    }

    public static void showControls(final Activity a) {
        final CastManager cm = CastManager.get(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 8));

        MediaItem item = cm.activeItem();
        CastDevice dev = cm.activeDevice();
        final TextView title = text(a, item == null ? "Transmissão" : item.title, 14, TEXT);
        title.setMaxLines(2);
        box.addView(title);
        final TextView where = text(a, dev == null ? "Conectando…" : "Em: " + dev.name, 12, MUTED);
        where.setPadding(0, dp(a, 2), 0, dp(a, 10));
        box.addView(where);
        final TextView state = text(a, cm.stateLabel(), 13, PEACH);
        box.addView(state);
        final TextView time = text(a, "0:00:00 / 0:00:00", 13, TEXT);
        time.setPadding(0, dp(a, 6), 0, 0);
        box.addView(time);
        final SeekBar sb = new SeekBar(a);
        sb.setMax(1);
        box.addView(sb);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button back = button(a, "⏪ 10s", PANEL_LIGHT, TEXT);
        Button pp = button(a, "⏯", PEACH, BG);
        Button fwd = button(a, "10s ⏩", PANEL_LIGHT, TEXT);
        back.setOnClickListener(v -> cm.seekBy(-10000));
        pp.setOnClickListener(v -> cm.togglePlayPause());
        fwd.setOnClickListener(v -> cm.seekBy(10000));
        for (Button bt : new Button[]{back, pp, fwd}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(a, 44), 1f);
            lp.rightMargin = dp(a, 6);
            row.addView(bt, lp);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(a, 8);
        box.addView(row, rp);

        final boolean[] dragging = new boolean[1];
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) time.setText(fmt(progress * 1000L) + " / " + fmt(cm.lastDur()));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging[0] = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging[0] = false;
                cm.seekTo(s.getProgress() * 1000L);
            }
        });

        final AlertDialog dlg = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Controle da TV")
                .setView(box)
                .setPositiveButton("Fechar", null)
                .setNegativeButton("Parar transmissão", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cm.stop();
            dlg.dismiss();
        });
        dlg.setOnDismissListener(d -> {
            cm.setStateListener(null);
        });

        cm.setStateListener(new CastManager.StateListener() {
            @Override
            public void onState(String st, long posMs, long durMs) {
                CastDevice d = cm.activeDevice();
                MediaItem it = cm.activeItem();
                if (it != null) title.setText(it.title);
                if (d != null) where.setText("Em: " + d.name);
                state.setText(cm.stateLabel());
                if (durMs > 0) {
                    int max = (int) (durMs / 1000);
                    if (sb.getMax() != max) sb.setMax(max);
                }
                if (!dragging[0]) {
                    sb.setProgress((int) (posMs / 1000));
                    time.setText(fmt(posMs) + " / " + fmt(durMs));
                }
            }

            @Override
            public void onMessage(String message) {
                Toast.makeText(a, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static int dp(Activity a, float v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static TextView text(Activity a, String value, float sp, int color) {
        TextView t = new TextView(a);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private static Button button(Activity a, String label, int bg, int fg) {
        Button b = new Button(a);
        b.setText(label);
        b.setTextColor(fg);
        b.setTextSize(13);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(a, 12));
        b.setBackground(g);
        b.setAllCaps(false);
        return b;
    }
}
