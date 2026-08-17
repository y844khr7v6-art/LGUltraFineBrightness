package com.lj.ultrafinebrightness;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int LG_VID=0x043e, LG_PID=0x9a63, BRIGHTNESS_INTERFACE_ID=1, MAX_RAW=54000;
    private static final String ACTION_USB_PERMISSION="com.lj.ultrafinebrightness.USB_PERMISSION";
    private UsbManager usbManager; private UsbDevice device; private UsbInterface brightnessInterface; private UsbDeviceConnection connection;
    private TextView status,value; private SeekBar slider; private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean suppressSlider=false; private final Runnable pendingWrite=()->setBrightnessPercent(slider.getProgress(),false);

    private final BroadcastReceiver receiver=new BroadcastReceiver(){ public void onReceive(Context c,Intent i){
        String a=i.getAction();
        if(ACTION_USB_PERMISSION.equals(a)){ UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE); if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)&&d!=null){device=d;openDevice();}else setStatus("USB permission denied"); }
        else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)){ UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE); if(d!=null&&isTarget(d)){closeConnection();setStatus("UltraFine disconnected — waiting for reconnect");} }
        else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)){ UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE); if(d!=null&&isTarget(d)){device=d;ensurePermissionAndOpen();} }
    }};

    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_main);usbManager=(UsbManager)getSystemService(Context.USB_SERVICE);status=findViewById(R.id.status);value=findViewById(R.id.value);slider=findViewById(R.id.slider);
        IntentFilter f=new IntentFilter(ACTION_USB_PERMISSION);f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);registerReceiver(receiver,f);
        ((Button)findViewById(R.id.reconnect)).setOnClickListener(v->findAndConnect()); ((Button)findViewById(R.id.read)).setOnClickListener(v->readBrightness());
        findViewById(R.id.b25).setOnClickListener(v->setBrightnessPercent(25,true));findViewById(R.id.b50).setOnClickListener(v->setBrightnessPercent(50,true));findViewById(R.id.b75).setOnClickListener(v->setBrightnessPercent(75,true));findViewById(R.id.b100).setOnClickListener(v->setBrightnessPercent(100,true));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean fromUser){value.setText(p+"%");if(fromUser&&!suppressSlider){handler.removeCallbacks(pendingWrite);handler.postDelayed(pendingWrite,35);}}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){handler.removeCallbacks(pendingWrite);setBrightnessPercent(s.getProgress(),false);}});
        UsbDevice d=getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);if(d!=null&&isTarget(d)){device=d;ensurePermissionAndOpen();}else findAndConnect();
    }
    private boolean isTarget(UsbDevice d){return d.getVendorId()==LG_VID&&d.getProductId()==LG_PID;}
    private void findAndConnect(){closeConnection();device=null;for(UsbDevice d:usbManager.getDeviceList().values())if(isTarget(d)){device=d;break;}if(device==null){setStatus("LG UltraFine Controls 043e:9a63 not found");return;}setStatus("UltraFine found — connecting…");ensurePermissionAndOpen();}
    private void ensurePermissionAndOpen(){if(usbManager.hasPermission(device)){openDevice();return;}PendingIntent pi=PendingIntent.getBroadcast(this,0,new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_IMMUTABLE);usbManager.requestPermission(device,pi);}
    private void openDevice(){closeConnection();for(int i=0;i<device.getInterfaceCount();i++){UsbInterface x=device.getInterface(i);if(x.getId()==1&&x.getInterfaceClass()==3){brightnessInterface=x;break;}}if(brightnessInterface==null){setStatus("HID BRIGHTNESS interface 1 missing");return;}connection=usbManager.openDevice(device);if(connection==null||!connection.claimInterface(brightnessInterface,true)){setStatus("Could not claim HID BRIGHTNESS interface 1");closeConnection();return;}setStatus("CONNECTED · HID BRIGHTNESS interface 1 claimed");handler.postDelayed(this::readBrightness,100);}
    private void setBrightnessPercent(int p,boolean updateSlider){if(!ready())return;p=Math.max(0,Math.min(100,p));int raw=Math.round((p/100f)*MAX_RAW);byte[] r=new byte[6];r[0]=(byte)(raw&255);r[1]=(byte)((raw>>8)&255);int n=connection.controlTransfer(0x21,0x09,0x0300,brightnessInterface.getId(),r,6,1000);if(n==6){if(updateSlider){suppressSlider=true;slider.setProgress(p);suppressSlider=false;}value.setText(p+"%");setStatus("SET OK · "+p+"% · raw "+raw);}else setStatus("SET FAILED · controlTransfer returned "+n);}
    private void readBrightness(){if(!ready())return;byte[] r=new byte[6];int n=connection.controlTransfer(0xA1,0x01,0x0300,brightnessInterface.getId(),r,6,1000);if(n>=2){int raw=(r[0]&255)|((r[1]&255)<<8);int p=Math.max(0,Math.min(100,Math.round(raw*100f/MAX_RAW)));suppressSlider=true;slider.setProgress(p);suppressSlider=false;value.setText(p+"%");setStatus("READ OK · "+p+"% · raw "+raw);}else setStatus("READ FAILED · controlTransfer returned "+n);}
    private boolean ready(){if(connection==null||brightnessInterface==null){setStatus("Not connected — tap Reconnect");return false;}return true;} private void setStatus(String s){status.setText(s);}
    private void closeConnection(){handler.removeCallbacks(pendingWrite);if(connection!=null){if(brightnessInterface!=null)try{connection.releaseInterface(brightnessInterface);}catch(Exception ignored){}connection.close();}connection=null;brightnessInterface=null;}
    @Override protected void onResume(){super.onResume();if(connection==null)handler.postDelayed(this::findAndConnect,150);}
    @Override protected void onDestroy(){closeConnection();try{unregisterReceiver(receiver);}catch(Exception ignored){}super.onDestroy();}
}
