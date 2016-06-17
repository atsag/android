package com.owncloud.android;

import android.accounts.Account;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
//import android.*;

import com.ibm.mqtt.IMqttClient;
import com.ibm.mqtt.MqttClient;
import com.ibm.mqtt.MqttException;
import com.ibm.mqtt.MqttNotConnectedException;
import com.ibm.mqtt.MqttPersistence;
import com.ibm.mqtt.MqttPersistenceException;
import com.ibm.mqtt.MqttSimpleCallback;
import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.services.DiskUsageService;
import com.owncloud.android.ui.activity.FileDisplayActivity;
import com.owncloud.android.ui.activity.Preferences;
import com.owncloud.android.ui.activity.RemountDiskActivity;
import android.preference.PreferenceManager;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

/*
 * An example of how to implement an MQTT client in Android, able to receive
 *  push notifications from an MQTT message broker server.
 *  
 *  Dale Lane (dale.lane@gmail.com)
 *    28 Jan 2011
 */
public class MQTTService extends Service implements MqttSimpleCallback
{
    /************************************************************************/
    /*    CONSTANTS                                                         */
    /************************************************************************/
    
	private static final String TAG ="MQTT";
	private final static String RESP_TOPIC = "Test";
	
    // something unique to identify your app - used for stuff like accessing
    //   application preferences
    public static final String APP_ID = "com.owncloud.android";
    
    // constants used to notify the Activity UI of received messages    
    public static final String MQTT_MSG_RECEIVED_INTENT = "com.imsight.androidmqtt.MSGRECVD";
    public static final String MQTT_MSG_RECEIVED_TOPIC  = "com.imsight.androidmqtt.MSGRECVD_TOPIC";
    public static final String MQTT_MSG_RECEIVED_MSG    = "com.imsight.androidmqtt.MSGRECVD_MSGBODY";
    
    // constants used to tell the Activity UI the connection status
    public static final String MQTT_STATUS_INTENT = "com.imsight.androidmqtt.STATUS";
    public static final String MQTT_STATUS_MSG    = "com.imsight.androidmqtt.STATUS_MSG";

    // constant used internally to schedule the next ping event
    public static final String MQTT_PING_ACTION = "com.imsight.androidmqtt.PING";
    
    // constants used by status bar notifications
    public static final int MQTT_NOTIFICATION_ONGOING = 1;  
    public static final int MQTT_NOTIFICATION_UPDATE  = 2;
    
    // constants used to define MQTT connection status
    public enum MQTTConnectionStatus 
    {
        INITIAL,                            // initial status
        CONNECTING,                         // attempting to connect
        CONNECTED,                          // connected
        NOTCONNECTED_WAITINGFORINTERNET,    // can't connect because the phone
                                            //     does not have Internet access
        NOTCONNECTED_USERDISCONNECT,        // user has explicitly requested 
                                            //     disconnection
        NOTCONNECTED_DATADISABLED,          // can't connect because the user 
                                            //     has disabled data access
        NOTCONNECTED_UNKNOWNREASON          // failed to connect for some reason
    }

    // MQTT constants
    public static final int MAX_MQTT_CLIENTID_LENGTH = 22;
    
    /************************************************************************/
    /*    VARIABLES used to maintain state                                  */
    /************************************************************************/
    
    // status of MQTT client connection
    private MQTTConnectionStatus connectionStatus = MQTTConnectionStatus.INITIAL;
    

    /************************************************************************/
    /*    VARIABLES used to configure MQTT connection                       */
    /************************************************************************/
    
    // taken from preferences
    //    host name of the server we're receiving push notifications from
    private String          brokerHostName       = "test";//"192.168.1.6";
    // taken from preferences
    //    topic we want to receive messages about
    //    can include wildcards - e.g.  '#' matches anything
    private String          topicName            = "test";//"SYS/disk";

    
    // defaults - this sample uses very basic defaults for it's interactions 
    //   with message brokers
    private int             brokerPortNumber     = 1883;
    private short           quality_of_service   = 0;
    private MqttPersistence usePersistence       = null;
    private boolean         cleanStart           = false;
    private int[]           qualitiesOfService   = { 0 } ;

    //  how often should the app ping the server to keep the connection alive?
    //
    //   too frequently - and you waste battery life
    //   too infrequently - and you wont notice if you lose your connection 
    //                       until the next unsuccessfull attempt to ping
    //
    //   it's a trade-off between how time-sensitive the data is that your 
    //      app is handling, vs the acceptable impact on battery life
    //
    //   it is perhaps also worth bearing in mind the network's support for 
    //     long running, idle connections. Ideally, to keep a connection open
    //     you want to use a keep alive value that is less than the period of
    //     time after which a network operator will kill an idle connection
    private short           keepAliveSeconds     = 20 * 60; 

    
    // This is how the Android client app will identify itself to the  
    //  message broker. 
    // It has to be unique to the broker - two clients are not permitted to  
    //  connect to the same broker using the same client ID. 
    private String          mqttClientId = null; 

    
    
