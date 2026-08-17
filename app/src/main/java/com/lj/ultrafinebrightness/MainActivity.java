package com.lj.ultrafinebrightness;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;
import android.view.View;

public class MainActivity extends Activity {
    private static final int LG_VID=0x043e, LG_PID=0x9a63, MAX_RAW=54000;
    private static final int IF_I2C=0, IF_BRIGHTNESS=1, IF_ALS=2;
    private static final String ACTION_USB_PERMISSION="com.lj.ultrafinebrightness.USB_PERMISSION";
    private static final String[] PROFILES={"Day","Night","Custom"};

    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface i2cInterface, brightnessInterface, alsInterface;
    private UsbEndpoint alsInEndpoint;
    private TextView status,value,probeOutput,alsValue,alsRaw,autoInfo;
    private SeekBar slider;
    private Button autoToggle,resetPreference;
    private Spinner profileSpinner;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean suppressSlider=false,suppressProfile=false;
    private volatile boolean alsRunning=false;
    private Thread alsThread;
    private volatile long latestLux=-1;
    private double filteredLux=-1;
    private boolean autoEnabled=false;
    private String activeProfile="Day";
    private long lastAutoWrite=0;
    private int lastKnownBrightness=50;
    private final Runnable pendingWrite=()->setBrightnessPercent(slider.getProgress(),false,true);

