package com.owncloud.android.ui.activity;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.widget.Toast;

import com.owncloud.android.R;

/**
 * Created by andreas on 5/6/16.
 */
public class RemountDiskActivity extends FragmentActivity {
    protected void onCreate() {
        //Toast.makeText(this, "Remount Activity started", Toast.LENGTH_SHORT).show();
        super.setContentView(R.layout.hard_disk_reconnect);
        Log.d("Layout","Layout Change!");
        //super.onCreate(savedInstanceState);
    }
    protected void onPause() {
        //Toast.makeText(this, "Remount Activity paused", Toast.LENGTH_LONG).show();
        super.onPause();
    }
    protected void onResume() {
        Toast.makeText(this, "Remount Activity resumed", Toast.LENGTH_LONG).show();

        //Below must go to seperate disk_use_check activity.
        //while
        //sleep 1000
        //check for activity
     /* THIS IS HOW TO STALL EΧΕCUTION OF ALL COMMANDS

        try{
            Toast.makeText(this, "Remount Activity part 1 - wait", Toast.LENGTH_SHORT).show();
            SystemClock.sleep(20000);
            Toast.makeText(this, "Remount Activity part 2", Toast.LENGTH_SHORT).show();
        }catch (java.lang.Exception exception)
        {
            Log.e("Exception", exception.toString());
        }*/
        super.onResume();
        finish();
    }
    protected void onStop() {
        //Toast.makeText(this, "Remount Activity stopped", Toast.LENGTH_LONG).show();
        super.onStop();
    }
}
