package com.lj.ultrafinebrightness;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class ProfileTileService extends TileService {
    @Override public void onStartListening(){super.onStartListening();refresh();}
    @Override public void onClick(){
        super.onClick();SharedPreferences p=getSharedPreferences("als_prefs",MODE_PRIVATE);String cur=p.getString("profile","Day");String next="Day".equals(cur)?"Night":"Night".equals(cur)?"Custom":"Day";p.edit().putString("profile",next).apply();
        Intent s=new Intent(this,UltraFineService.class).setAction(UltraFineService.ACTION_REFRESH);if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);refresh();
        sendBroadcast(new Intent(UltraFineService.ACTION_UI_SYNC).setPackage(getPackageName()));
    }
    private void refresh(){Tile t=getQsTile();if(t==null)return;SharedPreferences p=getSharedPreferences("als_prefs",MODE_PRIVATE);String profile=p.getString("profile","Day");t.setState(Tile.STATE_ACTIVE);t.setLabel("UltraFine Profile");if(Build.VERSION.SDK_INT>=29)t.setSubtitle(profile);t.updateTile();}
}
