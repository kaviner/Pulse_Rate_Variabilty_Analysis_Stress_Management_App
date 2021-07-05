package com.example.stressmanagementapp.Function.measure;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.JsonRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.example.stressmanagementapp.Chart.HRLineChart.HRLineChart;
import com.example.stressmanagementapp.Dialog.LoadingDialog;
import com.example.stressmanagementapp.Chart.AbstractCustomLineChart;
import com.example.stressmanagementapp.Chart.PPGLineChart.PPGLineChart;
import com.example.stressmanagementapp.Function.schedule.ScheduleActivity;
import com.example.stressmanagementapp.Function.schedule.ScheduleListAdapter;
import com.example.stressmanagementapp.Function.schedule.ViewScheduledMeasureActivity;
import com.example.stressmanagementapp.Model.MeasuredResult;
import com.example.stressmanagementapp.Model.PPG_Model;
import com.example.stressmanagementapp.Model.PPG_Model_Sample;
import com.example.stressmanagementapp.R;
import com.example.stressmanagementapp.Util.CustomThread;
import com.example.stressmanagementapp.Util.DateUtil;
import com.github.mikephil.charting.charts.LineChart;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.reactivestreams.Publisher;

import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Function;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import polar.com.sdk.api.PolarBleApi;
import polar.com.sdk.api.PolarBleApiCallback;
import polar.com.sdk.api.PolarBleApiDefaultImpl;
import polar.com.sdk.api.errors.PolarInvalidArgument;
import polar.com.sdk.api.model.PolarDeviceInfo;
import polar.com.sdk.api.model.PolarOhrPPGData;
import polar.com.sdk.api.model.PolarSensorSetting;

public class NewMeasuringActivity extends AppCompatActivity {
    private LineChart chart;
    private AbstractCustomLineChart ppgLineChart,hrLineChart;
    private Intent intent;
    private String userId, measureId;
    public final static String TAG = MeasuringActivity.class.getSimpleName();

    //Sensor API related
    private PolarBleApi api;
    private String DEVICE_ID = "719AF624"; // or bt address like F5:A7:B8:EF:7A:D1 // TODO replace with your device id
    private Disposable ppgDisposable;
    private Disposable autoConnectDisposable;

    //UI
    private Button startMeasureBtn, stopMeasureBtn;
    private final LoadingDialog loadingDialog = new LoadingDialog(NewMeasuringActivity.this);

