package com.lj.ultrafinebrightness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.service.quicksettings.TileService;

public class UltraFineService extends Service {
    public static final String ACTION_REFRESH="com.lj.ultrafinebrightness.REFRESH_SERVICE";
    public static final String ACTION_UI_SYNC="com.lj.ultrafinebrightness.UI_SYNC";
    private static final String ACTION_USB_PERMISSION="com.lj.ultrafinebrightness.SERVICE_USB_PERMISSION";
    private static final int LG_VID=0x043e, LG_PID=0x9a63, MAX_RAW=54000;
    private static final int IF_BRIGHTNESS=1, IF_ALS=2;
    private static final int NOTIFICATION_ID=106;
    private static final String CHANNEL_ID="ultrafine_controls";

    private UsbManager usbManager;
    private SharedPreferences prefs;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface brightnessInterface,alsInterface;
    private UsbEndpoint alsIn;
    private volatile boolean running=false;
    private Thread worker;
    private double filteredLux=-1;
    private int lastBrightness=50;
    private long lastWrite=0;

    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String a=i.getAction();
        if(ACTION_USB_PERMISSION.equals(a)){
            UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)&&d!=null){device=d;openDevice();}
        }else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)){
            UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(d!=null&&isTarget(d)){closeUsb();updateNotification();}
        }else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)){
            UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(d!=null&&isTarget(d)){device=d;ensurePermission();}
        }
    }};

    @Override public void onCreate(){
        super.onCreate();
        usbManager=(UsbManager)getSystemService(USB_SERVICE);
        prefs=getSharedPreferences("als_prefs",MODE_PRIVATE);
        createChannel();
        IntentFilter f=new IntentFilter(ACTION_USB_PERMISSION);f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);registerReceiver(receiver,f);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        boolean auto=prefs.getBoolean("auto_enabled",false);
        boolean persistent=prefs.getBoolean("notification_enabled",false);
        if(!auto&&!persistent){stopForeground(true);stopSelf();return START_NOT_STICKY;}
        startForeground(NOTIFICATION_ID,buildNotification());
        findAndConnect();
        requestTileRefresh();
        return START_STICKY;
    }

    private void findAndConnect(){
        if(connection!=null){syncWorkerState();updateNotification();return;}
        device=null;
        for(UsbDevice d:usbManager.getDeviceList().values())if(isTarget(d)){device=d;break;}
        if(device==null){updateNotification();return;}
        ensurePermission();
    }
    private boolean isTarget(UsbDevice d){return d.getVendorId()==LG_VID&&d.getProductId()==LG_PID;}
    private void ensurePermission(){
        if(device==null)return;
        if(usbManager.hasPermission(device)){openDevice();return;}
        PendingIntent pi=PendingIntent.getBroadcast(this,0,new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        usbManager.requestPermission(device,pi);
    }
    private void openDevice(){
        closeUsb();
        for(int i=0;i<device.getInterfaceCount();i++){
            UsbInterface x=device.getInterface(i);
            if(x.getInterfaceClass()!=UsbConstants.USB_CLASS_HID)continue;
            if(x.getId()==IF_BRIGHTNESS)brightnessInterface=x;else if(x.getId()==IF_ALS)alsInterface=x;
        }
        if(brightnessInterface==null)return;
        connection=usbManager.openDevice(device);if(connection==null)return;
        if(!connection.claimInterface(brightnessInterface,true)){closeUsb();return;}
        if(alsInterface!=null&&connection.claimInterface(alsInterface,true)){
            for(int e=0;e<alsInterface.getEndpointCount();e++){
                UsbEndpoint ep=alsInterface.getEndpoint(e);
                if(ep.getDirection()==UsbConstants.USB_DIR_IN&&ep.getType()==UsbConstants.USB_ENDPOINT_XFER_INT){alsIn=ep;break;}
            }
        }
        readBrightness();syncWorkerState();updateNotification();
    }

    private void syncWorkerState(){
        boolean auto=prefs.getBoolean("auto_enabled",false);
        if(auto&&connection!=null&&alsIn!=null&&!running)startAls();
        if(!auto&&running)stopAls();
    }
    private void startAls(){
        running=true;final UsbDeviceConnection c=connection;final UsbEndpoint ep=alsIn;
        worker=new Thread(()->{
            byte[] b=new byte[6];
            while(running&&connection==c){
                int n=c.bulkTransfer(ep,b,b.length,1200);
                if(n>=6){
                    long lux=((long)b[2]&255)|(((long)b[3]&255)<<8)|(((long)b[4]&255)<<16)|(((long)b[5]&255)<<24);
                    filteredLux=filteredLux<0?lux:(filteredLux*0.82+lux*0.18);
                    applyAuto();
                }
            }
        },"UltraFine-Auto-ALS");worker.start();
    }
    private void stopAls(){running=false;if(worker!=null)worker.interrupt();worker=null;}

    private void readBrightness(){
        if(connection==null||brightnessInterface==null)return;
        byte[] r=new byte[6];int n=connection.controlTransfer(0xA1,0x01,0x0300,brightnessInterface.getId(),r,6,800);
        if(n>=2){int raw=(r[0]&255)|((r[1]&255)<<8);lastBrightness=Math.max(0,Math.min(100,Math.round(raw*100f/MAX_RAW)));}
    }
    private void setBrightness(int p){
        if(connection==null||brightnessInterface==null)return;
        p=Math.max(0,Math.min(100,p));int raw=Math.round((p/100f)*MAX_RAW);byte[] r=new byte[6];r[0]=(byte)(raw&255);r[1]=(byte)((raw>>8)&255);
        int n=connection.controlTransfer(0x21,0x09,0x0300,brightnessInterface.getId(),r,6,800);if(n==6){lastBrightness=p;updateNotification();}
    }
    private void applyAuto(){
        if(!prefs.getBoolean("auto_enabled",false)||filteredLux<0)return;
        String profile=prefs.getString("profile","Day");int target=targetBrightness(filteredLux,profile);int diff=target-lastBrightness;if(Math.abs(diff)<3)return;
        long now=System.currentTimeMillis();if(now-lastWrite<700)return;lastWrite=now;int step=Math.min(3,Math.abs(diff));setBrightness(lastBrightness+(diff>0?step:-step));
    }
    private int targetBrightness(double lux,String profile){
        double l=Math.max(0,Math.min(4096,lux));double norm=Math.log1p(l)/Math.log1p(4096.0);int min,max;
        if("Night".equals(profile)){min=5;max=55;}else if("Custom".equals(profile)){min=8;max=90;}else{min=12;max=100;}
        int base=(int)Math.round(min+(max-min)*norm);int offset=prefs.getInt("offset_"+profile.toLowerCase(),0);return Math.max(min,Math.min(max,base+offset));
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"UltraFine controls",NotificationManager.IMPORTANCE_LOW);ch.setDescription("Auto ALS and quick monitor controls");((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);}
    }
    private Notification buildNotification(){
        boolean auto=prefs.getBoolean("auto_enabled",false);String profile=prefs.getString("profile","Day");
        String lux=filteredLux>=0?(Math.round(filteredLux)>=4096?"4096+ lux":Math.round(filteredLux)+" lux"):"— lux";
        String text=(auto?"Auto":"Manual")+" · "+profile+" · "+lastBrightness+"% · "+lux+(connection==null?" · disconnected":"");
        Intent open=new Intent(this,MainActivity.class);PendingIntent openPi=PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent toggle=new Intent(this,ControlReceiver.class).setAction(ControlReceiver.ACTION_TOGGLE_AUTO);PendingIntent togglePi=PendingIntent.getBroadcast(this,2,toggle,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent profileIntent=new Intent(this,ControlReceiver.class).setAction(ControlReceiver.ACTION_CYCLE_PROFILE);PendingIntent profilePi=PendingIntent.getBroadcast(this,3,profileIntent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher_ultrafine).setContentTitle("LG UltraFine").setContentText(text).setContentIntent(openPi).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null,auto?"Disable Auto":"Enable Auto",togglePi).build())
            .addAction(new Notification.Action.Builder(null,"Profile: "+profile,profilePi).build());
        return b.build();
    }
    private void updateNotification(){
        boolean auto=prefs.getBoolean("auto_enabled",false),persistent=prefs.getBoolean("notification_enabled",false);
        if(auto||persistent)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID,buildNotification());
    }
    private void requestTileRefresh(){
        TileService.requestListeningState(this,new ComponentName(this,AutoTileService.class));
        TileService.requestListeningState(this,new ComponentName(this,ProfileTileService.class));
        sendBroadcast(new Intent(ACTION_UI_SYNC).setPackage(getPackageName()));
    }
    private void closeUsb(){
        stopAls();
        if(connection!=null){if(brightnessInterface!=null)try{connection.releaseInterface(brightnessInterface);}catch(Exception ignored){}if(alsInterface!=null)try{connection.releaseInterface(alsInterface);}catch(Exception ignored){}connection.close();}
        connection=null;brightnessInterface=null;alsInterface=null;alsIn=null;filteredLux=-1;
    }
    @Override public void onDestroy(){closeUsb();try{unregisterReceiver(receiver);}catch(Exception ignored){}super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