    /************************************************************************/
    /*    VARIABLES  - other local variables                                */   
    /************************************************************************/
    // connection to the message broker
    private IMqttClient mqttClient = null;

   /* MYCOMMENT */

    // receiver that notifies the Service when the phone gets data connection 
    private NetworkConnectionIntentReceiver netConnReceiver;
    
    // receiver that notifies the Service when the user changes data use preferences
    private BackgroundDataChangeIntentReceiver dataEnabledReceiver;
    
    // receiver that wakes the Service up when it's time to ping the server
    private PingSender pingSender;

    /* MY VARIABLES */

   // /LocalBroadcastManager broadcaster;
   // long IDLE_SECONDS_LIMIT = 30;

    Handler handler = new Handler();
    BroadcastReceiver wifi_reconnection_receiver,mqtt_preferences_receiver;
    private String pending_message = "none";
    private Boolean RESTART = false;
    private Boolean COMPLEX_NOTIFICATIONS = false;
    private Boolean SIMPLE_NOTIFICATIONS = true;
    /* MY VARIABLES END HERE*/

    /************************************************************************/
    /*    METHODS - core Service lifecycle methods                          */
    /************************************************************************/

    // see http://developer.android.com/guide/topics/fundamentals.html#lcycles

    /*mycomment*/


    private void send_pending_messages(){
        if (!pending_message.equals("none")) {
            Log.v(TAG,"Just Sent a pending message, "+pending_message);
            publishMessageToTopic(pending_message);
            pending_message="none";
        }
    }

    @Override
    public void onCreate() 
    {
        Account currentAccount = AccountUtils.getCurrentOwnCloudAccount(getApplicationContext());
        if (currentAccount==null){
            Log.v(TAG,"Current Account is null");
            return;
        }
        else {
            Log.v(TAG,"Current Account is "+currentAccount.name);
        }

// MY CODE
        Log.v(TAG, "Current broker address is: " + brokerHostName);
        Log.v(TAG, "Current Mqtt topic is: " + topicName);
        PreferenceManager preferenceManager ;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        brokerHostName=prefs.getString("brokerHostName",null);
        topicName = prefs.getString("topicName",null);

        Log.v(TAG, "Updated broker address is: " + brokerHostName);
        Log.v(TAG, "Updated Mqtt topic is: " + topicName);

        mqtt_preferences_receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                //SharedPreferences settings = getSharedPreferences("com.owncloud.android.ui.Preferences",MODE_PRIVATE);
                //String test = new Preferences().getSharedPreferences("",0).getString("brokerHostName",null);
                brokerHostName = intent.getStringExtra("brokerHostName");
                topicName = intent.getStringExtra("topicName");

                Log.v(TAG, "New broker address is: " + brokerHostName);
                Log.v(TAG, "New Mqtt topic is: " + topicName);
                if (brokerHostName != null && topicName != null){
                    RESTART = true;
                    stopSelf();
                }

                //Log.v(TAG,"test is "+test);
            }
        };


        wifi_reconnection_receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG,"Received Alert to subscribe once more");
                //subscribeToTopic(topicName);
                Log.v(TAG,"these are the current values for broker: "+brokerHostName+" and topic: "+topicName);

                    Timer timer =new Timer();
                    //while (true)
                    timer.schedule(new TimerTask(){
                        @Override
                        public void run() {
                            Log.v(TAG,"Trying to connect with the help of Timer to topic "+topicName+" and broker "+brokerHostName);
                            if (mqttClient==null){
                                defineConnectionToBroker(brokerHostName);
                            }
                            if (connectToBroker()) {
                                subscribeToTopic(topicName);
//                                unbindService(FileDisplayActivity.mConnection);
 //                               bindService(FileDisplayActivity.mqttService, FileDisplayActivity.mConnection, Context.BIND_AUTO_CREATE);
                                send_pending_messages();
                                Log.v(TAG,"Connected with the help of Timer method and sent pending messages");
                                cancel(); //cancels the timer, we have no need for it.
                            }
                        }
                    },10,200);
            }
        };



