package com.google.android.youtube.pro.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.youtube.pro.webview.YTProWebView;

public class MediaCommandReceiver extends BroadcastReceiver {
    private final YTProWebView web;

    public MediaCommandReceiver(YTProWebView web) {
        this.web = web;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getExtras() == null) return;
        
        String action = intent.getExtras().getString("actionname");
        if (action == null) return;
        Log.e("Action MainActivity", action);

        switch (action) {
            case "PLAY_ACTION":
                web.evaluateJavascript("playVideo();", null);
                break;
            case "PAUSE_ACTION":
                web.evaluateJavascript("pauseVideo();", null);
                break;
            case "NEXT_ACTION":
                web.evaluateJavascript("playNext();", null);
                break;
            case "PREV_ACTION":
                web.evaluateJavascript("playPrev();", null);
                break;
            case "SEEKTO":
                String posRaw = intent.getExtras().getString("pos");
                if (posRaw != null) {
                    try {
                        double pos = Double.parseDouble(posRaw);
                        web.evaluateJavascript("seekTo(" + pos + ");", null);
                    } catch (NumberFormatException e) {
                        Log.e("MediaCommandReceiver", "Invalid seek pos: " + posRaw);
                    }
                }
                break;
        }
    }
}