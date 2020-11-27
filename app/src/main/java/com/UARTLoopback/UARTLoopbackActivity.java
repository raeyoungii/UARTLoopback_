package com.UARTLoopback;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import io.sentry.Sentry;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class UARTLoopbackActivity extends Activity {

    LinearLayout myBackground;

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
    String gateway;
    String current_time;
    int temperature;
    int humidity;
    int bio;
    int p_btn;
    int pir;
    int door;
    int fire;

    boolean btn_119 = false;
    boolean btn_call = false;
    boolean btn_carer = false;
    boolean btn_cancel = false;

    boolean inHouse = false;
    String emergencyResult = "Cancel";
    boolean urgentFlag = false;

    inHouse_thread inHouseThread;
    emergencyDecision_thread emergencyDecisionThread;

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
        myBackground = (LinearLayout) findViewById(R.id.Background);
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

        inHouseThread = new inHouse_thread();
        emergencyDecisionThread = new emergencyDecision_thread();

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

    private class inHouse_thread extends Thread {
        public void run() {
            try {
                Thread.sleep(120000);
                inHouse = false;
            } catch (InterruptedException e) {
                inHouse = true;
            }
        }
    }

    private class emergencyDecision_thread extends Thread {
        public void run() {
            try {
                if (true == urgentFlag) {
                    Thread.sleep(10000);
                } else {
                    Thread.sleep(120000);
                }
                emergencyResult = "Confirm";
            } catch (InterruptedException e) {
                emergencyResult = "Cancel";
                myBackground.setBackgroundColor(Color.parseColor("#dddddd"));
            }
            urgentFlag = false;
            emergencyDecision();
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

        String[] tmpArr = new String[ch.length];

        for (int i = 0; i < ch.length; i++) {
            temp = String.format("%02x", (int) ch[i]);

            if (temp.length() == 4) {
                tmpArr[i] = temp.substring(2, 4);
            } else {
                tmpArr[i] = temp;
            }
        }

        /* current time */
        StringBuilder tmpSB = new StringBuilder();
        long now = System.currentTimeMillis();
        Date date = new Date(now);
        SimpleDateFormat mFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        String formatDate = mFormat.format(date);

        try {
            /* Periodic Report */
            if (tmpArr.length == 124 && true == tmpArr[0].equals("33") && true == tmpArr[tmpArr.length - 1].equals("55")) {
                /* check s_len, s_cmd */
                String s_len = tmpArr[1] + tmpArr[2];
                String s_cmd = tmpArr[5];
                if (true == s_len.equals("007a") && true == s_cmd.equals("30")) {
                    gateway = "";
                    for (int i = 14; i < 22; i++) {
                        gateway = gateway.concat(tmpArr[i]);
                    }
                    current_time = formatDate;

                    for (int i = 0; i < 7; i++) {
                        String s_id = tmpArr[24 + i * 14];
                        int s_data = Integer.parseInt(tmpArr[26 + i * 14] + tmpArr[27 + i * 14], 16);
                        switch (s_id) {
                            case "40":
                                temperature = s_data;
                                break;
                            case "41":
                                humidity = s_data;
                                break;
                            case "42":
                                bio = s_data;
                                break;
                            case "44":
                                pir = s_data;
                                break;
                            case "4a":
                                door = s_data;
                                break;
                            case "11":
                                p_btn = s_data;
                                break;
                            case "47":
                                fire = s_data;
                                break;
                        }
                    }

                    String sensorData = "Current_time: " + current_time +
                            "\nGateway: " + gateway +
                            "\nTemperature: " + temperature +
                            "\t  Humidity: " + humidity +
                            "\t  Bio: " + bio +
                            "\n  PIR: " + pir +
                            "\t  Door: " + door +
                            "\t  P_btn: " + p_btn +
                            "\t  Fire: " + fire;

                    tmpSB.append(sensorData);
                    emergencyPredictServer();
                }
            } else if (tmpArr.length <= 12 && true == tmpArr[0].equals("33") && true == tmpArr[tmpArr.length - 1].equals("55")) {
                /* check s_len, s_cmd */
                String s_len = tmpArr[1] + tmpArr[2];
                String s_cmd = tmpArr[5] + tmpArr[6] + tmpArr[7];
                /* Key Event */
                if (true == s_len.equals("0008") && true == s_cmd.equals("601010")) {
                    // 119 통화 연결
                    btn_119 = true;
                    emergencyPredictAndroid();
                    urgentFlag = true;
                    emergencyDecisionThread.start();
                    String sensorData = "\n\n119";
                    tmpSB.append(sensorData);
                } else if (true == s_len.equals("0008") && true == s_cmd.equals("601050")) {
                    // 보호자 통화 연결
                    btn_call = true;
                    String sensorData = "\n\nCall";
                    tmpSB.append(sensorData);
                } else if (true == s_len.equals("0008") && true == s_cmd.equals("611020")) {
                    // 취소 버튼
                    emergencyDecisionThread.interrupt();
                    String sensorData = "\n\nCancel";
                    tmpSB.append(sensorData);
                } else if (true == s_len.equals("0008") && true == s_cmd.equals("601030")) {
                    // 생활복지사 통화 연결
                    btn_carer = true;
                    String sensorData = "\n\nCarer";
                    tmpSB.append(sensorData);
                }
                /* Zigbee Sensor Event */
                else if (true == s_len.equals("000a") && true == s_cmd.equals("674402")) {
                    // PIR
                    inHouseThread.start();
                    String sensorData = "\n\nPIR";
                    tmpSB.append(sensorData);
                } else if (true == s_len.equals("000a") && true == s_cmd.equals("674a01")) {
                    // Door
                    inHouseThread.interrupt();
                    String sensorData = "\n\nDoor";
                    tmpSB.append(sensorData);
                } else if (true == s_len.equals("0008") && true == s_cmd.equals("601102")) {
                    // RF_btn - Emergency
                    emergencyPredictAndroid();
                    urgentFlag = true;
                    emergencyDecisionThread.start();
                    String sensorData = "\n\nRF_btn - Emergency";
                    tmpSB.append(sensorData);
                } else if (true == s_len.equals("0008") && true == s_cmd.equals("611102")) {
                    // RF_btn - Cancel

                    emergencyDecisionThread.interrupt();
                    String sensorData = "\n\nRF_btn - Cancel";
                    tmpSB.append(sensorData);
                } else if (true == s_len.equals("0008") && true == s_cmd.equals("604702")) {
                    // Fire
                    emergencyPredictAndroid();
                    urgentFlag = true;
                    emergencyDecisionThread.start();
                    String sensorData = "\n\nFire";
                    tmpSB.append(sensorData);
                }
            }
            readText.setText(tmpSB);
        } catch (Exception e) {
            Sentry.captureException(e);
        }

        readSB.delete(0, readSB.length());
        tmpSB.delete(0, tmpSB.length());
    }

    private void emergencyPredictServer() {
        long now = System.currentTimeMillis();
        Date date = new Date(now);
        SimpleDateFormat mFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

        String etTime = mFormat.format(date);
        String etMac = gateway;
        String etTemp = Integer.toString(temperature);
        String etHum = Integer.toString(humidity);
        String etBio = Integer.toString(bio);
        String etPir = Integer.toString(pir);
        String etDoor = Integer.toString(door);
        String etFire = Integer.toString(fire);
        String etP_btn = Integer.toString(p_btn);

        Call<ResponseBody> call = MyClient.getInstance().getMyApi().predictServer(etTime, etMac, etTemp, etHum, etBio, etPir, etDoor, etFire, etP_btn);
        call.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                try {
                    JSONObject jsonObject = new JSONObject(response.body().string());
                    if (jsonObject.getString("result").equals("Emergency")) {
                        // 배경 빨간색으로 변경, 결정 쓰레드 2분
                        myBackground.setBackgroundColor(Color.RED);
                        emergencyDecisionThread.start();
                    }
                } catch (JSONException e) {
                    Sentry.captureException(e);
                } catch (IOException e) {
                    Sentry.captureException(e);
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Sentry.captureException(t);
            }
        });
    }

    private void emergencyPredictAndroid() {
        long now = System.currentTimeMillis();
        Date date = new Date(now);
        SimpleDateFormat mFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

        String etTime = mFormat.format(date);
        String etMac = gateway;
        String etUrgent = "Warning";

        Call<ResponseBody> call = MyClient.getInstance().getMyApi().predictAndroid(etTime, etMac, etUrgent);
        call.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Sentry.captureException(t);
            }
        });
    }

    private void emergencyDecision() {
        long now = System.currentTimeMillis();
        Date date = new Date(now);
        SimpleDateFormat mFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

        String etTime = mFormat.format(date);
        String etMac = gateway;
        String etDecision = emergencyResult;

        Call<ResponseBody> call = MyClient.getInstance().getMyApi().decision(etTime, etMac, etDecision);
        call.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Sentry.captureException(t);
            }
        });
    }
}