/*
        Toast.makeText(this,"MQTTONCREATE",Toast.LENGTH_LONG).show();
        broadcaster = LocalBroadcastManager.getInstance(this);
    //repeat the check every minute
        final SimpleDateFormat format = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

        Timer timer =new Timer(); timer.schedule(new TimerTask(){
        @Override public void run() {
            Log.v(TAG,"Timer task is Running");
            long time_idle=0; //measures time between the last activity logged and the current time.
            try {
                time_idle =  Calendar.getInstance().getTime().getTime() - format.parse(Log_OC.last_action_timestamp).getTime();
            }
            catch (Exception e){
                Log.e(TAG,"Could not calculate time difference");
            }
            long time_idle_in_seconds = TimeUnit.MILLISECONDS.toSeconds(time_idle);
            if (time_idle_in_seconds>IDLE_SECONDS_LIMIT){
                signalDisk();
            }
        }
    },60*1000);


    // Recurring task
        Timer timer =new Timer(); timer.scheduleAtFixedRate(new TimerTask(){
        @Override public void run() {
            Log.v(TAG,"SignalDisk is Running");
            signalDisk();
        }
    }, 1000, 1000);


        // Intent intent = new Intent(this, RemountDiskActivity.class);
       // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
       // startActivity(intent);

        Log.d(TAG,"Remount Activity should have started");
    */
    /*MY CODE END  */
        super.onCreate();

        // reset status variable to initial state
        connectionStatus = MQTTConnectionStatus.INITIAL;
        
        // create a binder that will let the Activity UI send 
        //   commands to the Service 
        mBinder = new LocalBinder<MQTTService>(this);
        
        // get the broker settings out of app preferences
        //   this is not the only way to do this - for example, you could use 
        //   the Intent that starts the Service to pass on configuration values


        //MYCOMMENTS
        //SharedPreferences settings = getSharedPreferences(APP_ID, MODE_PRIVATE);

        //brokerHostName = settings.getString("broker", "");
        //topicName      = settings.getString("topic",  "");
        
        // register to be notified whenever the user changes their preferences 
        //  relating to background data use - so that we can respect the current
        //  preference
        dataEnabledReceiver = new BackgroundDataChangeIntentReceiver();
        registerReceiver(dataEnabledReceiver,
                         new IntentFilter(ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED));
        
        // define the connection to the broker
        defineConnectionToBroker(brokerHostName);
    }
    
    
    @Override
    public void onStart(final Intent intent, final int startId) 
    {
        // This is the old onStart method that will be called on the pre-2.0
        // platform.  On 2.0 or later we override onStartCommand() so this
        // method will not be called.        
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                handleStart(intent, startId);
            }
        }, "MQTTservice").start();
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) 
    {
        LocalBroadcastManager.getInstance(this).registerReceiver((wifi_reconnection_receiver),
                new IntentFilter("com.mqttservice.restart"));
        LocalBroadcastManager.getInstance(this).registerReceiver((mqtt_preferences_receiver),
                new IntentFilter("com.owncloud.preferences.update_mqtt"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                handleStart(intent, startId);
            }
        }, "MQTTservice").start();
        
        // return START_NOT_STICKY - we want this Service to be left running 
        //  unless explicitly stopped, and it's process is killed, we want it to
        //  be restarted
        return START_STICKY;
    }
    synchronized void handleStart(Intent intent, int startId)
    {
        // before we start - check for a couple of reasons why we should stop

        if (mqttClient == null) 
        {
            // we were unable to define the MQTT client connection, so we stop 
            //  immediately - there is nothing that we can do
            stopSelf();
            return;
        }
        
        ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if (cm.getBackgroundDataSetting() == false) // respect the user's request not to use data!
        {
            // user has disabled background data
            connectionStatus = MQTTConnectionStatus.NOTCONNECTED_DATADISABLED;
            
            // update the app to show that the connection has been disabled
            broadcastServiceStatus("Not connected - background data disabled");
            
            // we have a listener running that will notify us when this 
            //   preference changes, and will call handleStart again when it 
            //   is - letting us pick up where we leave off now 
            return;
        }
        
        // the Activity UI has started the MQTT service - this may be starting
        //  the Service new for the first time, or after the Service has been
        //  running for some time (multiple calls to startService don't start
        //  multiple Services, but it does call this method multiple times)
        // if we have been running already, we re-send any stored data        
        rebroadcastStatus();
        rebroadcastReceivedMessages();
        
        // if the Service was already running and we're already connected - we 
        //   don't need to do anything 
        if (!isAlreadyConnected())
        {
            // set the status to show we're trying to connect
            connectionStatus = MQTTConnectionStatus.CONNECTING;
            
            // we are creating a background service that will run forever until
            //  the user explicity stops it. so - in case they start needing 
            //  to save battery life - we should ensure that they don't forget
            //  we're running, by leaving an ongoing notification in the status
            //  bar while we are running

         /*mycomment
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification notification = new Notification(R.drawable.ic_mqtt, 
                                                         "MQTT",
                                                         System.currentTimeMillis());                                                         
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            notification.flags |= Notification.FLAG_NO_CLEAR;
            Intent notificationIntent = new Intent(this, com.imsight.mqttandroid.MQTTActivity.class);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, 
                                                                    notificationIntent, 
                                                                    PendingIntent.FLAG_UPDATE_CURRENT);
            notification.setLatestEventInfo(this, "MQTT", "MQTT Service is running", contentIntent);
            nm.notify(MQTT_NOTIFICATION_ONGOING, notification);
         */
            
            // before we attempt to connect - we check if the phone has a 
            //  working data connection
            if (isOnline())
            {
                // we think we have an Internet connection, so try to connect
                //  to the message broker
                //MY CODE
                if (mqttClient==null){
                    defineConnectionToBroker(brokerHostName);
                }
                //MY CODE END
                if (connectToBroker()) 
                {
                    // we subscribe to a topic - registering to receive push
                    //  notifications with a particular key
                    // in a 'real' app, you might want to subscribe to multiple
                    //  topics - I'm just subscribing to one as an example
                    // note that this topicName could include a wildcard, so 
                    //  even just with one subscription, we could receive 
                    //  messages for multiple topics
                    subscribeToTopic(topicName);
                }
            }
            else
            {
                // we can't do anything now because we don't have a working 
                //  data connection 
                connectionStatus = MQTTConnectionStatus.NOTCONNECTED_WAITINGFORINTERNET;
                
                // inform the app that we are not connected
                broadcastServiceStatus("Waiting for network connection");
            }
        }

        // changes to the phone's network - such as bouncing between WiFi 
        //  and mobile data networks - can break the MQTT connection
        // the MQTT connectionLost can be a bit slow to notice, so we use 
        //  Android's inbuilt notification system to be informed of 
        //  network changes - so we can reconnect immediately, without 
        //  haing to wait for the MQTT timeout
        if (netConnReceiver == null)
        {
            netConnReceiver = new NetworkConnectionIntentReceiver();            
            registerReceiver(netConnReceiver, 
                             new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            
        }
        
        // creates the intents that are used to wake up the phone when it is 
        //  time to ping the server
        if (pingSender == null)
        {
            pingSender = new PingSender();
            registerReceiver(pingSender, new IntentFilter(MQTT_PING_ACTION));
        }
    }
 /**/
    /*mycomment*/

    @Override

    public void onDestroy() 
    {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(wifi_reconnection_receiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mqtt_preferences_receiver);
        Log.v(TAG,"Mqtt service terminating");

        super.onDestroy();

        // disconnect immediately
        disconnectFromBroker();

        // inform the app that the app has successfully disconnected
        broadcastServiceStatus("Disconnected");
        
        // try not to leak the listener        
        if (dataEnabledReceiver != null) 
        {
            unregisterReceiver(dataEnabledReceiver);
            dataEnabledReceiver = null;
        }
        
        if (mBinder != null) {
            mBinder.close();
            mBinder = null;
        }
        if(RESTART){
            startService(new Intent(this,MQTTService.class));
            Log.v(TAG,"Mqtt service should restart");
        }

    }
        /*mycomment ends*/


    /************************************************************************/
    /*    METHODS - broadcasts and notifications                            */
    /************************************************************************/
    
    // methods used to notify the Activity UI of something that has happened
    //  so that it can be updated to reflect status and the data received 
    //  from the server
    
    private void broadcastServiceStatus(String statusDescription) 
    {
        // inform the app (for times when the Activity UI is running / 
        //   active) of the current MQTT connection status so that it 
        //   can update the UI accordingly
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(MQTT_STATUS_INTENT);
        broadcastIntent.putExtra(MQTT_STATUS_MSG, statusDescription); 
        sendBroadcast(broadcastIntent);        
    }
    
    private void broadcastReceivedMessage(String topic, String message)
    {
        // pass a message received from the MQTT server on to the Activity UI 
        //   (for times when it is running / active) so that it can be displayed 
        //   in the app GUI
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(MQTT_MSG_RECEIVED_INTENT);
        broadcastIntent.putExtra(MQTT_MSG_RECEIVED_TOPIC, topic);
        broadcastIntent.putExtra(MQTT_MSG_RECEIVED_MSG,   message);
        sendBroadcast(broadcastIntent);                
    }
    
    // methods used to notify the user of what has happened for times when 
    //  the app Activity UI isn't running




    private void notifyUser(String alert, String title, String body)
    {
        if (COMPLEX_NOTIFICATIONS){
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        Intent notificationIntent = new Intent(this, MQTTActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                this);
        Notification notification = builder.setContentIntent(contentIntent)
                .setSmallIcon(R.drawable.ic_ok).setWhen(System.currentTimeMillis())
                .setAutoCancel(true).setContentTitle(title)
                .setContentText(body).build();


        /*Notification notification = new Notification(R.drawable.ic_ok, alert,
                                                     System.currentTimeMillis());*/
        notification.defaults |= Notification.DEFAULT_LIGHTS;
        notification.defaults |= Notification.DEFAULT_SOUND;
 //Will not allow additional permissions       notification.defaults |= Notification.DEFAULT_VIBRATE;
        notification.flags |= Notification.FLAG_AUTO_CANCEL;        
        notification.ledARGB = Color.MAGENTA;


        nm.notify(MQTT_NOTIFICATION_UPDATE, notification);
        }
        else if (SIMPLE_NOTIFICATIONS) {
            if (body.equals("ON")) body = "Hard disk has been mounted";
            else if (body.equals("OFF")) body = "Hard disk has been unmounted";
            final String parent_alert = body;
            handler.post(new Runnable() {
                    public void run() {
                        Toast.makeText(getApplicationContext(),parent_alert,Toast.LENGTH_SHORT).show();
                    }
            });
        }
    }



    //replacement method for notifyUser defined above

    /*private void notifyUser(String alert, String title, String body)
    {
        Toast.makeText(this,title.toUpperCase()+" "+body,Toast.LENGTH_LONG).show();

    }*/
    
    /************************************************************************/
    /*    METHODS - binding that allows access from the Actitivy            */
    /************************************************************************/
    
    // trying to do local binding while minimizing leaks - code thanks to
    //   Geoff Bruckner - which I found at  
    //   http://groups.google.com/group/cw-android/browse_thread/thread/d026cfa71e48039b/c3b41c728fedd0e7?show_docid=c3b41c728fedd0e7
    
    private LocalBinder<MQTTService> mBinder;
    
    @Override
    public IBinder onBind(Intent intent) 
    {
    	return mBinder;
    }
    public class LocalBinder<S> extends Binder 
    {
        private WeakReference<S> mService;
        
        public LocalBinder(S service)
        {
            mService = new WeakReference<S>(service);
        }
        public S getService() 
        {
            return mService.get();
        }        
        public void close() 
        { 
            mService = null; 
        }
    }
    
    // 
    // public methods that can be used by Activities that bind to the Service
    //
    
    public MQTTConnectionStatus getConnectionStatus() 
    {
        return connectionStatus;
    }    
    
    public void rebroadcastStatus()
    {
        String status = "";
        
        switch (connectionStatus)
        {
            case INITIAL:
                status = "Please wait";
                break;
            case CONNECTING:
                status = "Connecting...";
                break;
            case CONNECTED:
                status = "Connected";
                break;
            case NOTCONNECTED_UNKNOWNREASON:
                status = "Not connected - waiting for network connection";
                break;
            case NOTCONNECTED_USERDISCONNECT:
                status = "Disconnected";
                break;
            case NOTCONNECTED_DATADISABLED:
                status = "Not connected - background data disabled";
                break;
            case NOTCONNECTED_WAITINGFORINTERNET:
                status = "Unable to connect";
                break;
        }
        
        //
        // inform the app that the Service has successfully connected
        broadcastServiceStatus(status);
    }
    public void disconnect()
    {
        disconnectFromBroker();

        // set status 
        connectionStatus = MQTTConnectionStatus.NOTCONNECTED_USERDISCONNECT;
        
        // inform the app that the app has successfully disconnected
        broadcastServiceStatus("Disconnected");       
    }


    /************************************************************************/
    /*    METHODS - MQTT methods inherited from MQTT classes                */
    /************************************************************************/
    
    /*
     * callback - method called when we no longer have a connection to the 
     *  message broker server
     */


    /*public void connectionLost(){

    }*/
    /*Maybe change to above?*/

    public void connectionLost() throws Exception
    {
        // we protect against the phone switching off while we're doing this
        //  by requesting a wake lock - we request the minimum possible wake 
        //  lock - just enough to keep the CPU running until we've finished
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
        wl.acquire();
        
        
        //
        // have we lost our data connection?
        //
        
        if (isOnline() == false)
        {
            connectionStatus = MQTTConnectionStatus.NOTCONNECTED_WAITINGFORINTERNET;
            
            // inform the app that we are not connected any more
            broadcastServiceStatus("Connection lost - no network connection");
        
            //
            // inform the user (for times when the Activity UI isn't running) 
            //   that we are no longer able to receive messages
            notifyUser("Connection lost - no network connection", 
                       "MQTT", "Connection lost - no network connection");

            //
            // wait until the phone has a network connection again, when we 
            //  the network connection receiver will fire, and attempt another
            //  connection to the broker
        }
        else 
        {
            // 
            // we are still online
            //   the most likely reason for this connectionLost is that we've 
            //   switched from wifi to cell, or vice versa
            //   so we try to reconnect immediately
            // 
            
            connectionStatus = MQTTConnectionStatus.NOTCONNECTED_UNKNOWNREASON;
            
            // inform the app that we are not connected any more, and are 
            //   attempting to reconnect
            broadcastServiceStatus("Connection lost - reconnecting...");
            
            // try to reconnect
            if (connectToBroker()) {
                subscribeToTopic(topicName);
            }
        }
        
        // we're finished - if the phone is switched off, it's okay for the CPU 
        //  to sleep now
        wl.release();
    }

  /* end of my comment*/



    /*
     *   callback - called when we receive a message from the server 
     */

    /*one more of my comments*/

    public void publishArrived(String topic, byte[] payloadbytes, int qos, boolean retained)  
    {
        // we protect against the phone switching off while we're doing this
        //  by requesting a wake lock - we request the minimum possible wake 
        //  lock - just enough to keep the CPU running until we've finished
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
        wl.acquire();
        
        //
        //  I'm assuming that all messages I receive are being sent as strings
        //   this is not an MQTT thing - just me making as assumption about what
        //   data I will be receiving - your app doesn't have to send/receive
        //   strings - anything that can be sent as bytes is valid
        String messageBody = new String(payloadbytes);

        //
        //  for times when the app's Activity UI is not running, the Service 
        //   will need to safely store the data that it receives
        if (addReceivedMessageToStore(topic, messageBody))
        {
            // this is a new message - a value we haven't seen before
            
            //
            // inform the app (for times when the Activity UI is running) of the 
            //   received message so the app UI can be updated with the new data
            broadcastReceivedMessage(topic, messageBody);
            
            //
            // inform the user (for times when the Activity UI isn't running) 
            //   that there is new data available

            notifyUser("New data received Edited", topic, messageBody);
        }

        /*




        */

        // receiving this message will have kept the connection alive for us, so
        //  we take advantage of this to postpone the next scheduled ping
        scheduleNextPing();

        // we're finished - if the phone is switched off, it's okay for the CPU 
        //  to sleep now        
        wl.release();
    }

    /*mycomment ends here*/
    /************************************************************************/
    /*    METHODS - wrappers for some of the MQTT methods that we use       */
    /************************************************************************/
    
    /*
     * Create a client connection object that defines our connection to a
     *   message broker server 
     */

    /*mycomment*/


    private void defineConnectionToBroker(String brokerHostName)
    {
        String mqttConnSpec = "tcp://" + brokerHostName + "@" + brokerPortNumber;
        
        try
        {
            // define the connection to the broker
            mqttClient = MqttClient.createMqttClient(mqttConnSpec, usePersistence);

            // register this client app has being able to receive messages
            mqttClient.registerSimpleHandler(this);            
        }
        catch (MqttException e)
        {
            // something went wrong!
            mqttClient = null;
            connectionStatus = MQTTConnectionStatus.NOTCONNECTED_UNKNOWNREASON;
            
            //
            // inform the app that we failed to connect so that it can update
            //  the UI accordingly
            broadcastServiceStatus("Invalid connection parameters");

            //
            // inform the user (for times when the Activity UI isn't running) 
            //   that we failed to connect
            notifyUser("Unable to connect", "MQTT", "Unable to connect");
        }        
    }

    /*
     * (Re-)connect to the message broker
     */


    /*mycomment*/


    private boolean connectToBroker()
    {
        try
        {            
            // try to connect
            mqttClient.connect(generateClientId(), cleanStart, keepAliveSeconds);

            //
            // inform the app that the app has successfully connected
            broadcastServiceStatus("Connected");
            
            // we are connected
            connectionStatus = MQTTConnectionStatus.CONNECTED;

            // we need to wake up the phone's CPU frequently enough so that the 
            //  keep alive messages can be sent
            // we schedule the first one of these now
            scheduleNextPing();

            return true;
        }
        catch (MqttException e)
        {
            // something went wrong!
            
            connectionStatus = MQTTConnectionStatus.NOTCONNECTED_UNKNOWNREASON;
            
            //
            // inform the app that we failed to connect so that it can update
            //  the UI accordingly
            broadcastServiceStatus("Unable to connect");

            //
            // inform the user (for times when the Activity UI isn't running) 
            //   that we failed to connect


           notifyUser("Unable to connect", "MQTT", "Unable to connect - will retry later. Please ensure that you have inserted a valid MQTT broker IP address and a topic");



            // if something has failed, we wait for one keep-alive period before
            //   trying again
            // in a real implementation, you would probably want to keep count
            //  of how many times you attempt this, and stop trying after a 
            //  certain number, or length of time - rather than keep trying 
            //  forever.
            // a failure is often an intermittent network issue, however, so 
            //  some limited retry is a good idea
            scheduleNextPing();
            
            return false;
        }
    }

    /* 
     * Ashu Publish Message to a Topic 
     * 
     */
    
    public boolean publishMessageToTopic(String message)
    {
    	Boolean retained = false;
    	if (isAlreadyConnected() == false)
    	{
            pending_message = message;
    		Log.d(TAG, "mqtt, Unable to publish as we are not connected");
    	}
    	else
    	{
    		try
    		{
    			
    			Log.d(TAG, "MQTT Publish Message Rcvd: " + message);
    			
    			
    			// String[] tps = { topicName };
    			// MqttPayload msg = new MqttPayload(message.getBytes());
    			byte[] msg = message.getBytes();
    			
    			
    			// mqttClient.publish(RESP_TOPIC, msg, 0, false);

    			mqttClient.publish(topicName, msg, quality_of_service, false);
    			//subscribed = true;
                return true;
    			
    		}
    		catch (MqttNotConnectedException e) 
            {
                Log.e("mqtt", "subscribe failed - MQTT not connected", e);
            } 
            catch (IllegalArgumentException e) 
            {
                Log.e("mqtt", "subscribe failed - illegal argument", e);
            } 
            catch (MqttException e) 
            {
                Log.e("mqtt", "subscribe failed - MQTT exception", e);
            }
    	}
        return false;
    }
    
    /*
     * Send a request to the message broker to be sent messages published with 
     *  the specified topic name. Wildcards are allowed.    
     */

    /*mycomment*/


    private void subscribeToTopic(String topicName)
    {
        boolean subscribed = false;
        
        if (isAlreadyConnected() == false)
        {
            // quick sanity check - don't try and subscribe if we 
            //  don't have a connection
            
            Log.e("mqtt", "Unable to subscribe as we are not connected");
        }
        else 
        {                                    
            try 
            {
                String[] topics = { topicName };
                mqttClient.subscribe(topics, qualitiesOfService);
                
                subscribed = true;
            } 
            catch (MqttNotConnectedException e) 
            {
                Log.e("mqtt", "subscribe failed - MQTT not connected", e);
            } 
            catch (IllegalArgumentException e) 
            {
                Log.e("mqtt", "subscribe failed - illegal argument", e);
            } 
            catch (MqttException e) 
            {
                Log.e("mqtt", "subscribe failed - MQTT exception", e);
            }
        }
        
        if (subscribed == false)
        {
            //
            // inform the app of the failure to subscribe so that the UI can 
            //  display an error
            broadcastServiceStatus("Unable to subscribe");

            //inform the user (for times when the Activity UI isn't running)
            //TODO: inform the user in a meaningful way
            //notifyUser("Unable to subscribe", "MQTT", "Unable to subscribe");
        }
    }

    /*
     * Terminates a connection to the message broker.
     */

    /*mycomment*/


    private void disconnectFromBroker()
    {
        // if we've been waiting for an Internet connection, this can be 
        //  cancelled - we don't need to be told when we're connected now
        try
        {
            if (netConnReceiver != null) 
            {
                unregisterReceiver(netConnReceiver);
                netConnReceiver = null;
            }
            
            if (pingSender != null)
            {
                unregisterReceiver(pingSender);
                pingSender = null;
            }
        }
        catch (Exception eee)
        {
            // probably because we hadn't registered it
            Log.e("mqtt", "unregister failed", eee);
        }

        try 
        {
            if (mqttClient != null)
            {
                mqttClient.disconnect();
            }
        } 
        catch (MqttPersistenceException e) 
        {
            Log.e("mqtt", "disconnect failed - persistence exception", e);
        }
        finally
        {
            mqttClient = null;
        }
        
        // we can now remove the ongoing notification that warns users that
        //  there was a long-running ongoing service running
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancelAll();
    }



    /*
     * Checks if the MQTT client thinks it has an active connection
     */
    private boolean isAlreadyConnected()
    {
        return ((mqttClient != null) && (mqttClient.isConnected() == true));
    }
    /*mycomment*/

    private class BackgroundDataChangeIntentReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context ctx, Intent intent) 
        {
            // we protect against the phone switching off while we're doing this
            //  by requesting a wake lock - we request the minimum possible wake 
            //  lock - just enough to keep the CPU running until we've finished
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
            wl.acquire();
   
            ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if (cm.getBackgroundDataSetting()) 
            {
                // user has allowed background data - we start again - picking 
                //  up where we left off in handleStart before
                defineConnectionToBroker(brokerHostName);
                handleStart(intent, 0);
            }
            else 
            {
                // user has disabled background data
                connectionStatus = MQTTConnectionStatus.NOTCONNECTED_DATADISABLED;
                
                // update the app to show that the connection has been disabled
                broadcastServiceStatus("Not connected - background data disabled");
                
                // disconnect from the broker
                disconnectFromBroker();
            }            
            
            // we're finished - if the phone is switched off, it's okay for the CPU 
            //  to sleep now            
            wl.release();
        }        
    }

    /*
     * Called in response to a change in network connection - after losing a 
     *  connection to the server, this allows us to wait until we have a usable
     *  data connection again
     */

    /*mycomment*/

    private class NetworkConnectionIntentReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context ctx, Intent intent) 
        {
            // we protect against the phone switching off while we're doing this
            //  by requesting a wake lock - we request the minimum possible wake 
            //  lock - just enough to keep the CPU running until we've finished
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
            wl.acquire();
            
            if (isOnline())
            {
                // we have an internet connection - have another try at connecting
                //MY CODE
                if (mqttClient==null){
                    defineConnectionToBroker(brokerHostName);
                }
                //MY CODE END
                if (connectToBroker())
                {
                    // we subscribe to a topic - registering to receive push
                    //  notifications with a particular key
                    subscribeToTopic(topicName);
                }
            }
            
            // we're finished - if the phone is switched off, it's okay for the CPU 
            //  to sleep now
            wl.release();
        }
    }


    /*
     * Schedule the next time that you want the phone to wake up and ping the 
     *  message broker server
     */
    private void scheduleNextPing()
    {
        // When the phone is off, the CPU may be stopped. This means that our 
        //   code may stop running.
        // When connecting to the message broker, we specify a 'keep alive' 
        //   period - a period after which, if the client has not contacted
        //   the server, even if just with a ping, the connection is considered
        //   broken.
        // To make sure the CPU is woken at least once during each keep alive
        //   period, we schedule a wake up to manually ping the server
        //   thereby keeping the long-running connection open
        // Normally when using this Java MQTT client library, this ping would be
        //   handled for us. 
        // Note that this may be called multiple times before the next scheduled
        //   ping has fired. This is good - the previously scheduled one will be
        //   cancelled in favour of this one.
        // This means if something else happens during the keep alive period, 
        //   (e.g. we receive an MQTT message), then we start a new keep alive
        //   period, postponing the next ping.
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, 
                                                                 new Intent(MQTT_PING_ACTION), 
                                                                 PendingIntent.FLAG_UPDATE_CURRENT);
        
        // in case it takes us a little while to do this, we try and do it 
        //  shortly before the keep alive period expires
        // it means we're pinging slightly more frequently than necessary 
        Calendar wakeUpTime = Calendar.getInstance();
        wakeUpTime.add(Calendar.SECOND, keepAliveSeconds);
        
        AlarmManager aMgr = (AlarmManager) getSystemService(ALARM_SERVICE);        
        aMgr.set(AlarmManager.RTC_WAKEUP,  
                 wakeUpTime.getTimeInMillis(),                 
                 pendingIntent);
    }
    
    
    /*
     * Used to implement a keep-alive protocol at this Service level - it sends 
     *  a PING message to the server, then schedules another ping after an 
     *  interval defined by keepAliveSeconds
     */
    /*mycomment*/

    public class PingSender extends BroadcastReceiver 
    {
        @Override
        public void onReceive(Context context, Intent intent) 
        {
            // Note that we don't need a wake lock for this method (even though
            //  it's important that the phone doesn't switch off while we're
            //  doing this).
            // According to the docs, "Alarm Manager holds a CPU wake lock as 
            //  long as the alarm receiver's onReceive() method is executing. 
            //  This guarantees that the phone will not sleep until you have 
            //  finished handling the broadcast."
            // This is good enough for our needs.
            
            try 
            {
                mqttClient.ping();                
            } 
            catch (MqttException e)
            {
                // if something goes wrong, it should result in connectionLost
                //  being called, so we will handle it there
                Log.e("mqtt", "ping failed - MQTT exception", e);
                
                // assume the client connection is broken - trash it
                try {                    
                    mqttClient.disconnect();
                } 
                catch (MqttPersistenceException e1) {
                    Log.e("mqtt", "disconnect failed - persistence exception", e1);                
                }
                
                // reconnect
                if (connectToBroker()) {
                    subscribeToTopic(topicName);
                }                
            }

            // start the next keep alive period 
            scheduleNextPing();
        }
    }

    /************************************************************************/
    /*   APP SPECIFIC - stuff that would vary for different uses of MQTT    */
    /************************************************************************/
    
    //  apps that handle very small amounts of data - e.g. updates and 
    //   notifications that don't need to be persisted if the app / phone
    //   is restarted etc. may find it acceptable to store this data in a 
    //   variable in the Service
    //  that's what I'm doing in this sample: storing it in a local hashtable 
    //  if you are handling larger amounts of data, and/or need the data to
    //   be persisted even if the app and/or phone is restarted, then 
    //   you need to store the data somewhere safely
    //  see http://developer.android.com/guide/topics/data/data-storage.html
    //   for your storage options - the best choice depends on your needs 
    
    // stored internally
    
    private Hashtable<String, String> dataCache = new Hashtable<String, String>(); 

    private boolean addReceivedMessageToStore(String key, String value) 
    {
        String previousValue = null;
        
        if (value.length() == 0)
        {
            previousValue = dataCache.remove(key);
        }
        else
        {
            previousValue = dataCache.put(key, value);
        }
        
        // is this a new value? or am I receiving something I already knew?
        //  we return true if this is something new
        return ((previousValue == null) || 
                (previousValue.equals(value) == false));
    }

    // provide a public interface, so Activities that bind to the Service can 
    //  request access to previously received messages
    
    public void rebroadcastReceivedMessages() 
    {
        Enumeration<String> e = dataCache.keys();         
        while(e.hasMoreElements()) 
        {
            String nextKey = e.nextElement();
            String nextValue = dataCache.get(nextKey);
            
            broadcastReceivedMessage(nextKey, nextValue);
        }
    }
    

    /************************************************************************/
    /*    METHODS - internal utility methods                                */
    /************************************************************************/    
    
    private String generateClientId()
    {
        // generate a unique client id if we haven't done so before, otherwise
        //   re-use the one we already have

        if (mqttClientId == null)
        {
            // generate a unique client ID - I'm basing this on a combination of 
            //  the phone device id and the current timestamp         
            String timestamp = "" + (new Date()).getTime();
            String android_id = Settings.System.getString(getContentResolver(), 
                                                          Secure.ANDROID_ID);        
            mqttClientId = timestamp + android_id;
            
            // truncate - MQTT spec doesn't allow client ids longer than 23 chars
            if (mqttClientId.length() > MAX_MQTT_CLIENTID_LENGTH) {
                mqttClientId = mqttClientId.substring(0, MAX_MQTT_CLIENTID_LENGTH);
            }
        }
        
        return mqttClientId;
    }
    
    private boolean isOnline() 
    {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if(cm.getActiveNetworkInfo() != null &&
           cm.getActiveNetworkInfo().isAvailable() &&
           cm.getActiveNetworkInfo().isConnected())
        {
            return true;
        }
        
        return false;
    }   
}