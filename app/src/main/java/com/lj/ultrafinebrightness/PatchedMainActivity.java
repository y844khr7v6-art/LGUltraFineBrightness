package com.lj.ultrafinebrightness;

import android.os.Bundle;
import android.view.View;
import android.widget.PopupMenu;
import android.content.SharedPreferences;

public class PatchedMainActivity extends MainActivity {
    private SharedPreferences prefs;
    private View expertSection;

    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        prefs=getSharedPreferences("als_prefs",MODE_PRIVATE);
        expertSection=findViewById(R.id.expertSection);
        View overflow=findViewById(R.id.overflowMenu);
        if(overflow!=null) overflow.setOnClickListener(this::showOverflowMenu);
    }

    private void showOverflowMenu(View anchor){
        PopupMenu popup=new PopupMenu(this,anchor);
        boolean expert=prefs.getBoolean("expert_mode",false);
        boolean notif=prefs.getBoolean("notification_enabled",false);
        popup.getMenu().add(0,1,0,"Expert Mode").setCheckable(true).setChecked(expert);
        popup.getMenu().add(0,2,1,"Persistent notification").setCheckable(true).setChecked(notif);
        popup.getMenu().add(0,3,2,"Open HID Lab");
        popup.setOnMenuItemClickListener(item->{
            if(item.getItemId()==1){
                boolean on=!prefs.getBoolean("expert_mode",false);
                prefs.edit().putBoolean("expert_mode",on).apply();
                if(expertSection!=null) expertSection.setVisibility(on?View.VISIBLE:View.GONE);
                return true;
            }
            if(item.getItemId()==2){
                boolean on=!prefs.getBoolean("notification_enabled",false);
                prefs.edit().putBoolean("notification_enabled",on).apply();
                return true;
            }
            if(item.getItemId()==3){
                prefs.edit().putBoolean("expert_mode",true).apply();
                if(expertSection!=null){expertSection.setVisibility(View.VISIBLE);expertSection.requestFocus();}
                return true;
            }
            return false;
        });
        popup.show();
    }
}
