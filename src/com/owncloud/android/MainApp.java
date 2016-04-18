/**
 *   ownCloud Android client application
 *
 *   @author masensio
 *   @author David A. Velasco
 *   Copyright (C) 2015 ownCloud Inc.
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.owncloud.android;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.owncloud.android.authentication.PassCodeManager;
import com.owncloud.android.datamodel.ThumbnailsCacheManager;
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory;
import com.owncloud.android.lib.common.OwnCloudClientManagerFactory.Policy;
import com.owncloud.android.lib.common.utils.Log_OC;

import java.lang.ref.WeakReference;

//My_imports
import com.owncloud.android.MQTTService.LocalBinder;
//my_imports end

/**
 * Main Application of the project
 *
 * Contains methods to build the "static" strings. These strings were before constants in different
 * classes
 */
public class MainApp extends Application {

    private static final String TAG = MainApp.class.getSimpleName();

    private static final String AUTH_ON = "on";

    @SuppressWarnings("unused")
    private static final String POLICY_SINGLE_SESSION_PER_ACCOUNT = "single session per account";
    @SuppressWarnings("unused")
    private static final String POLICY_ALWAYS_NEW_CLIENT = "always new client";



    private static Context mContext;

    // TODO Enable when "On Device" is recovered?
    // TODO better place
    // private static boolean mOnlyOnDevice = false;

    // New (my) class variables start here
    public static Intent mqttService;
    public static MQTTService mService;
    private static boolean mBound;
    public static ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {

            Log.d(TAG, "Inside onServiceConnected");
            // We've bound to LocalService, cast the IBinder and get LocalService instance

            LocalBinder binder = (LocalBinder) service;
            mService = (MQTTService)binder.getService();
            //Debug
            //System.out.println(mService.toString());
            // mService = ((MQTTService.java.LocalBinder) service).getService();

            mBound = true;
            String message = "ON";
            mService.publishMessageToTopic(message);
//            mService.stopSelf();
//            mService.unbindService(mConnection);
//            unbindService(mConnection);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };
// New class variables end here

    public void onCreate(){
        super.onCreate();
        MainApp.mContext = getApplicationContext();

        boolean isSamlAuth = AUTH_ON.equals(getString(R.string.auth_method_saml_web_sso));

        OwnCloudClientManagerFactory.setUserAgent(getUserAgent());
        if (isSamlAuth) {
            OwnCloudClientManagerFactory.setDefaultPolicy(Policy.SINGLE_SESSION_PER_ACCOUNT);
        } else {
            OwnCloudClientManagerFactory.setDefaultPolicy(Policy.ALWAYS_NEW_CLIENT);
        }

        // initialise thumbnails cache on background thread
        new ThumbnailsCacheManager.InitDiskCacheTask().execute();

        if (BuildConfig.DEBUG) {

            String dataFolder = getDataFolder();

            // Set folder for store logs
            Log_OC.setLogDataFolder(dataFolder);

            Log_OC.startLogging();
            Log_OC.d("Debug", "start logging");
        }
//-----------------------------------Modifications start
        // Segment of modifications
        Toast.makeText(this,"Owncloud Application has started",Toast.LENGTH_LONG).show();


//        final com.imsight.com.imsight.androidmqtt.androidmqtt.MQTTService.java mService;
//        final mqttservice = new Intent(this, MQTTService.class);
        mqttService= new Intent(MainApp.mContext, MQTTService.class);
        startService(mqttService);
/*WATCH OUT! NEXT LINE SHOULD NOT BE COMMENTED!*/

        bindService(mqttService, mConnection, Context.BIND_AUTO_CREATE);
//below line stops the service.
//        stopService(mqttService);


//-----------------------------------modifications end



        // register global protection with pass code
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            this.registerActivityLifecycleCallbacks( new ActivityLifecycleCallbacks() {

                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    Log_OC.d(activity.getClass().getSimpleName(),  "onCreate(Bundle) starting" );
                    PassCodeManager.getPassCodeManager().onActivityCreated(activity);
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    Log_OC.d(activity.getClass().getSimpleName(),  "onStart() starting" );
                    PassCodeManager.getPassCodeManager().onActivityStarted(activity);
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    Log_OC.d(activity.getClass().getSimpleName(), "onResume() starting" );
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    Log_OC.d(activity.getClass().getSimpleName(), "onPause() ending");
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    Log_OC.d(activity.getClass().getSimpleName(), "onStop() ending" );
                    PassCodeManager.getPassCodeManager().onActivityStopped(activity);
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                    Log_OC.d(activity.getClass().getSimpleName(), "onSaveInstanceState(Bundle) starting" );
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    Log_OC.d(activity.getClass().getSimpleName(), "onDestroy() ending" );
                }
            });
        }
    }

    public void publishMessage(String message){
        //if (mBound) for(int i=1;i<10;i++){System.out.println("Supposedly connected");}
        //while (mService==null){System.out.println("NULL!");}
        mService.publishMessageToTopic(message);
        unbindService(mConnection);
       // stopService(mqttService);
    }

    public static Context getAppContext() {
        return MainApp.mContext;
    }

    // Methods to obtain Strings referring app_name 
    //   From AccountAuthenticator 
    //   public static final String ACCOUNT_TYPE = "owncloud";    
    public static String getAccountType() {
        return getAppContext().getResources().getString(R.string.account_type);
    }

    //  From AccountAuthenticator 
    //  public static final String AUTHORITY = "org.owncloud";
    public static String getAuthority() {
        return getAppContext().getResources().getString(R.string.authority);
    }

    //  From AccountAuthenticator
    //  public static final String AUTH_TOKEN_TYPE = "org.owncloud";
    public static String getAuthTokenType() {
        return getAppContext().getResources().getString(R.string.authority);
    }

    //  From ProviderMeta 
    //  public static final String DB_FILE = "owncloud.db";
    public static String getDBFile() {
        return getAppContext().getResources().getString(R.string.db_file);
    }

    //  From ProviderMeta
    //  private final String mDatabaseName = "ownCloud";
    public static String getDBName() {
        return getAppContext().getResources().getString(R.string.db_name);
    }

    /**
     * name of data_folder, e.g., "owncloud"
     */
    public static String getDataFolder() {
        return getAppContext().getResources().getString(R.string.data_folder);
    }

    // log_name
    public static String getLogName() {
        return getAppContext().getResources().getString(R.string.log_name);
    }

    // TODO Enable when "On Device" is recovered ?
//    public static void showOnlyFilesOnDevice(boolean state){
//        mOnlyOnDevice = state;
//    }
//
//    public static boolean getOnlyOnDevice(){
//        return mOnlyOnDevice;
//    }

    // user agent
    public static String getUserAgent() {
        String appString = getAppContext().getResources().getString(R.string.user_agent);
        String packageName = getAppContext().getPackageName();
        String version = "";

        PackageInfo pInfo = null;
        try {
            pInfo = getAppContext().getPackageManager().getPackageInfo(packageName, 0);
            if (pInfo != null) {
                version = pInfo.versionName;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log_OC.e(TAG, "Trying to get packageName", e.getCause());
        }

        // Mozilla/5.0 (Android) ownCloud-android/1.7.0
        String userAgent = String.format(appString, version);

        return userAgent;
    }




}