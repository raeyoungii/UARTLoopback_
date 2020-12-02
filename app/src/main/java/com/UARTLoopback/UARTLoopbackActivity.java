package com.UARTLoopback;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

    byte status;

    int baudRate; /* baud rate */
    byte stopBit; /* 1:1stop bits, 2:2 stop bits */
    byte dataBit; /* 8:8bit, 7: 7bit */
    byte parity; /* 0: none, 1: odd, 2: even, 3: mark, 4: space */
    byte flowControl; /* 0:none, 1: flow control(CTS,RTS) */
    public Context global_context;
    public boolean bConfiged = false;
    public SharedPreferences sharePrefSettings;
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

    /* Handler */
    InHouseHandler inHouseHandler;
    boolean inHouse = false;

    DecisionHandler decisionHandler;
    boolean decisionTimer = false;

    private static final int MESSAGE_IN_HOUSE_START = 100;
    private static final int MESSAGE_IN_HOUSE_REPEAT = 101;
    private static final int MESSAGE_IN_HOUSE_STOP = 102;

    private static final int MESSAGE_DECISION_START = 100;
    private static final int MESSAGE_DECISION_REPEAT = 101;
    private static final int MESSAGE_DECISION_STOP = 102;

    ArrayList<Integer> recentTemp = new ArrayList<>(6);
    ArrayList<Integer> recentFire = new ArrayList<>(6);
    ArrayList<Integer> recentBio = new ArrayList<>(6);

    TemperatureThread temperatureThread;
    FireThread fireThread;
    BioThread bioThread;

    private TextView mTimeView;

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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        mTimeView = findViewById(R.id.time_view);
        mTimeView.setTextSize(18);
        mTimeView.setTextColor(Color.LTGRAY);

        mHandler.sendEmptyMessage(0);
        uartInterface = new FT311UARTInterface(this, sharePrefSettings);

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        temperatureThread = new TemperatureThread();
        fireThread = new FireThread();
        bioThread = new BioThread();

        temperatureThread.start();
        fireThread.start();
        bioThread.start();

        inHouseHandler = new InHouseHandler();
        decisionHandler = new DecisionHandler();

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

    private class InHouseHandler extends Handler {
        int cnt;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_IN_HOUSE_START:
                    // 타이머 초기화 기능
                    cnt = 0;
                    this.removeMessages(MESSAGE_IN_HOUSE_REPEAT);
                    this.sendEmptyMessage(MESSAGE_IN_HOUSE_REPEAT);
                    break;
                case MESSAGE_IN_HOUSE_REPEAT:
                    // 타이머 반복 기능
                    if (cnt < 120) {
                        this.sendEmptyMessageDelayed(MESSAGE_IN_HOUSE_REPEAT, 1000);
                    } else {
                        inHouse = false;
                        inHouseHandler.sendEmptyMessage(MESSAGE_IN_HOUSE_STOP);
                    }
                    break;
                case MESSAGE_IN_HOUSE_STOP:
                    // 타이머 종료 기능
                    this.removeMessages(MESSAGE_IN_HOUSE_REPEAT);
                    break;
            }
        }
    }

    private class DecisionHandler extends Handler {
        int cnt;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_DECISION_START:
                    // 타이머 초기화 기능
                    cnt = 0;
                    decisionTimer = true;
                    this.removeMessages(MESSAGE_DECISION_REPEAT);
                    this.sendEmptyMessage(MESSAGE_DECISION_REPEAT);
                    break;
                case MESSAGE_DECISION_REPEAT:
                    // 타이머 반복 기능
                    if (cnt < 120) {
                        this.sendEmptyMessageDelayed(MESSAGE_DECISION_REPEAT, 1000);
                    } else {
                        emergencyDecision();
                        inHouseHandler.sendEmptyMessage(MESSAGE_DECISION_STOP);
                    }
                    break;
                case MESSAGE_DECISION_STOP:
                    // 타이머 종료 기능
                    decisionTimer = false;
                    this.removeMessages(MESSAGE_DECISION_REPEAT);
                    break;
            }
        }
    }

    private void startDecision() {
        if (false == decisionTimer) {
            decisionHandler.sendEmptyMessage(MESSAGE_DECISION_START);
        }
    }

    private void stopDecision() {
        decisionHandler.sendEmptyMessage(MESSAGE_DECISION_STOP);
    }


    private class TemperatureThread extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                }
                if (recentTemp.size() == 6) {
                    int cnt = 0;
                    for (int i = 0; i < 6; i++) {
                        if (recentTemp.get(i) >= 35) {
                            cnt++;
                        }
                    }
                    if (cnt > 4) {
                        emergencyPredictAndroid("High Temperature");
                    }
                }
            }
        }
    }

    private class FireThread extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                }
                if (recentFire.size() == 6) {
                    int cnt = 0;
                    for (int i = 0; i < 6; i++) {
                        if (recentFire.get(i) != 0) {
                            cnt++;
                        }
                    }
                    if (cnt > 4) {
                        emergencyPredictAndroid("Fire for 1 min");
                    }
                }
            }
        }
    }

    private class BioThread extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                }
                if (recentBio.size() == 6) {
                    int cnt = 0;
                    for (int i = 0; i < 6; i++) {
                        if (recentBio.get(i) >= 100) {
                            cnt++;
                        }
                    }
                    if (cnt > 4) {
                        emergencyPredictAndroid("High Heart Rate");
                    }
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
        long now = System.currentTimeMillis();
        Date date = new Date(now);
        SimpleDateFormat mFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        String formatDate = mFormat.format(date);

        try {
            /* Periodic Report */
            if (tmpArr.length == 124 && tmpArr[0].equals("33") && tmpArr[tmpArr.length - 1].equals("55")) {
                /* check s_len, s_cmd */
                String s_len = tmpArr[1] + tmpArr[2];
                String s_cmd = tmpArr[5];
                if (s_len.equals("007a") && s_cmd.equals("30")) {
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
                                bio = Integer.parseInt(tmpArr[27 + i * 14], 16);
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

                    // TODO: emergency for temp, fire, bio
                    if (recentTemp.size() > 6) {
                        recentTemp.remove(0);
                    }
                    recentTemp.add(temperature);

                    if (recentFire.size() > 6) {
                        recentFire.remove(0);
                    }
                    recentFire.add(bio);

                    if (recentBio.size() > 6) {
                        recentBio.remove(0);
                    }
                    recentBio.add(bio);
                    emergencyPredictServer();
                }
            } else if (tmpArr.length >= 10 && tmpArr.length <= 12 && tmpArr[0].equals("33") && tmpArr[tmpArr.length - 1].equals("55")) {
                /* check s_len, s_cmd */
                String s_len = tmpArr[1] + tmpArr[2];
                String s_cmd = tmpArr[5] + tmpArr[6] + tmpArr[7];
                /* Key Event */
                if (s_len.equals("0008") && s_cmd.equals("601010")) {
                    // 119 통화 연결
                    Toast.makeText(global_context, "119", Toast.LENGTH_SHORT).show();
                    emergencyPredictAndroid("119");
                } else if (s_len.equals("0008") && s_cmd.equals("601050")) {
                    // 보호자 통화 연결
                    Toast.makeText(global_context, "Carer", Toast.LENGTH_SHORT).show();
                } else if (s_len.equals("0008") && s_cmd.equals("611020")) {
                    // 취소 버튼
                    stopDecision();
                    Toast.makeText(global_context, "Cancel", Toast.LENGTH_SHORT).show();
                } else if (s_len.equals("0008") && s_cmd.equals("601030")) {
                    // 생활복지사 통화 연결
                    Toast.makeText(global_context, "LWA", Toast.LENGTH_SHORT).show();
                }
                /* Zigbee Sensor Event */
                else if (s_len.equals("000a") && s_cmd.equals("674401")) {
                    // PIR
//                    inHouse = true;
//                    inHouseHandler.sendEmptyMessage(MESSAGE_IN_HOUSE_STOP);
                } else if (s_len.equals("000a") && s_cmd.equals("674a01")) {
                    // Door
                    inHouseHandler.sendEmptyMessage(MESSAGE_IN_HOUSE_START);
                    Toast.makeText(global_context, "Door", Toast.LENGTH_SHORT).show();
                } else if (s_len.equals("0008") && s_cmd.equals("601101")) {
                    // RF_btn - Emergency
                    emergencyPredictAndroid("RF_btn - Emergency");
                    Toast.makeText(global_context, "RF_btn - Emergency", Toast.LENGTH_SHORT).show();
                    startDecision();
                } else if (s_len.equals("0008") && s_cmd.equals("611101")) {
                    // RF_btn - Cancel
                    Toast.makeText(global_context, "RF_btn - Cancel", Toast.LENGTH_SHORT).show();
                    stopDecision();
                } else if (s_len.equals("0008") && s_cmd.equals("604701")) {
                    // Fire
                    Toast.makeText(global_context, "Fire", Toast.LENGTH_SHORT).show();
                    emergencyPredictAndroid("Fire");
                }
            }
        } catch (Exception e) {
            Sentry.captureException(e);
        }
        readSB.delete(0, readSB.length());
    }

    private void emergencyPredictServer() {
        String etTime = current_time;
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
                String data = "temp: " + temperature + " hum: " + humidity + " bio: " + bio;
                Toast.makeText(global_context, data, Toast.LENGTH_SHORT).show();
//                try {
//                    JSONObject jsonObject = new JSONObject(response.body().string());
//                    if (!jsonObject.getString("result").equals("Normal")) {
//                        // 배경 빨간색으로 변경, 결정 쓰레드 2분
//                        jsonObject.getString("result");
//                        startDecision();
//                    }
//                } catch (JSONException e) {
//                    Sentry.captureException(e);
//                } catch (IOException e) {
//                    Sentry.captureException(e);
//                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Sentry.captureException(t);
            }
        });
    }

    private void emergencyPredictAndroid(String information) {
        long now = System.currentTimeMillis();
        Date date = new Date(now);
        SimpleDateFormat mFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

        String etTime = mFormat.format(date);
        String etMac = gateway;
        String etDecision = "Emergency_Android: " + information;

        Call<ResponseBody> call = MyClient.getInstance().getMyApi().predictAndroid(etTime, etMac, etDecision);
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
        String etDecision = "Emergency_Server";

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

    Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {

            long now = System.currentTimeMillis();
            Date date = new Date(now);
            SimpleDateFormat mFormatTime = new SimpleDateFormat("yyyy-MM-dd\na h:mm:ss");
            String formatTime = mFormatTime.format(date);
            mTimeView.setText(String.format(formatTime));

            // 메세지를 처리하고 또다시 핸들러에 메세지 전달 (1000ms 지연)
            mHandler.sendEmptyMessageDelayed(0, 1000);
        }
    };
}

