package com.google.android.youtube.pro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import com.google.android.youtube.pro.receivers.NotificationActionReceiver;

public class ForegroundService extends Service {

    public static final String CHANNEL_ID = "Media";
    public static final String ACTION_UPDATE_NOTIFICATION = "UPDATE_NOTIFICATION";
    private NotificationManager notificationManager;
    private BroadcastReceiver updateReceiver;
    private MediaSession mediaSession;
    // 🔒 백그라운드 재생 중 CPU 잠들지 않도록 PARTIAL_WAKE_LOCK 유지(화면 꺼짐/잠금 시에도 오디오 끊김 방지)
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        initMediaSession();
        registerUpdateReceiver();
        createNotificationChannel();
        // 🔒 WakeLock 확보 — 서비스 생존 중 화면 꺼짐 시에도 JS 타이머·미디어 디코딩 유지.
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTPro:Playback");
                wakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) { Log.e("YTPRO_FS", "wakeLock create failed: " + e); }
    }

    /** 🔒 재생 시작/재개 시 WakeLock 취득. setReferenceCounted(false) 이므로 중복 acquire 무해. */
    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(/* 화면 꺼져도 서비스 종료까지 유지 */);
        } catch (Exception e) { Log.e("YTPRO_FS", "wakeLock acquire failed: " + e); }
    }

    /** 🔒 재생 정지/서비스 종료 시 WakeLock 해제. */
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) { Log.e("YTPRO_FS", "wakeLock release failed: " + e); }
    }


    private void initMediaSession() {
        mediaSession = new MediaSession(getApplicationContext(), "YTPROMediaSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                super.onPlay();
                getApplicationContext().sendBroadcast(new Intent("TRACKS_TRACKS")
                        .putExtra("actionname", "PLAY_ACTION"));
                        Log.e("pause","play session called");

            }

            @Override
            public void onPause() {
                super.onPause();
                getApplicationContext().sendBroadcast(new Intent("TRACKS_TRACKS")
                        .putExtra("actionname", "PAUSE_ACTION"));
                        
                        Log.e("pause","pause session called");
            }

            @Override
            public void onSkipToNext() {
                super.onSkipToNext();
// Handle skip to next
                getApplicationContext().sendBroadcast(new Intent("TRACKS_TRACKS")
                        .putExtra("actionname", "NEXT_ACTION"));
            }

            @Override
            public void onSkipToPrevious() {
                super.onSkipToPrevious();
// Handle skip to previous

                getApplicationContext().sendBroadcast(new Intent("TRACKS_TRACKS")
                        .putExtra("actionname", "PREV_ACTION"));

            }
            @Override
            public void onSeekTo(long pos) {
                super.onSeekTo(pos);
                getApplicationContext().sendBroadcast(new Intent("TRACKS_TRACKS")
                        .putExtra("actionname", "SEEKTO").putExtra("pos", pos+""));


            }
        });

        mediaSession.setActive(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Background Play",
                    NotificationManager.IMPORTANCE_MIN
            );
            notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }


    public void updateNotification(String icon, String title, String subtitle, String action, long duration, long currentPosition) {

        Context cont=getApplicationContext();

        byte[] decodedBytes = Base64.decode(icon, Base64.DEFAULT);
        Bitmap largeIcon = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);


        int playbackState;
        if("pause".equals(action)){
            playbackState= PlaybackState.STATE_PAUSED;
        }
        else if("play".equals(action)){
            playbackState= PlaybackState.STATE_PLAYING;
        }else{
            playbackState= PlaybackState.STATE_BUFFERING;
        }

        updateMediaSessionMetadata(title, subtitle, largeIcon, duration); 
        updatePlaybackState(currentPosition, playbackState); 

        Intent openAppIntent = new Intent(cont, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openAppPendingIntent = PendingIntent.getActivity(cont, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        Intent playIntent = new Intent(cont, NotificationActionReceiver.class);
        playIntent.setAction("PLAY_ACTION");
        PendingIntent playPendingIntent = PendingIntent.getBroadcast(cont, 0, playIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        Intent pauseIntent = new Intent(cont, NotificationActionReceiver.class);
        pauseIntent.setAction("PAUSE_ACTION");
        PendingIntent pausePendingIntent = PendingIntent.getBroadcast(cont, 0, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        Intent nextIntent = new Intent(cont, NotificationActionReceiver.class);
        nextIntent.setAction("NEXT_ACTION");
        PendingIntent nextPendingIntent = PendingIntent.getBroadcast(cont, 0, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        Intent prevIntent = new Intent(cont, NotificationActionReceiver.class);
        prevIntent.setAction("PREV_ACTION");
        PendingIntent prevPendingIntent = PendingIntent.getBroadcast(cont, 0, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        Notification.Builder builder = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);

        builder.setSmallIcon((Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? R.drawable.notification : R.mipmap.app_icon)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setLargeIcon(largeIcon)
                .setContentIntent(openAppPendingIntent)
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()))
                .addAction(R.drawable.ic_skip_previous_white, "Previous", prevPendingIntent);

        if ("play".equals(action)) {
            builder.addAction(R.drawable.ic_pause_white, "Pause", pausePendingIntent)
                    .addAction(R.drawable.ic_skip_next_white, "Next", nextPendingIntent);
        } else if ("pause".equals(action))  {
            builder.addAction(R.drawable.ic_play_arrow_white, "Play", playPendingIntent)
                    .addAction(R.drawable.ic_skip_next_white, "Next", nextPendingIntent);
        }else{

            builder.addAction(R.drawable.ic_pause_white, "Pause", pausePendingIntent)
                    .addAction(R.drawable.ic_skip_next_white, "Next", nextPendingIntent);

        }



        notificationManager.notify(1, builder.build());
    }



    private void registerUpdateReceiver() {
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_UPDATE_NOTIFICATION.equals(intent.getAction())) {
                    String icon = intent.getStringExtra("icon");
                    String title = intent.getStringExtra("title");
                    String subtitle = intent.getStringExtra("subtitle");
                    String action = intent.getStringExtra("action");
                    long duration = intent.getLongExtra("duration", 0);
                    long currentPosition = intent.getLongExtra("currentPosition", 0);

                    updateNotification(icon, title, subtitle, action, duration, currentPosition);
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_UPDATE_NOTIFICATION);

          if (Build.VERSION.SDK_INT >= 34 && getApplicationInfo().targetSdkVersion >= 34) {
           registerReceiver(updateReceiver, filter,RECEIVER_EXPORTED);
          }
          else{
           registerReceiver(updateReceiver, filter);
          }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            setupNotification(intent);
        }
        return START_NOT_STICKY;
    }

    private void setupNotification(Intent intent) {
        long duration = intent.getLongExtra("duration", 0);
        long currentPosition = intent.getLongExtra("currentPosition", 0);
        String action = intent.getStringExtra("action");

        int playbackState;
        if("pause".equals(action)){
            playbackState= PlaybackState.STATE_PAUSED;
            releaseWakeLock(); // 🔒 일시정지 시 CPU 깨움 불필요 → WakeLock 해제(배터리 절감)
        }
        else if("play".equals(action)){
            playbackState= PlaybackState.STATE_PLAYING;
            acquireWakeLock(); // 🔒 재생 중 화면 꺼짐 대비 WakeLock 취득
        }else{
            playbackState= PlaybackState.STATE_BUFFERING;
            acquireWakeLock(); // 🔒 버퍼링(시작)에도 CPU 유지
        }
        
        
        String title = intent.getStringExtra("title");
        String subtitle = intent.getStringExtra("subtitle");
        String icon = intent.getStringExtra("icon");

        byte[] decodedBytes = Base64.decode(icon, Base64.DEFAULT);
        Bitmap largeIcon = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openAppPendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent playIntent = new Intent(this, NotificationActionReceiver.class);
        playIntent.setAction("PLAY_ACTION");
        PendingIntent playPendingIntent = PendingIntent.getBroadcast(this, 0, playIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent pauseIntent = new Intent(this, NotificationActionReceiver.class);
        pauseIntent.setAction("PAUSE_ACTION");
        PendingIntent pausePendingIntent = PendingIntent.getBroadcast(this, 0, pauseIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent nextIntent = new Intent(this, NotificationActionReceiver.class);
        nextIntent.setAction("NEXT_ACTION");
        PendingIntent nextPendingIntent = PendingIntent.getBroadcast(this, 0, nextIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent prevIntent = new Intent(this, NotificationActionReceiver.class);
        prevIntent.setAction("PREV_ACTION");
        PendingIntent prevPendingIntent = PendingIntent.getBroadcast(this, 0, prevIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);

                builder.setSmallIcon((Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? R.drawable.notification : R.mipmap.app_icon)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setLargeIcon(largeIcon)
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()))
                .setContentIntent(openAppPendingIntent);


        builder.addAction(R.drawable.ic_skip_previous_white, "Previous", prevPendingIntent);
        
                    builder.addAction(R.drawable.ic_pause_white, "Pause", pausePendingIntent);
                    
                builder.addAction(R.drawable.ic_skip_next_white, "Next", nextPendingIntent);

        Notification notification = builder.build();

        // Update MediaSession metadata and playback state
        updateMediaSessionMetadata(title, subtitle, largeIcon, duration);
        updatePlaybackState(currentPosition, playbackState);

        startForeground(1, notification);
    }
    
    
    
    
    
    
    private void updateMediaSessionMetadata(String title, String artist, Bitmap albumArt, long duration) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "YT PRO")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, albumArt)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build();

        mediaSession.setMetadata(metadata);
    }







    private void updatePlaybackState(long currentPosition, int state) {
        PlaybackState playbackState = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(state, currentPosition, 1.0f) // 1.0f for playback speed
                .build();
                
                
                // rn it doesn't have a function to increase the playback speed if someone increases it from the youtube player , cuz people don't usually use that , and i am too lazy to implement it here

        mediaSession.setPlaybackState(playbackState);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock(); // 🔒 서비스 종료 시 WakeLock 확실히 해제(누수 방지)
        if (mediaSession != null) {
            try { mediaSession.setActive(false); mediaSession.release(); } catch (Exception e) {}
        }
        unregisterReceiver(updateReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
