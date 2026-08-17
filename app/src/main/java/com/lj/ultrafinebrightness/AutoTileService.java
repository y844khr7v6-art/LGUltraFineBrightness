package com.lj.ultrafinebrightness;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class AutoTileService extends TileService {
    @Override public void onStartListening(){super.onStartListening();refresh();}
    @Override public void onClick(){
        super.onClick();SharedPreferences p=getSharedPreferences("als_prefs",MODE_PRIVATE);boolean next=!p.getBoolean("auto_enabled",false);p.edit().putBoolean("auto_enabled",next).apply();
        Intent s=new Intent(this,UltraFineService.class).setAction(UltraFineService.ACTION_REFRESH);if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);refresh();
        sendBroadcast(new Intent(UltraFineService.ACTION_UI_SYNC).setPackage(getPackageName()));
    }
    private void refresh(){Tile t=getQsTile();if(t==null)return;SharedPreferences p=getSharedPreferences("als_prefs",MODE_PRIVATE);boolean auto=p.getBoolean("auto_enabled",false);String profile=p.getString("profile","Day");t.setState(auto?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);t.setLabel("UltraFine Auto");if(Build.VERSION.SDK_INT>=29)t.setSubtitle((auto?"On · ":"Off · ")+profile);t.updateTile();}
}
