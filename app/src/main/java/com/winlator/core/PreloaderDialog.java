package com.winlator.core;

import android.app.Activity;
import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.winlator.R;

public class PreloaderDialog {
    private final Activity activity;
    private Dialog dialog;
    private int progressTextResId = -1;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.preloader_dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    public synchronized void show(int textResId) {
        if (isShowing()) return;
        close();
        if (dialog == null) create();
        ((TextView)dialog.findViewById(R.id.TextView)).setText(textResId);
        progressTextResId = -1;
        dialog.show();
    }

    public synchronized void showProgress(int textResId) {
        show(textResId);
        progressTextResId = textResId;
        updateProgress(0);
    }

    public void setProgress(final int percent) {
        activity.runOnUiThread(() -> {
            synchronized (PreloaderDialog.this) {
                if (progressTextResId != -1) updateProgress(percent);
            }
        });
    }

    private void updateProgress(int percent) {
        int boundedPercent = Math.max(0, Math.min(100, percent));
        TextView textView = dialog.findViewById(R.id.TextView);
        textView.setText(activity.getString(progressTextResId) + " " + boundedPercent + "%");
    }

    public void showOnUiThread(final int textResId) {
        activity.runOnUiThread(() -> show(textResId));
    }

    public synchronized void close() {
        try {
            if (dialog != null) {
                dialog.dismiss();
            }
        }
        catch (Exception e) {}
        progressTextResId = -1;
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}