    //Measuring data
    private boolean receiveFirstPPG, stopReceivePPGThread, stopUpdateLineChartThread;
    private int ppgIndex;
    private CustomThread PPG_DataThread;
    private ProgressBar progressBar;
    private JSONObject sensorData;
    private Socket mSocket;
    private static JSONArray tempPPGSignal;
    private static PPG_Model_Sample simpleInOneMinutes;
    private static ReentrantLock lock;
    private String apiPath;
    private String mobileID;
    private double remainingTimeInPercentageOfMinute;
    private float timeCountDown = 60;
    private TextView progress_tv,measuredSampleNo,lastHRValue,lastAvgPPI,lastMaxPPI,lastMinPPI,lastSampleTime,measureStartAt,measureEndDate;
    private int measuredSample=0;
    private ListView measuredResultList;
    private List<MeasuredResult> measuredResultStringList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_newmeasuring_activity);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);


        apiPath = getString(R.string.api_path);
        mobileID = Settings.Secure.getString(getApplicationContext().getContentResolver(), Settings.Secure.ANDROID_ID);

        handleIntent();
        setupSensorApi();
        initThreadState();
        initMeasureRecord();
        setupBtnListener();
        initProgressBar();
        initMeasureResult();
        initWebSocket();





    }

    private void initMeasureResult() {
        measuredSampleNo = findViewById(R.id.measuredSampleNo);
        lastHRValue = findViewById(R.id.lastHRValue);
        lastAvgPPI = findViewById(R.id.lastAvgPPI);
        lastMaxPPI = findViewById(R.id.lastMaxPPI);
        lastMinPPI = findViewById(R.id.lastMinPPI);
        lastSampleTime = findViewById(R.id.lastSampleTime);
        measureStartAt = findViewById(R.id.measureStartAt);
        measureEndDate = findViewById(R.id.measureEndDate);
        measuredResultList = findViewById(R.id.measuredResultList);
        measuredResultStringList = new ArrayList<MeasuredResult>();
    }

    private void initProgressBar() {
        progressBar = findViewById(R.id.circular_determinative_pb);
        progressBar.setProgress(0);
        progress_tv  = findViewById(R.id.progress_tv);
        progress_tv.setText("0%");
    }

    private void initWebSocket() {
        try {
            mSocket = IO.socket(this.getString(R.string.web_socket_endpoint));
            mSocket.connect();
            mSocket.on("getResult", onGetResult);
        } catch (URISyntaxException e) {
            e.printStackTrace();
            System.out.println("ERROR " + e.getMessage());
        }
    }

    private void handleIntent() {
        Intent intent = getIntent();
        if (intent != null) {
            String endDate = intent.getStringExtra("endDateTime");
            String activityName = intent.getStringExtra("activityName");
            TextView scheduleEndAt = findViewById(R.id.measureEndDate);
            if (activityName == null) {
                setTitle("Quick Measurement");
                scheduleEndAt.setText("N/A");
            } else if (activityName != null) {
                setTitle("Measuring " + activityName);
                scheduleEndAt.setText(intent.getStringExtra("endDateTime"));
            }
        }
    }

    private Emitter.Listener onGetResult = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            NewMeasuringActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    JSONObject data = (JSONObject) args[0];

                    try {
                        data.put("measureID",measureId);
                        data.put("timestamp",DateUtil.getCurrentDateWithUTC());
                        Gson gson = new GsonBuilder().create();
                        MeasuredResult result = gson.fromJson(data.toString(),MeasuredResult.class);


                        Log.d("InsertPPGSignalToWebSocket", "result = "+result.toString());
                        pushNewMeasuredResult(data);

                        lastHRValue.setText(String.valueOf((int)result.getAvg_overall_bpm()));
                        lastAvgPPI.setText(String.valueOf((int)result.getAvg_overall_ppi()));
                        lastMaxPPI.setText(String.valueOf((int)result.getMax_ppi()));
                        lastMinPPI.setText(String.valueOf((int)result.getMin_ppi()));
                        final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Hong_Kong"), Locale.US);
                        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT+08:00"));
                        lastSampleTime.setText(simpleDateFormat.format(calendar.getTime()));
                        measuredResultStringList.add(result);
                        MeasureResultListAdapter customCountryList = new MeasureResultListAdapter(NewMeasuringActivity.this, measuredResultStringList);
                        measuredResultList.setAdapter(customCountryList);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

        }
    };

    private void initMeasureRecord() {
        tempPPGSignal = new JSONArray();
        lock = new ReentrantLock();

        this.userId = "6058ba30ba59f62decefbe3d";
        this.measureId = String.format("%s_%s", userId, DateUtil.getDateStringInMeasuredRecord());
        Log.i(TAG, "Measure Id = " + this.measureId);

        String endpoint = "addMeasuredRecord";
        // Instantiate the RequestQueue.
        RequestQueue queue = Volley.newRequestQueue(this);
        String url = apiPath + "/" + endpoint;
        Log.d("initMeasureRecord", "Connecting url = " + url);
        //Log.d("pushNewPPGSignalRecordTo_MeasuredRecord", "RequestBody = " + jsonRequestBody.toString());
        List<String> list = new ArrayList<String>();
        // Request a string response from the provided URL.
        JSONObject jsonRequestBody = new JSONObject();
        try {
            jsonRequestBody.put("userID", userId);
            jsonRequestBody.put("measureID", measureId);
            jsonRequestBody.put("deviceID", mobileID);
            jsonRequestBody.put("sensorID", DEVICE_ID);
            jsonRequestBody.put("activityID", "60599a397194bc0568afdeca");
            jsonRequestBody.put("activityName", "Sleep");
            jsonRequestBody.put("category", "Rest");
        } catch (Exception e) {
            e.printStackTrace();
        }
        /*
            "userID": "605995e57194bc0568afdec1",
            "measureID": "6058bb78ba59f62decefbe3f_20210316T082708",
            "deviceID": "1234",
            "sensorID": "5678",
            "activityID": "60599a397194bc0568afdeca",
            "activityName": "sleep",
            "category": "rest",
            "startDateTime": "2021-05-09T08:27:08.000"
        */

        JsonObjectRequest jsonRequest = new JsonObjectRequest(Request.Method.POST, url, jsonRequestBody,
                new Response.Listener<JSONObject>() {

                    @Override
                    public void onResponse(JSONObject response) {
                        Log.d("initMeasureRecord", "onResponse: " + response.toString());
                    }


                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e("initMeasureRecord", "Response body = " + error.toString());
            }
        });

        // Add the request to the RequestQueue.
        queue.add(jsonRequest);


    }

    private void initThreadState() {
        stopReceivePPGThread = false;
        stopUpdateLineChartThread = false;
    }


    private void setupBtnListener() {
        startMeasureBtn = findViewById(R.id.startMeasureBtn);
        stopMeasureBtn = findViewById(R.id.stopMeasureBtn);
        this.startMeasureBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    loadingDialog.startLoadingDialog();
                    stopReceivePPGThread = false;
                    stopUpdateLineChartThread = false;
                    autoConnectSensorAndStartMeasuring();
                } catch (Exception ex) {
                    loadingDialog.dismissDialog();
                    ex.printStackTrace();
                }
            }
        });
        this.stopMeasureBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    try {
                        loadingDialog.startLoadingDialog();
                        api.disconnectFromDevice(DEVICE_ID);
                        PPG_DataThread.stopThread();
                        stopReceivePPGThread = true;
                        stopUpdateLineChartThread = true;
                        initThreadState();
                        loadingDialog.dismissDialog();
                        //ppgLineChart.chart.clear();
                        hrLineChart.chart.clear();
                    } catch (PolarInvalidArgument polarInvalidArgument) {
                        loadingDialog.dismissDialog();
                        polarInvalidArgument.printStackTrace();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
    }

    private void setupSensorApi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
        api = PolarBleApiDefaultImpl.defaultImplementation(this, PolarBleApi.ALL_FEATURES);
        api.setPolarFilter(false);
        api.setApiLogger(s -> Log.d(TAG, s));
        Log.d(TAG, "version: " + PolarBleApiDefaultImpl.versionInfo());
        initApiCallBack();
    }

    private void initApiCallBack() {
        api.setApiCallback(new PolarBleApiCallback() {
            @Override
            public void blePowerStateChanged(boolean powered) {
                Log.d(TAG, "BLE power: " + powered);
            }

            @Override
            public void deviceConnected(@NonNull PolarDeviceInfo polarDeviceInfo) {
                Log.d(TAG, "[deviceConnected] CONNECTED: " + polarDeviceInfo.deviceId);
                DEVICE_ID = polarDeviceInfo.deviceId;
            }

            @Override
            public void deviceConnecting(@NonNull PolarDeviceInfo polarDeviceInfo) {
                Log.d(TAG, "[deviceConnecting] CONNECTING: " + polarDeviceInfo.deviceId);
                DEVICE_ID = polarDeviceInfo.deviceId;
            }

            @Override
            public void deviceDisconnected(@NonNull PolarDeviceInfo polarDeviceInfo) {
                Log.d(TAG, "DISCONNECTED: " + polarDeviceInfo.deviceId);
                ppgDisposable = null;
                loadingDialog.dismissDialog();
            }

            @Override
            public void ecgFeatureReady(@NonNull String identifier) {
                Log.d(TAG, "ECG READY: " + identifier);
                // ecg streaming can be started now if needed
            }

            @Override
            public void accelerometerFeatureReady(@NonNull String identifier) {
                Log.d(TAG, "ACC READY: " + identifier);
                // acc streaming can be started now if needed
            }

            @Override
            public void ppgFeatureReady(@NonNull String identifier) {
                Log.d(TAG, "PPG READY: " + identifier);
                // ohr ppg can be started
                //Step 2 subscribe the PPG signal channel once the sensor api ppg feature ready
                subscribePPG();
            }

            @Override
            public void ppiFeatureReady(@NonNull String identifier) {
                Log.d(TAG, "PPI READY: " + identifier);
                // ohr ppi can be started
            }


            @Override
            public void hrFeatureReady(@NonNull String identifier) {
                Log.d(TAG, "HR READY: " + identifier);
                // hr notifications are about to start
            }

            @Override
            public void disInformationReceived(@NonNull String identifier, @NonNull UUID uuid, @NonNull String value) {
                Log.d(TAG, "uuid: " + uuid + " value: " + value);

            }

            @Override
            public void batteryLevelReceived(@NonNull String identifier, int level) {
                Log.d(TAG, "BATTERY LEVEL: " + level);

            }

            @Override
            public void polarFtpFeatureReady(@NonNull String s) {
                Log.d(TAG, "FTP ready");
            }
        });
    }
    //Step 1 auto connect to the sensor
    private void autoConnectSensorAndStartMeasuring() {

        if (autoConnectDisposable != null) {
            autoConnectDisposable.dispose();
            autoConnectDisposable = null;
        }
        autoConnectDisposable = api.autoConnectToDevice(-50, "180D", null).subscribe(
                () -> {
                    Log.d(TAG, "auto connect search complete");
                    autoConnectDisposable.dispose();
                    autoConnectDisposable = null;
                },
                throwable -> Log.e(TAG, "" + throwable.toString())
        );

    }

    private void subscribePPG() {
        if (ppgDisposable == null) {
            ppgIndex = 0;
            receiveFirstPPG = false;
            loadingDialog.dismissDialog();
            feedMultiple();
        } else {
            ppgDisposable.dispose();
            ppgDisposable = null;
        }
    }

    //Step 3 Create PPG_DataThread for receive PPG signal from sensor and send the sample to python WebSocket Server for analysis
    private void feedMultiple() {
        if (PPG_DataThread != null)
            PPG_DataThread.stopThread();

        PPG_DataThread = initPPGDataThread(); //Thread to update received data to PPG model object
        PPG_DataThread.startThread();
    }

    private PPG_Model ppgModel;

    private void pushNewMeasuredResult(JSONObject jsonRequestBody) {
        String endpoint = "pushNewMeasuredResult_To_MeasuredRecord";
        // Instantiate the RequestQueue.
        RequestQueue queue = Volley.newRequestQueue(this);
        String url = apiPath + "/" + endpoint;
        Log.d("pushNewPPGSignalRecordTo_MeasuredRecord", "Connecting url = " + url);
        //Log.d("pushNewPPGSignalRecordTo_MeasuredRecord", "RequestBody = " + jsonRequestBody.toString());
        List<String> list = new ArrayList<String>();
        // Request a string response from the provided URL.


        JsonObjectRequest jsonRequest = new JsonObjectRequest(Request.Method.POST, url, jsonRequestBody,
                new Response.Listener<JSONObject>() {

                    @Override
                    public void onResponse(JSONObject response) {
                        Log.d("pushNewPPGSignalRecordTo_MeasuredRecord", "onResponse: " + response.toString());
                    }


                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e("getAllActivityCategory", "Response body = " + error.toString());
            }
        });

        // Add the request to the RequestQueue.
        queue.add(jsonRequest);
    }

    class InsertPPGSignalToMongoDB extends TimerTask {
        public void run() {
            lock.lock();
            try {
                Log.d("InsertPPGSignalToMongoDB", " JSONArray size = " + tempPPGSignal.length() + "\n" + tempPPGSignal.toString());
                String json = new Gson().toJson(tempPPGSignal);
                JSONObject jsonRequestBody = new JSONObject();
                jsonRequestBody.put("ppgSignalSet", tempPPGSignal);
                if (tempPPGSignal.length() != 0) {
                    //pushNewPPGRecordInMinute(jsonRequestBody);
                    tempPPGSignal = new JSONArray();
                }
            } catch (Exception e) {
                e.printStackTrace();
                Log.e("InsertPPGSignalToMongoDB", "run:  " + e.getMessage());
            } finally {
                lock.unlock();
            }
            Log.d("InsertPPGSignalToMongoDB", "Insert JSONArray to mongodb");
        }
    }

    class InsertPPGSignalToWebSocket extends TimerTask {
        public void run() {
            lock.lock();
            if(loadingDialog.isDisplaying())
                loadingDialog.dismissDialog();
            try {
                Log.d("InsertPPGSignalToWebSocket", " JSONArray size = " + tempPPGSignal.length() + "\n" + tempPPGSignal.toString());

                String json = new Gson().toJson(tempPPGSignal);
                JSONObject jsonRequestBody = new JSONObject();
//                jsonRequestBody.put("ppgSignalSet", tempPPGSignal);
                //pushNewPPGRecordInMinute(jsonRequestBody);
                try {
                    if (tempPPGSignal.length() > 5000) {
                        Log.d("InsertPPGSignalToWebSocket", "Emit ");
                        mSocket.emit("PPG_Signal", tempPPGSignal.toString());
                    }

                } catch (Exception ex) {
                    ex.printStackTrace();
                    System.out.println("ERROR during emitting message");
                }
                tempPPGSignal = new JSONArray();
                NewMeasuringActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CountDownTimer countTime =new CountDownTimer(60000, 1000) {

                            public void onTick(long millisUntilFinished) {
                                float second = millisUntilFinished / 1000 ;
                                timeCountDown = 60 - second;
                                float percentage = timeCountDown / 60 * 100;
//                        Log.d("timer", "onTick: second"+second);
//                        Log.d("timer", "onTick: "+timeCountDown);
//                        Log.d("timer", "onTick: "+percentage);
                                progressBar.setProgress((int)percentage);
                                progress_tv.setText(String.valueOf((int)percentage)+"%");
                            }

                            public void onFinish() {
                                measuredSample++;
                                measuredSampleNo.setText(String.valueOf(measuredSample));
                            }

                        };
                        countTime.start();
                    }
                });


            } catch (
                    Exception e) {
                e.printStackTrace();
                Log.e("InsertPPGSignalToWebSocket", "run:  " + e.getMessage());
            } finally {
                lock.unlock();
            }
            Log.d("InsertPPGSignalToWebSocket", "Insert JSONArray to websocket");
        }

    }

    public void addPPGSignalToThisMinute(JSONObject ppgSignal) {
        lock.lock();
        try {
            tempPPGSignal.put(ppgSignal);
        } finally {
            lock.unlock();
        }
    }

    private CustomThread initPPGDataThread() {
        Runnable getPPGDataRunnable;
        Thread getPPGData_OnUIThread;

        // And From your main() method or any other method
        Timer timer = new Timer();
        simpleInOneMinutes = new PPG_Model_Sample();
        timer.schedule(new InsertPPGSignalToWebSocket(), 0, 60000);
        //timer.schedule(new InsertPPGSignalToWebSocket(), 5000, 5000);
        getPPGDataRunnable = (new Runnable() {
            @Override
            public void run() {
                if (stopReceivePPGThread == false && ppgDisposable == null) {
                    ppgDisposable = api.requestPpgSettings(DEVICE_ID).toFlowable().flatMap((Function<PolarSensorSetting, Publisher<PolarOhrPPGData>>) polarPPGSettings -> api.startOhrPPGStreaming(DEVICE_ID, polarPPGSettings.maxSettings())).subscribe(
                            polarOhrPPGData -> {
                                for (PolarOhrPPGData.PolarOhrPPGSample sample : polarOhrPPGData.samples) {
                                    SimpleDateFormat dft = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                                    Date currentTime = new Date(System.currentTimeMillis());
                                    ppgModel = new PPG_Model(sample, dft.format(currentTime), userId, measureId);
                                    addPPGSignalToThisMinute(ppgModel.toJsonObject());
                                    //addPPGSignalToThisMinute(ppgModel.toJsonObject());
                                    //Log.d(TAG,"Detect ppgModel: "+ppgModel.toJsonObject().toString());
                                }
                                if (receiveFirstPPG == true && loadingDialog.getLoadingDialog().isShowing() == true)
                                    loadingDialog.dismissDialog();
                            },

                            throwable -> Log.e(TAG, "getPPGDataRunnable() " + throwable.getMessage()),
                            () -> Log.d(TAG, "complete")
                    );
//                    if (dataSampleID%5==0 && (ppgList!=null&&ppgList.size()>0)){
//                        ++slidingWindowIndex;
//                        sample.setPPGModelList(ppgList);
//                        SlidingWindow w = new SlidingWindow(sample);
//                        list.addNewSlidingWindow(w);
//                        sample = new PPG_DataSample(5);
//                    }
                }
            }
        });
        getPPGData_OnUIThread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 1000; i++) {

                    //Assign the getPPGDataRunnable to UI Thread
                    runOnUiThread(getPPGDataRunnable);

                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        return new CustomThread(getPPGDataRunnable, getPPGData_OnUIThread);
    }



    public String getSensorID() {
        return this.DEVICE_ID;
    }
}