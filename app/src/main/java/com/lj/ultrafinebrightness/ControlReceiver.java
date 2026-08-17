package com.lj.ultrafinebrightness;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class ControlReceiver extends BroadcastReceiver {
    public static final String ACTION_TOGGLE_AUTO="com.lj.ultrafinebrightness.action.TOGGLE_AUTO";
    public static final String ACTION_CYCLE_PROFILE="com.lj.ultrafinebrightness.action.CYCLE_PROFILE";
    @Override public void onReceive(Context context,Intent intent){
        SharedPreferences p=context.getSharedPreferences("als_prefs",Context.MODE_PRIVATE);
        String a=intent.getAction();
        if(ACTION_TOGGLE_AUTO.equals(a)){
            p.edit().putBoolean("auto_enabled",!p.getBoolean("auto_enabled",false)).apply();
        }else if(ACTION_CYCLE_PROFILE.equals(a)){
            String cur=p.getString("profile","Day");String next="Day".equals(cur)?"Night":"Night".equals(cur)?"Custom":"Day";p.edit().putString("profile",next).apply();
        }
        Intent s=new Intent(context,UltraFineService.class).setAction(UltraFineService.ACTION_REFRESH);
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(s);else context.startService(s);
        context.sendBroadcast(new Intent(UltraFineService.ACTION_UI_SYNC).setPackage(context.getPackageName()));
    }
}
