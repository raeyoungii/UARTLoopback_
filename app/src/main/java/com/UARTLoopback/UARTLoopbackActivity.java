package com.UARTLoopback;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.EditText;

import java.text.SimpleDateFormat;
import java.util.Date;

public class UARTLoopbackActivity extends Activity {

    // menu item
    Menu myMenu;
    final int MENU_CONFIGURE = Menu.FIRST;
    final int MENU_CLEAN = Menu.FIRST + 1;

    final int FORMAT_HEX = 0;

    int inputFormat = FORMAT_HEX;

    StringBuffer readSB = new StringBuffer();

    /* thread to read the data */
    public handler_thread handlerThread;

    /* declare a FT312 UART interface variable */
    public FT311UARTInterface uartInterface;

    /* graphical objects */
    EditText readText;

    /* local variables */
    byte[] writeBuffer;
    byte[] readBuffer;
    char[] readBufferToChar;
    int[] actualNumBytes;

    int numBytes;
    byte count;
    byte status;
    byte writeIndex = 0;
    byte readIndex = 0;

    int baudRate; /* baud rate */
    byte stopBit; /* 1:1stop bits, 2:2 stop bits */
    byte dataBit; /* 8:8bit, 7: 7bit */
    byte parity; /* 0: none, 1: odd, 2: even, 3: mark, 4: space */
    byte flowControl; /* 0:none, 1: flow control(CTS,RTS) */
    public Context global_context;
    public boolean bConfiged = false;
    public SharedPreferences sharePrefSettings;
    Drawable originalDrawable;
    public String act_string;

    /* sensor Data */
    int gateway;
    int temperature;
    int humidity;
    int bio;
    int p_btn;
    int pir;
    int door;
    int fire;

    boolean btn_119 = false;
    boolean btn_call = false;
    boolean btn_cancel = false;
    boolean btn_carer = false;


    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        sharePrefSettings = getSharedPreferences("UARTLBPref", 0);
        //cleanPreference();
        /* create editable text objects */
        readText = (EditText) findViewById(R.id.ReadValues);

        global_context = this;

        /* allocate buffer */
        writeBuffer = new byte[64];
        readBuffer = new byte[4096];
        readBufferToChar = new char[4096];
        actualNumBytes = new int[1];

        /* setup the baud rate list */
        baudRate = 115200;

        /* stop bits */
        stopBit = 1;

        /* data bits */
        dataBit = 8;

        /* parity */
        parity = 0;

        /* flow control */
        flowControl = 0;

        act_string = getIntent().getAction();
        if (-1 != act_string.indexOf("android.intent.action.MAIN")) {
            restorePreference();
        } else if (-1 != act_string.indexOf("android.hardware.usb.action.USB_ACCESSORY_ATTACHED")) {
            cleanPreference();
        }

