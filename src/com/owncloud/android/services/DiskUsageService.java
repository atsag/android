package com.owncloud.android.services;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.ui.activity.FileDisplayActivity;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

/**
 * Created by andreas on 5/12/16.
 */
public class DiskUsageService  extends Service {

    static final int IDLE_SECONDS_LIMIT = 30;
    static final int PRECISION_SECONDS = 10; //maximum absolute deviation from seconds limit //repeat the check every minute
    public static boolean USER_NOTIFIED = false;
    BroadcastReceiver receiver;
    static final String TAG = DiskUsageService.class.getSimpleName();
    LocalBroadcastManager broadcaster;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    public void onCreate()
    {

//MY CODE
        //Toast.makeText(this,"MQTTONCREATE",Toast.LENGTH_LONG).show();
        broadcaster = LocalBroadcastManager.getInstance(this);
        final SimpleDateFormat format = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                if (intent.getAction().equals("com.filedisplayactivity.user_notified_reset")) {
                    Log.v(TAG,"User responded with remounting");
                    USER_NOTIFIED = false; //it should have been true;
                }else if(intent.getAction().equals("com.filedisplayactivity.broadcast_received")){
                    Log.v(TAG, "User was notified on his screen");
                    USER_NOTIFIED = true; //it should have been false;
                }

            }
        };
        Timer timer =new Timer();
        //while (true)
        timer.schedule(new TimerTask(){
        @Override
            public void run() {
                long time_idle=0; //measures time between the last activity logged and the current time.
                try {
                    time_idle =  Calendar.getInstance().getTime().getTime() - format.parse(Log_OC.last_action_timestamp).getTime();
                }
                catch (Exception e){
                    Log.e(TAG,"Could not calculate time difference");
                }
                long time_idle_in_seconds = TimeUnit.MILLISECONDS.toSeconds(time_idle);
                if (time_idle_in_seconds>IDLE_SECONDS_LIMIT){
                    if(!USER_NOTIFIED){
                        /*
                        Intent intent = new Intent(DiskUsageService.this,FileDisplayActivity.class);
                        startActivity(intent);
                        */
                        //FileDisplayActivity.publishMessage("OFF");    implemented when broadcast from notifyUser is received
                        notifyDiskDisconnected();
                    }
                }
            }
        },10,PRECISION_SECONDS*1000);
    }
    public void notifyDiskDisconnected() {
        final Intent intent = new Intent(DiskUsageService.this,FileDisplayActivity.class);
        intent.setAction("com.diskusageservice.remountaction"); //MUST BE THE SAME WITH RELEVANT ACTION IN FILEDISPLAYACTIVITY RECEIVER
        broadcaster.sendBroadcast(intent);
        Log.v(TAG,"notifyDiskDisconnected has sent broadcast");
    }
    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId){
        IntentFilter filter = new IntentFilter("com.filedisplayactivity.user_notified_reset");
        filter.addAction("com.filedisplayactivity.broadcast_received");
        LocalBroadcastManager.getInstance(this).registerReceiver((receiver),filter);
        return  START_STICKY;
    }
    @Override
    public void onDestroy(){
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
    }
}
