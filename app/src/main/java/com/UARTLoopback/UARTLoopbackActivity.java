package com.UARTLoopback;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Trace;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
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

    RelativeLayout myBackground;

    StringBuffer readSB = new StringBuffer();

    /* thread to read the data */
    public handler_thread handlerThread;

    /* declare a FT312 UART interface variable */
    public FT311UARTInterface uartInterface;

    /* graphical objects */
    EditText readText;
    Button configBtn;
    TextView textBio;
    TextView textTemp;
    TextView textHum;
    TextView mTimeView;

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
    String gateway = "null";
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
    int inHouse = 0;

    E119Handler e119Handler;
    boolean e119Timer = false;

    EmergencyBtnHandler emergencyBtnHandler;
    boolean emergencyBtnTimer = false;

    CancelHandler cancelHandler;
    boolean cancelTimer = false;

    DecisionHandler decisionHandler;
    boolean decisionTimer = false;
    String decisionMessage = "null";

    private static final int MESSAGE_119_START = 100;
    private static final int MESSAGE_119_REPEAT = 101;
    private static final int MESSAGE_119_STOP = 102;

    private static final int MESSAGE_EMERGENCY_BTN_START = 100;
    private static final int MESSAGE_EMERGENCY_BTN_REPEAT = 101;
    private static final int MESSAGE_EMERGENCY_BTN_STOP = 102;

    private static final int MESSAGE_CANCEL_START = 100;
    private static final int MESSAGE_CANCEL_REPEAT = 101;
    private static final int MESSAGE_CANCEL_STOP = 102;

    private static final int MESSAGE_IN_HOUSE_START = 100;
    private static final int MESSAGE_IN_HOUSE_REPEAT = 101;
    private static final int MESSAGE_IN_HOUSE_STOP = 102;

    private static final int MESSAGE_DECISION_START = 100;
    private static final int MESSAGE_DECISION_REPEAT = 101;
    private static final int MESSAGE_DECISION_STOP = 102;

    ArrayList<Integer> recentTemp = new ArrayList<>(6);
    ArrayList<Integer> recentFire = new ArrayList<>(6);
    ArrayList<Integer> recentBio = new ArrayList<>(6);

    int[] fakeArr = {70, 70, 70, 70, 68, 75, 85, 101, 103, 105, 108};
    int fakeIdx = 0;
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
        configBtn = findViewById(R.id.configButton);
        textBio = findViewById(R.id.textBio);
        textTemp = findViewById(R.id.textTemp);
        textHum = findViewById(R.id.textHum);
        mTimeView = findViewById(R.id.time_view);
        myBackground = findViewById(R.id.background);

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

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        mHandler.sendEmptyMessage(0);

        inHouseHandler = new InHouseHandler();
        decisionHandler = new DecisionHandler();
        emergencyBtnHandler = new EmergencyBtnHandler();
        cancelHandler = new CancelHandler();

        handlerThread = new handler_thread(handler);
        handlerThread.start();

        configBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (false == bConfiged) {
                    bConfiged = true;
                    uartInterface.SetConfig(baudRate, dataBit, stopBit, parity, flowControl);
                    savePreference();
                }

                if (true == bConfiged) {
                    configBtn.setBackgroundColor(getResources().getColor(R.color.transparent));
                    configBtn.setEnabled(false);
                }
            }
        });
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
                    cnt = 0;
                    this.removeMessages(MESSAGE_IN_HOUSE_REPEAT);
                    this.sendEmptyMessage(MESSAGE_IN_HOUSE_REPEAT);
                    break;
                case MESSAGE_IN_HOUSE_REPEAT:
                    if (cnt < 600) {
                        cnt++;
                        this.sendEmptyMessageDelayed(MESSAGE_IN_HOUSE_REPEAT, 1000);
                    } else {
                        inHouse = 0;
                        this.sendEmptyMessage(MESSAGE_IN_HOUSE_STOP);
                    }
                    break;
                case MESSAGE_IN_HOUSE_STOP:
                    this.removeMessages(MESSAGE_IN_HOUSE_REPEAT);
                    break;
            }
        }
    }

    private class E119Handler extends Handler {
        int cnt;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_119_START:
                    cnt = 0;
                    e119Timer = true;
                    this.removeMessages(MESSAGE_119_REPEAT);
                    this.sendEmptyMessage(MESSAGE_119_REPEAT);
                    break;
                case MESSAGE_119_REPEAT:
                    if (cnt < 40) {
                        cnt++;
                        this.sendEmptyMessageDelayed(MESSAGE_119_REPEAT, 1000);
                    } else {
                        this.sendEmptyMessage(MESSAGE_119_STOP);
                    }
                    break;
                case MESSAGE_119_STOP:
                    e119Timer = false;
                    this.removeMessages(MESSAGE_119_REPEAT);
                    break;
            }
        }
    }


    private class EmergencyBtnHandler extends Handler {
        int cnt;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_EMERGENCY_BTN_START:
                    cnt = 0;
                    emergencyBtnTimer = true;
                    this.removeMessages(MESSAGE_EMERGENCY_BTN_REPEAT);
                    this.sendEmptyMessage(MESSAGE_EMERGENCY_BTN_REPEAT);
                    break;
                case MESSAGE_EMERGENCY_BTN_REPEAT:
                    if (cnt < 40) {
                        cnt++;
                        this.sendEmptyMessageDelayed(MESSAGE_EMERGENCY_BTN_REPEAT, 1000);
                    } else {
                        this.sendEmptyMessage(MESSAGE_EMERGENCY_BTN_STOP);
                    }
                    break;
                case MESSAGE_EMERGENCY_BTN_STOP:
                    emergencyBtnTimer = false;
                    this.removeMessages(MESSAGE_EMERGENCY_BTN_REPEAT);
                    break;
            }
        }
    }

    private class CancelHandler extends Handler {
        int cnt;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_CANCEL_START:
                    cnt = 0;
                    cancelTimer = true;
                    this.removeMessages(MESSAGE_CANCEL_REPEAT);
                    this.sendEmptyMessage(MESSAGE_CANCEL_REPEAT);
                    break;
                case MESSAGE_CANCEL_REPEAT:
                    if (cnt < 40) {
                        cnt++;
                        this.sendEmptyMessageDelayed(MESSAGE_CANCEL_REPEAT, 1000);
                    } else {
                        this.sendEmptyMessage(MESSAGE_CANCEL_STOP);
                    }
                    break;
                case MESSAGE_CANCEL_STOP:
                    cancelTimer = false;
                    this.removeMessages(MESSAGE_CANCEL_REPEAT);
                    break;
            }
        }
    }


    /* 취소 버튼 확인해서 결정 */
    private class DecisionHandler extends Handler {
        int cnt;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_DECISION_START:
                    cnt = 0;
                    decisionTimer = true;
                    this.removeMessages(MESSAGE_DECISION_REPEAT);
                    this.sendEmptyMessage(MESSAGE_DECISION_REPEAT);
                    break;
                case MESSAGE_DECISION_REPEAT:
                    if (cnt < 120) {
                        cnt++;
                        this.sendEmptyMessageDelayed(MESSAGE_DECISION_REPEAT, 1000);
                    } else {
                        emergencyDecision(decisionMessage);
                        this.sendEmptyMessage(MESSAGE_DECISION_STOP);
                    }
                    break;
                case MESSAGE_DECISION_STOP:
                    decisionTimer = false;
                    decisionMessage = "null";
                    this.removeMessages(MESSAGE_DECISION_REPEAT);
                    break;
            }
        }
    }


    private void startDecision(String description) {
        if (false == decisionTimer) {
            decisionMessage = description;
            decisionHandler.sendEmptyMessage(MESSAGE_DECISION_START);
        }
    }

    private void stopDecision() {
        decisionHandler.sendEmptyMessage(MESSAGE_DECISION_STOP);
    }


    public void recentArrayAdd(int temperatureData, int bioData, int fireData) {
        if (recentTemp.size() > 6) {
            recentTemp.remove(0);
        }
        recentTemp.add(temperatureData);

        if (recentBio.size() > 6) {
            recentBio.remove(0);
        }
        recentBio.add(bioData);

        if (recentFire.size() > 6) {
            recentFire.remove(0);
        }
        recentFire.add(fireData);
    }


    public void recentDataPredict() {
        int temp_cnt = 0;
        int bio_cnt = 0;
        int fire_cnt = 0;

        for (int i = 0; i < recentTemp.size(); i++) {
            if (recentTemp.get(i) >= 35 || (inHouse == 1 && recentTemp.get(i) <= 10)) {
                temp_cnt++;
            }
        }
        if (temp_cnt > 2) {
            emergencyPredictAndroid("Abnormal Temperature");
        }

        for (int i = 0; i < recentBio.size(); i++) {
            if (recentBio.get(i) != 0 && (recentBio.get(i) >= 100 || recentBio.get(i) <= 45)) {
                bio_cnt++;
            }
        }
        if (bio_cnt > 2) {
            emergencyPredictAndroid("Abnormal Heart Rate");
        }

        for (int i = 0; i < recentFire.size(); i++) {
            if (recentFire.get(i) != 0) {
                fire_cnt++;
            }
        }
        if (fire_cnt > 2) {
            emergencyPredictAndroid("Fire for 1 minute");
        }
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

                    for (int i = 0; i < 7; i++) {
                        String s_id = tmpArr[24 + i * 14];
                        int s_data = Integer.parseInt(tmpArr[26 + i * 14] + tmpArr[27 + i * 14], 16);
                        switch (s_id) {
                            case "40": temperature = s_data; break;
                            case "41": humidity = s_data; break;
                            case "42": bio = Integer.parseInt(tmpArr[27 + i * 14], 16); break;
                            case "44": pir = s_data; break;
                            case "4a": door = s_data; break;
                            case "11": p_btn = s_data; break;
                            case "47": fire = s_data; break;
                        }
                    }

                    textBio.setText(Integer.toString(bio));
                    textTemp.setText(Integer.toString(temperature));
                    textHum.setText(Integer.toString(humidity));

                    /* emergency for temp, bio, fire */
                    recentArrayAdd(temperature, bio, fire);
                    recentDataPredict();

                    /* InHouse Timer */
                    if (pir != 0) {
                        inHouse = 1;
                        inHouseHandler.sendEmptyMessage(MESSAGE_IN_HOUSE_STOP);
                    }

                    /* test bio */
                    if (fakeIdx == fakeArr.length) {
                        fakeIdx = 0;
                    }
                    bio = fakeArr[fakeIdx];
                    fakeIdx++;

                    emergencyPredictServer();
                }
            } else if (tmpArr.length >= 10 && tmpArr.length <= 12 && tmpArr[0].equals("33")
                    && tmpArr[tmpArr.length - 1].equals("55")) {
                /* check s_len, s_cmd */
                String s_len = tmpArr[1] + tmpArr[2];
                String s_cmd = tmpArr[5] + tmpArr[6] + tmpArr[7];
                String toastMessage = "null";
                /* Key Event */
                if (s_len.equals("0008") && s_cmd.equals("601010")) {
                    /* 119 통화 연결 */
                    if (false == e119Timer) {
                        toastMessage = "119";
                        e119Handler.sendEmptyMessage(MESSAGE_119_START);
                    }
                } else if (s_len.equals("0008") && s_cmd.equals("601050")) {
                    /* 보호자 통화 연결 */
                    toastMessage = "Carer";
                } else if (s_len.equals("0008") && s_cmd.equals("611020")) {
                    /* 취소 버튼 */
                    if (false == cancelTimer) {
                        toastMessage = "Cancel";
                        cancelHandler.sendEmptyMessage(MESSAGE_CANCEL_START);
                        myBackground.setBackgroundColor(Color.parseColor("#EFEFEF"));
                    }
                    stopDecision();
                } else if (s_len.equals("0008") && s_cmd.equals("601030")) {
                    /* 생활복지사 통화 연결 */
                    toastMessage = "LWA";
                }
                /* Zigbee Sensor Event */
                else if (s_len.equals("000a") && s_cmd.equals("674401")) {
                    /* PIR */
                    toastMessage = "PIR";
                } else if (s_len.equals("000a") && s_cmd.equals("674a01")) {
                    /* Door */
                    toastMessage = "Door";
                    inHouseHandler.sendEmptyMessage(MESSAGE_IN_HOUSE_START);
                } else if (s_len.equals("0008") && s_cmd.equals("601101")) {
                    /* RF_btn - Emergency */
                    if (false == emergencyBtnTimer) {
                        toastMessage = "RF_btn - Emergency";
                        emergencyBtnHandler.sendEmptyMessage(MESSAGE_EMERGENCY_BTN_START);
                        myBackground.setBackgroundColor(Color.RED);
                        emergencyPredictAndroid("RF_btn - Emergency");
                        startDecision("RF_btn - Emergency");
                    }
                } else if (s_len.equals("0008") && s_cmd.equals("611101")) {
                    /* RF_btn - Cancel */
                    if (false == cancelTimer) {
                        toastMessage = "RF_btn - Cancel";
                        cancelHandler.sendEmptyMessage(MESSAGE_CANCEL_START);
                        myBackground.setBackgroundColor(Color.parseColor("#EFEFEF"));
                        stopDecision();
                    }
                } else if (s_len.equals("0008") && s_cmd.equals("604701")) {
                    /* Fire */
                    toastMessage = "Fire";
                    emergencyPredictAndroid("Fire");
                }
                
                if (false == toastMessage.equals("null")) {
                    Toast.makeText(global_context, toastMessage, Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Sentry.captureException(e);
        }
        readSB.delete(0, readSB.length());
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
        String etInHouse = Integer.toString(inHouse);

        Call<ResponseBody> call = MyClient.getInstance().getMyApi().predictServer(etTime, etMac,
                etTemp, etHum, etBio, etPir, etDoor, etFire, etP_btn, etInHouse);
        call.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                try {
                    JSONObject jsonObject = new JSONObject(response.body().string());
                    if (!jsonObject.getString("result").equals("Normal")) {
                        // 배경 빨간색으로 변경, 결정 쓰레드 2분
                        myBackground.setBackgroundColor(Color.RED);
                        startDecision("Emergency_Server");
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

    private void emergencyPredictAndroid(String information) {
        long now = System.currentTimeMillis();
        Date date = new Date(now);
        SimpleDateFormat mFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

        String etTime = mFormat.format(date);
        String etDecision = "Emergency: " + information;
        String etMac = gateway;

        Call<ResponseBody> call = MyClient.getInstance().getMyApi().predictAndroid(etTime, etDecision, etMac);
        call.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                myBackground.setBackgroundColor(Color.RED);
                startDecision(information);
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Sentry.captureException(t);
            }
        });
    }

    private void emergencyDecision(String description) {
        long now = System.currentTimeMillis();
        Date date = new Date(now);
        SimpleDateFormat mFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

        String etTime = mFormat.format(date);
        String etDecision = "Emergency_Decision: " + description;
        String etMac = gateway;

        Call<ResponseBody> call = MyClient.getInstance().getMyApi().decision(etTime, etDecision, etMac);
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
            SimpleDateFormat mFormatTime = new SimpleDateFormat("yyyy-MM-dd\na h:mm");
            String formatTime = mFormatTime.format(date);
            mTimeView.setText(String.format(formatTime));
            mHandler.sendEmptyMessageDelayed(0, 1000);
        }
    };
}

