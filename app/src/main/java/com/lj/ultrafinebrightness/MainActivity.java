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
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int LG_VID = 0x043e;
    private static final int LG_PID = 0x9a63;
    private static final int BRIGHTNESS_INTERFACE_ID = 1;
    private static final int MAX_RAW = 54000;
    private static final String ACTION_USB_PERMISSION = "com.lj.ultrafinebrightness.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbDevice device;
    private UsbInterface brightnessInterface;
    private UsbDeviceConnection connection;
    private TextView status;
    private TextView value;
    private SeekBar slider;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice grantedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && grantedDevice != null) {
                device = grantedDevice;
                openDevice();
            } else {
                setStatus("USB permission denied");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        status = findViewById(R.id.status);
        value = findViewById(R.id.value);
        slider = findViewById(R.id.slider);

        registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        Button reconnect = findViewById(R.id.reconnect);
        Button read = findViewById(R.id.read);
        Button b25 = findViewById(R.id.b25);
        Button b50 = findViewById(R.id.b50);
        Button b75 = findViewById(R.id.b75);
        Button b100 = findViewById(R.id.b100);

        reconnect.setOnClickListener(v -> findAndConnect());
        read.setOnClickListener(v -> readBrightness());
        b25.setOnClickListener(v -> setBrightnessPercent(25));
        b50.setOnClickListener(v -> setBrightnessPercent(50));
        b75.setOnClickListener(v -> setBrightnessPercent(75));
        b100.setOnClickListener(v -> setBrightnessPercent(100));

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setBrightnessPercent(seekBar.getProgress());
            }
        });

        UsbDevice attached = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (attached != null && isTarget(attached)) {
            device = attached;
            ensurePermissionAndOpen();
        } else {
            findAndConnect();
        }
    }

    private boolean isTarget(UsbDevice d) {
        return d.getVendorId() == LG_VID && d.getProductId() == LG_PID;
    }

    private void findAndConnect() {
        closeConnection();
        device = null;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (isTarget(d)) {
                device = d;
                break;
            }
        }

        if (device == null) {
            setStatus("LG UltraFine Controls 043e:9a63 not found");
            return;
        }

        setStatus("Found 043e:9a63 — checking USB permission…");
        ensurePermissionAndOpen();
    }

    private void ensurePermissionAndOpen() {
        if (usbManager.hasPermission(device)) {
            openDevice();
            return;
        }

        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE
        );
        usbManager.requestPermission(device, permissionIntent);
    }

    private void openDevice() {
        brightnessInterface = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getId() == BRIGHTNESS_INTERFACE_ID && candidate.getInterfaceClass() == 3) {
                brightnessInterface = candidate;
                break;
            }
        }

        if (brightnessInterface == null) {
            setStatus("Found device, but HID BRIGHTNESS interface 1 is missing");
            return;
        }

        connection = usbManager.openDevice(device);
        if (connection == null) {
            setStatus("openDevice() failed");
            return;
        }

        boolean claimed = connection.claimInterface(brightnessInterface, true);
        if (!claimed) {
            setStatus("Found 043e:9a63, but claimInterface(1,true) FAILED");
            closeConnection();
            return;
        }

        setStatus("CONNECTED · HID BRIGHTNESS interface 1 claimed");
    }

    private void setBrightnessPercent(int percent) {
        if (!ready()) return;
        percent = Math.max(0, Math.min(100, percent));
        int raw = Math.round((percent / 100.0f) * MAX_RAW);
        byte[] report = new byte[6];
        report[0] = (byte) (raw & 0xff);
        report[1] = (byte) ((raw >> 8) & 0xff);

        int result = connection.controlTransfer(
                0x21,
                0x09,
                0x0300,
                brightnessInterface.getId(),
                report,
                report.length,
                1000
        );

        if (result == report.length) {
            slider.setProgress(percent);
            value.setText(percent + "%");
            setStatus("SET OK · " + percent + "% · raw " + raw + " · 6 bytes");
        } else {
            setStatus("SET FAILED · controlTransfer returned " + result);
        }
    }

    private void readBrightness() {
        if (!ready()) return;
        byte[] report = new byte[6];
        int result = connection.controlTransfer(
                0xA1,
                0x01,
                0x0300,
                brightnessInterface.getId(),
                report,
                report.length,
                1000
        );

        if (result >= 2) {
            int raw = (report[0] & 0xff) | ((report[1] & 0xff) << 8);
            int percent = Math.round((raw * 100.0f) / MAX_RAW);
            percent = Math.max(0, Math.min(100, percent));
            slider.setProgress(percent);
            value.setText(percent + "%");
            setStatus("READ OK · " + percent + "% · raw " + raw + " · " + result + " bytes");
        } else {
            setStatus("READ FAILED · controlTransfer returned " + result);
        }
    }

    private boolean ready() {
        if (connection == null || brightnessInterface == null) {
            setStatus("Not connected — tap Reconnect first");
            return false;
        }
        return true;
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void closeConnection() {
        if (connection != null) {
            if (brightnessInterface != null) {
                try { connection.releaseInterface(brightnessInterface); } catch (Exception ignored) { }
            }
            connection.close();
        }
        connection = null;
        brightnessInterface = null;
    }

    @Override
    protected void onDestroy() {
        closeConnection();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