        uartInterface = new FT311UARTInterface(this, sharePrefSettings);

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        handlerThread = new handler_thread(handler);
        handlerThread.start();
    }

    protected void cleanPreference() {
        SharedPreferences.Editor editor = sharePrefSettings.edit();
        editor.remove("configed");
        editor.remove("baudRate");
        editor.remove("stopBit");
        editor.remove("dataBit");
        editor.remove("parity");
        editor.remove("flowControl");
        editor.commit();
    }

    protected void savePreference() {
        if (true == bConfiged) {
            sharePrefSettings.edit().putString("configed", "TRUE").commit();
            sharePrefSettings.edit().putInt("baudRate", baudRate).commit();
            sharePrefSettings.edit().putInt("stopBit", stopBit).commit();
            sharePrefSettings.edit().putInt("dataBit", dataBit).commit();
            sharePrefSettings.edit().putInt("parity", parity).commit();
            sharePrefSettings.edit().putInt("flowControl", flowControl).commit();
        } else {
            sharePrefSettings.edit().putString("configed", "FALSE").commit();
        }
    }

    protected void restorePreference() {
        String key_name = sharePrefSettings.getString("configed", "");
        if (true == key_name.contains("TRUE")) {
            bConfiged = true;
        } else {
            bConfiged = false;
        }

        baudRate = sharePrefSettings.getInt("baudRate", 115200);
        stopBit = (byte) sharePrefSettings.getInt("stopBit", 1);
        dataBit = (byte) sharePrefSettings.getInt("dataBit", 8);
        parity = (byte) sharePrefSettings.getInt("parity", 0);
        flowControl = (byte) sharePrefSettings.getInt("flowControl", 0);
    }


    //@Override
    public void onHomePressed() {
        onBackPressed();
    }

    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        // Ideally should implement onResume() and onPause()
        // to take appropriate action when the activity looses focus
        super.onResume();
        if (2 == uartInterface.ResumeAccessory()) {
            cleanPreference();
            restorePreference();
        }
    }

    @Override
    protected void onPause() {
        // Ideally should implement onResume() and onPause()
        // to take appropriate action when the activity looses focus
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        uartInterface.DestroyAccessory(bConfiged);
        super.onDestroy();
    }


    final Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            for (int i = 0; i < actualNumBytes[0]; i++) {
                readBufferToChar[i] = (char) readBuffer[i];
            }
            appendData(readBufferToChar, actualNumBytes[0]);
        }
    };

    /* usb input data handler */
    private class handler_thread extends Thread {
        Handler mHandler;

        /* constructor */
        handler_thread(Handler h) {
            mHandler = h;
        }

        public void run() {
            Message msg;

            while (true) {

                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                }

                status = uartInterface.ReadData(4096, readBuffer, actualNumBytes);

                if (status == 0x00 && actualNumBytes[0] > 0) {
                    msg = mHandler.obtainMessage();
                    mHandler.sendMessage(msg);
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        myMenu = menu;
        myMenu.add(0, MENU_CONFIGURE, 0, "Configure");
        myMenu.add(0, MENU_CLEAN, 0, "Clean Field");
        return super.onCreateOptionsMenu(myMenu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_CONFIGURE:
                if (false == bConfiged) {
                    bConfiged = true;
                    uartInterface.SetConfig(baudRate, dataBit, stopBit, parity, flowControl);
                    savePreference();
                }
                break;

            case MENU_CLEAN:
            default:
                readSB.delete(0, readSB.length());
                readText.setText(readSB);
                break;
        }

        return super.onOptionsItemSelected(item);
    }


    public void appendData(char[] data, int len) {
        if (len >= 1) {
            readSB.append(String.copyValueOf(data, 0, len));
        }

        char[] ch = readSB.toString().toCharArray();
        String temp;
        StringBuilder tmpSB = new StringBuilder();
        String[] tmpArr = new String[ch.length];

        for (int i = 0; i < ch.length; i++) {
            temp = String.format("%02x", (int) ch[i]);

            if (temp.length() == 4) {
                tmpSB.append(temp.substring(2, 4));
                tmpArr[i] = temp.substring(2, 4);
            } else {
                tmpSB.append(temp);
                tmpArr[i] = temp;
            }

            if (i + 1 < ch.length) {
                tmpSB.append(" ");
            }
        }

        /* current time */
        StringBuilder tmpSB2 = new StringBuilder();
        long now = System.currentTimeMillis();
        Date date = new Date(now);
        SimpleDateFormat mFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        String formatDate = mFormat.format(date) + "\n\n";

        tmpSB2.append(formatDate);
        tmpSB2.append(tmpSB);

        // TODO: 데이터 파싱 후 DB에 삽입
        /* check cmd */
        String cmd = tmpArr[5];

        /* Periodic Report */
        if (true == cmd.equals("30")) {
            gateway = Integer.parseInt(tmpArr[12] + tmpArr[13], 16);
            temperature = Integer.parseInt(tmpArr[26] + tmpArr[27], 16);
            humidity = Integer.parseInt(tmpArr[40] + tmpArr[41], 16);
            bio = Integer.parseInt(tmpArr[54] + tmpArr[55], 16);
            p_btn = Integer.parseInt(tmpArr[68] + tmpArr[69], 16);
            pir = Integer.parseInt(tmpArr[82] + tmpArr[83], 16);
            door = Integer.parseInt(tmpArr[96] + tmpArr[97], 16);
            fire = Integer.parseInt(tmpArr[110] + tmpArr[111], 16);

            String sensorData = "\n\nGateway: " + gateway +
                    "\nTemperature: " + temperature +
                    "\t  Humidity: " + humidity +
                    "\t  Bio: " + bio +
                    "\nP_btn: " + p_btn +
                    "\t  PIR: " + pir +
                    "\t  Door: " + door +
                    "\t  Fire: " + fire;

            tmpSB2.append(sensorData);
        } else {
            cmd = cmd.concat(tmpArr[6] + tmpArr[7]);
            /* Key Event */
            if (true == cmd.equals("601010")) {
                btn_119 = true;
                String sensorData = "\n\n119";
                tmpSB2.append(sensorData);
            } else if (true == cmd.equals("601050")) {
                btn_call = true;
                String sensorData = "\n\nCall";
                tmpSB2.append(sensorData);
            } else if (true == cmd.equals("611020")) {
                btn_cancel = true;
                String sensorData = "\n\nCancel";
                tmpSB2.append(sensorData);
            } else if (true == cmd.equals("601030")) {
                btn_carer = true;
                String sensorData = "\n\nCarer";
                tmpSB2.append(sensorData);
            }
            /* Zigbee Sensor Event */
            else if (true == cmd.equals("674402")) {
                btn_carer = true;
                String sensorData = "\n\nPIR";
                tmpSB2.append(sensorData);
            } else if (true == cmd.equals("674a01")) {
                btn_carer = true;
                String sensorData = "\n\nDoor";
                tmpSB2.append(sensorData);
            } else if (true == cmd.equals("601102")) {
                btn_carer = true;
                String sensorData = "\n\nRF_btn - Emergency";
                tmpSB2.append(sensorData);
            } else if (true == cmd.equals("611102")) {
                btn_carer = true;
                String sensorData = "\n\nRF_btn - Cancel";
                tmpSB2.append(sensorData);
            } else if (true == cmd.equals("604702")) {
                btn_carer = true;
                String sensorData = "\n\nFire";
                tmpSB2.append(sensorData);
            }
        }

        readText.setText(tmpSB2);

        readSB.delete(0, readSB.length());
        tmpSB.delete(0, tmpSB.length());
        tmpSB2.delete(0, tmpSB.length());
    }
}