    private final BroadcastReceiver receiver=new BroadcastReceiver(){ public void onReceive(Context c,Intent i){
        String a=i.getAction();
        if(ACTION_USB_PERMISSION.equals(a)){
            UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)&&d!=null){device=d;openDevice();}
            else setStatus("USB permission denied");
        } else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)){
            UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(d!=null&&isTarget(d)){closeConnection();setStatus("UltraFine disconnected — waiting for reconnect");}
        } else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)){
            UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(d!=null&&isTarget(d)){device=d;ensurePermissionAndOpen();}
        }
    }};

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);setContentView(R.layout.activity_main);
        prefs=getSharedPreferences("als_prefs",MODE_PRIVATE);
        autoEnabled=prefs.getBoolean("auto_enabled",false);
        activeProfile=prefs.getString("profile","Day");
        usbManager=(UsbManager)getSystemService(Context.USB_SERVICE);
        status=findViewById(R.id.status);value=findViewById(R.id.value);slider=findViewById(R.id.slider);probeOutput=findViewById(R.id.probeOutput);
        alsValue=findViewById(R.id.alsValue);alsRaw=findViewById(R.id.alsRaw);autoInfo=findViewById(R.id.autoInfo);
        autoToggle=findViewById(R.id.autoToggle);resetPreference=findViewById(R.id.resetPreference);profileSpinner=findViewById(R.id.profileSpinner);

        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,PROFILES);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);profileSpinner.setAdapter(adapter);
        for(int n=0;n<PROFILES.length;n++)if(PROFILES[n].equals(activeProfile)){suppressProfile=true;profileSpinner.setSelection(n);suppressProfile=false;break;}
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(suppressProfile)return;activeProfile=PROFILES[pos];prefs.edit().putString("profile",activeProfile).apply();updateAutoUi();if(autoEnabled)applyAutoBrightness();}public void onNothingSelected(AdapterView<?> p){}});
        autoToggle.setOnClickListener(v->{autoEnabled=!autoEnabled;prefs.edit().putBoolean("auto_enabled",autoEnabled).apply();updateAutoUi();if(autoEnabled){startAlsLive();applyAutoBrightness();}});
        resetPreference.setOnClickListener(v->{prefs.edit().remove(offsetKey()).apply();updateAutoUi();if(autoEnabled)applyAutoBrightness();});

        IntentFilter f=new IntentFilter(ACTION_USB_PERMISSION);f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);registerReceiver(receiver,f);
        ((Button)findViewById(R.id.reconnect)).setOnClickListener(v->findAndConnect());
        ((Button)findViewById(R.id.read)).setOnClickListener(v->readBrightness());
        ((Button)findViewById(R.id.probeAls)).setOnClickListener(v->probeInterface(IF_ALS,"HID ALS"));
        ((Button)findViewById(R.id.probeI2c)).setOnClickListener(v->probeInterface(IF_I2C,"HID I2C"));
        ((Button)findViewById(R.id.probeAll)).setOnClickListener(v->probeAllReadOnly());
        findViewById(R.id.b25).setOnClickListener(v->setBrightnessPercent(25,true,true));
        findViewById(R.id.b50).setOnClickListener(v->setBrightnessPercent(50,true,true));
        findViewById(R.id.b75).setOnClickListener(v->setBrightnessPercent(75,true,true));
        findViewById(R.id.b100).setOnClickListener(v->setBrightnessPercent(100,true,true));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean fromUser){value.setText(p+"%");if(fromUser&&!suppressSlider){handler.removeCallbacks(pendingWrite);handler.postDelayed(pendingWrite,35);}}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){handler.removeCallbacks(pendingWrite);setBrightnessPercent(s.getProgress(),false,true);}
        });
        updateAutoUi();
        UsbDevice d=getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);if(d!=null&&isTarget(d)){device=d;ensurePermissionAndOpen();}else findAndConnect();
    }

    private boolean isTarget(UsbDevice d){return d.getVendorId()==LG_VID&&d.getProductId()==LG_PID;}
    private void findAndConnect(){closeConnection();device=null;for(UsbDevice d:usbManager.getDeviceList().values())if(isTarget(d)){device=d;break;}if(device==null){setStatus("LG UltraFine Controls 043e:9a63 not found");return;}setStatus("UltraFine found — connecting…");ensurePermissionAndOpen();}
    private void ensurePermissionAndOpen(){if(usbManager.hasPermission(device)){openDevice();return;}PendingIntent pi=PendingIntent.getBroadcast(this,0,new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_IMMUTABLE);usbManager.requestPermission(device,pi);}

    private void openDevice(){
        closeConnection();
        for(int i=0;i<device.getInterfaceCount();i++){
            UsbInterface x=device.getInterface(i);if(x.getInterfaceClass()!=UsbConstants.USB_CLASS_HID)continue;
            if(x.getId()==IF_I2C)i2cInterface=x; else if(x.getId()==IF_BRIGHTNESS)brightnessInterface=x; else if(x.getId()==IF_ALS)alsInterface=x;
        }
        if(brightnessInterface==null){setStatus("HID BRIGHTNESS interface 1 missing");return;}
        connection=usbManager.openDevice(device);if(connection==null){setStatus("Could not open UltraFine USB control device");return;}
        boolean b=connection.claimInterface(brightnessInterface,true);boolean i=i2cInterface!=null&&connection.claimInterface(i2cInterface,true);boolean a=alsInterface!=null&&connection.claimInterface(alsInterface,true);
        if(a){for(int e=0;e<alsInterface.getEndpointCount();e++){UsbEndpoint ep=alsInterface.getEndpoint(e);if(ep.getDirection()==UsbConstants.USB_DIR_IN&&ep.getType()==UsbConstants.USB_ENDPOINT_XFER_INT){alsInEndpoint=ep;break;}}}
        if(!b){setStatus("Could not claim HID BRIGHTNESS interface 1");closeConnection();return;}
        setStatus("CONNECTED · brightness ✓ · I2C "+(i?"✓":"—")+" · ALS "+(a?"✓":"—"));
        handler.postDelayed(this::readBrightness,100);handler.postDelayed(this::probeAllReadOnly,250);handler.postDelayed(this::startAlsLive,350);
    }

    private void setBrightnessPercent(int p,boolean updateSlider,boolean userAction){
        if(!readyBrightness())return;p=Math.max(0,Math.min(100,p));int raw=Math.round((p/100f)*MAX_RAW);byte[] r=new byte[6];r[0]=(byte)(raw&255);r[1]=(byte)((raw>>8)&255);
        int n=connection.controlTransfer(0x21,0x09,0x0300,brightnessInterface.getId(),r,6,1000);
        if(n==6){lastKnownBrightness=p;if(updateSlider){suppressSlider=true;slider.setProgress(p);suppressSlider=false;}value.setText(p+"%");if(userAction&&autoEnabled&&filteredLux>=0)learnPreference(p);setStatus("SET OK · "+p+"% · raw "+raw);}else setStatus("SET FAILED · controlTransfer returned "+n);
    }

    private void readBrightness(){if(!readyBrightness())return;byte[] r=new byte[6];int n=connection.controlTransfer(0xA1,0x01,0x0300,brightnessInterface.getId(),r,6,1000);if(n>=2){int raw=(r[0]&255)|((r[1]&255)<<8);int p=Math.max(0,Math.min(100,Math.round(raw*100f/MAX_RAW)));lastKnownBrightness=p;suppressSlider=true;slider.setProgress(p);suppressSlider=false;value.setText(p+"%");setStatus("READ OK · "+p+"% · raw "+raw);}else setStatus("READ FAILED · controlTransfer returned "+n);}

    private void startAlsLive(){
        if(connection==null||alsInterface==null||alsInEndpoint==null||alsRunning)return;
        alsRunning=true;alsValue.setText("Listening…");final UsbDeviceConnection c=connection;final UsbEndpoint ep=alsInEndpoint;
        alsThread=new Thread(()->{byte[] b=new byte[6];while(alsRunning&&connection==c){int n=c.bulkTransfer(ep,b,b.length,1200);if(n>=6){int event=b[1]&255;long lux=((long)b[2]&255)|(((long)b[3]&255)<<8)|(((long)b[4]&255)<<16)|(((long)b[5]&255)<<24);latestLux=lux;filteredLux=filteredLux<0?lux:(filteredLux*0.82+lux*0.18);final long shown=lux;final String raw=toHex(b,n);handler.post(()->{alsValue.setText((shown>=4096?"4096+":String.valueOf(shown))+" lux");alsRaw.setText("Filtered "+Math.round(filteredLux)+" lux · "+raw);if(autoEnabled)applyAutoBrightness();});}}},"UltraFine-ALS");alsThread.start();
    }
    private void stopAlsLive(){alsRunning=false;if(alsThread!=null)alsThread.interrupt();alsThread=null;}

    private void learnPreference(int chosen){int baseline=baseBrightness(filteredLux,activeProfile);int offset=chosen-baseline;prefs.edit().putInt(offsetKey(),offset).apply();updateAutoUi();}
    private String offsetKey(){return "offset_"+activeProfile.toLowerCase();}
    private int learnedOffset(){return prefs.getInt(offsetKey(),0);}
    private int baseBrightness(double lux,String profile){
        double l=Math.max(0,Math.min(4096,lux));double norm=Math.log1p(l)/Math.log1p(4096.0);int min,max;
        if("Night".equals(profile)){min=5;max=55;}else if("Custom".equals(profile)){min=8;max=90;}else{min=12;max=100;}
        return (int)Math.round(min+(max-min)*norm);
    }
    private int targetBrightness(){int base=baseBrightness(filteredLux,activeProfile);int target=base+learnedOffset();int min="Night".equals(activeProfile)?5:("Custom".equals(activeProfile)?8:12);int max="Night".equals(activeProfile)?55:("Custom".equals(activeProfile)?90:100);return Math.max(min,Math.min(max,target));}
    private void applyAutoBrightness(){
        if(!autoEnabled||filteredLux<0||!readyBrightness())return;int target=targetBrightness();int diff=target-lastKnownBrightness;if(Math.abs(diff)<3)return;long now=System.currentTimeMillis();if(now-lastAutoWrite<700)return;lastAutoWrite=now;int step=Math.min(3,Math.abs(diff));int next=lastKnownBrightness+(diff>0?step:-step);setBrightnessPercent(next,true,false);updateAutoUi();
    }
    private void updateAutoUi(){if(autoToggle==null)return;autoToggle.setText(autoEnabled?"Disable Auto ALS":"Enable Auto ALS");String lux=filteredLux>=0?Math.round(filteredLux)+" lux":"— lux";String offset=learnedOffset()==0?"neutral":((learnedOffset()>0?"+":"")+learnedOffset()+" learned");autoInfo.setText((autoEnabled?"AUTO":"MANUAL")+" · "+activeProfile+" · "+lux+" · "+offset);}

    private void probeAllReadOnly(){if(connection==null)return;StringBuilder out=new StringBuilder("READ-ONLY HID PROBE\n043e:9a63 LG UltraFine Display Controls\n\n");out.append(describeInterface(i2cInterface,"Interface 0 · HID I2C"));out.append(describeInterface(brightnessInterface,"Interface 1 · HID BRIGHTNESS"));out.append(describeInterface(alsInterface,"Interface 2 · HID ALS"));probeOutput.setText(out.toString());}
    private void probeInterface(int id,String name){if(connection==null){setStatus("Not connected");return;}UsbInterface intf=id==IF_I2C?i2cInterface:id==IF_ALS?alsInterface:brightnessInterface;probeOutput.setText("READ-ONLY PROBE\n\n"+describeInterface(intf,"Interface "+id+" · "+name));}
    private String describeInterface(UsbInterface intf,String label){StringBuilder s=new StringBuilder();s.append(label).append("\n");if(intf==null){s.append("  unavailable / not claimed\n\n");return s.toString();}s.append("  class=").append(hex2(intf.getInterfaceClass())).append(" subclass=").append(hex2(intf.getInterfaceSubclass())).append(" protocol=").append(hex2(intf.getInterfaceProtocol())).append("\n");s.append("  endpoints=").append(intf.getEndpointCount()).append("\n");for(int e=0;e<intf.getEndpointCount();e++){UsbEndpoint ep=intf.getEndpoint(e);s.append("    ep").append(e).append(" addr=").append(hex2(ep.getAddress())).append(" dir=").append(ep.getDirection()==UsbConstants.USB_DIR_IN?"IN":"OUT").append(" type=").append(endpointType(ep.getType())).append(" maxPacket=").append(ep.getMaxPacketSize()).append(" interval=").append(ep.getInterval()).append("\n");}byte[] desc=new byte[2048];int dn=connection.controlTransfer(0x81,0x06,0x2200,intf.getId(),desc,desc.length,1000);s.append("  HID report descriptor: ").append(dn>=0?dn+" bytes":"FAILED "+dn).append("\n");if(dn>0)s.append("    ").append(toHex(desc,Math.min(dn,256))).append(dn>256?" …":"").append("\n");s.append("  GET_REPORT reads (no writes):\n");int found=0;for(int reportType:new int[]{1,3})for(int reportId=0;reportId<16;reportId++){byte[] b=new byte[64];int n=connection.controlTransfer(0xA1,0x01,(reportType<<8)|reportId,intf.getId(),b,b.length,120);if(n>0){s.append("    ").append(reportType==1?"INPUT":"FEATURE").append(" id=").append(reportId).append(" len=").append(n).append(" : ").append(toHex(b,n)).append("\n");found++;}}if(intf.getId()==IF_ALS)s.append("  decoded: report ID 1 = [event][uint32 LE illuminance lux], saturates at 4096\n");if(intf.getId()==IF_I2C)s.append("  decoded: vendor page 0xFF00, raw 64-byte reports; writes remain disabled\n");if(found==0)s.append("    no readable reports in IDs 0–15\n");s.append("\n");return s.toString();}

    private static String endpointType(int t){if(t==UsbConstants.USB_ENDPOINT_XFER_INT)return "INTERRUPT";if(t==UsbConstants.USB_ENDPOINT_XFER_BULK)return "BULK";if(t==UsbConstants.USB_ENDPOINT_XFER_ISOC)return "ISO";if(t==UsbConstants.USB_ENDPOINT_XFER_CONTROL)return "CONTROL";return String.valueOf(t);}
    private static String hex2(int n){return String.format("0x%02X",n&255);}
    private static String toHex(byte[] b,int n){StringBuilder s=new StringBuilder();for(int i=0;i<n;i++){if(i>0)s.append(' ');s.append(String.format("%02X",b[i]&255));}return s.toString();}
    private boolean readyBrightness(){if(connection==null||brightnessInterface==null){setStatus("Not connected — tap Reconnect");return false;}return true;}
    private void setStatus(String s){status.setText(s);}
    private void closeConnection(){stopAlsLive();handler.removeCallbacks(pendingWrite);if(connection!=null){for(UsbInterface x:new UsbInterface[]{i2cInterface,brightnessInterface,alsInterface})if(x!=null)try{connection.releaseInterface(x);}catch(Exception ignored){}connection.close();}connection=null;i2cInterface=null;brightnessInterface=null;alsInterface=null;alsInEndpoint=null;latestLux=-1;filteredLux=-1;}
    @Override protected void onResume(){super.onResume();if(connection==null)handler.postDelayed(this::findAndConnect,150);}
    @Override protected void onDestroy(){closeConnection();try{unregisterReceiver(receiver);}catch(Exception ignored){}super.onDestroy();}
}
