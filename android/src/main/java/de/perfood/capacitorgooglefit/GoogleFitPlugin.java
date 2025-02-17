package de.perfood.capacitorgooglefit;

import android.Manifest;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import com.getcapacitor.JSObject;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.FitnessOptions;
import com.google.android.gms.fitness.data.Bucket;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.result.DataReadResponse;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@CapacitorPlugin(name = "GoogleFit")
@NativePlugin(requestCodes = { GoogleFitPlugin.GOOGLE_FIT_PERMISSIONS_REQUEST_CODE, GoogleFitPlugin.RC_SIGN_IN })
public class GoogleFitPlugin extends Plugin {

    public static final String TAG = "HistoryApi";
    static final int GOOGLE_FIT_PERMISSIONS_REQUEST_CODE = 19849;
    static final int RC_SIGN_IN = 1337;

    private FitnessOptions getFitnessSignInOptions() {
        // FitnessOptions instance, declaring the Fit API data types
        // and access required
        return FitnessOptions
            .builder()
            .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_CALORIES_EXPENDED, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_CALORIES_EXPENDED, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_ACTIVITY_SEGMENT, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_HEIGHT, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_WEIGHT, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_ACTIVITY_SEGMENT, FitnessOptions.ACCESS_READ)
            .build();
    }

    private GoogleSignInAccount getAccount() {
        return GoogleSignIn.getLastSignedInAccount(getActivity());
    }

    private void requestPermissions() {
        GoogleSignIn.requestPermissions(getActivity(), GOOGLE_FIT_PERMISSIONS_REQUEST_CODE, getAccount(), getFitnessSignInOptions());
    }

    @PluginMethod
    public void connectToGoogleFit(PluginCall call) {
        GoogleSignInAccount account = getAccount();
        if (account == null) {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().build();
            GoogleSignInClient signInClient = GoogleSignIn.getClient(this.getActivity(), gso);
            Intent intent = signInClient.getSignInIntent();
            startActivityForResult(call, intent, RC_SIGN_IN);
        } else {
            this.requestPermissions();
        }
        call.resolve();
    }

    @PluginMethod
    public void isAllowed(PluginCall call) {
        final JSObject result = new JSObject();
        GoogleSignInAccount account = getAccount();
        if (account != null && GoogleSignIn.hasPermissions(account, getFitnessSignInOptions())) {
            result.put("allowed", true);
        } else {
            result.put("allowed", false);
        }
        call.resolve(result);
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        PluginCall savedCall = getSavedCall();

        if (requestCode == GOOGLE_FIT_PERMISSIONS_REQUEST_CODE) {
            savedCall.resolve();
        } else if (requestCode == RC_SIGN_IN) {
            if (!GoogleSignIn.hasPermissions(this.getAccount(), getFitnessSignInOptions())) {
                this.requestPermissions();
            } else {
                savedCall.resolve();
            }
        }
    }

    @PluginMethod
    public Task<DataReadResponse> getSteps(final PluginCall call) throws ParseException {
        final GoogleSignInAccount account = getAccount();

        if (account == null) {
            call.reject("No access");
            return null;
        }

        long startTime = dateToTimestamp(call.getString("startTime"));
        long endTime = dateToTimestamp(call.getString("endTime"));

        String timeUnitInput = call.getString("timeUnit", "HOURS");

        TimeUnit timeUnit = stringToTimeUnit(timeUnitInput);

        int bucketSize = call.getInt("bucketSize", 1);

        if (startTime == -1 || endTime == -1 || bucketSize == -1) {
            call.reject("Must provide a start time and end time");

            return null;
        }

        DataReadRequest readRequest = new DataReadRequest.Builder()
            .aggregate(DataType.TYPE_STEP_COUNT_DELTA)
            .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
            .bucketByTime(bucketSize, timeUnit)
            .enableServerQueries()
            .build();

        return Fitness
            .getHistoryClient(getActivity(), account)
            .readData(readRequest)
            .addOnSuccessListener(
                new OnSuccessListener<DataReadResponse>() {
                    @Override
                    public void onSuccess(DataReadResponse dataReadResponse) {
                        List<Bucket> buckets = dataReadResponse.getBuckets();

                        JSONArray steps = new JSONArray();

                        for (Bucket bucket : buckets) {
                            for (DataSet dataSet : bucket.getDataSets()) {
                                for (DataPoint dp : dataSet.getDataPoints()) {
                                    for (Field field : dp.getDataType().getFields()) {
                                        JSONObject stepEntry = new JSONObject();
                                        try {
                                            stepEntry.put("startTime", timestampToDate(dp.getStartTime(TimeUnit.MILLISECONDS)));
                                            stepEntry.put("endTime", timestampToDate(dp.getEndTime(TimeUnit.MILLISECONDS)));
                                            stepEntry.put("value", dp.getValue(field).asInt());
                                            steps.put(stepEntry);
                                        } catch (JSONException e) {
                                            call.reject(e.getMessage());
                                            return;
                                        }
                                    }
                                }
                            }
                        }

                        JSObject result = new JSObject();
                        result.put("steps", steps);

                        call.resolve(result);
                    }
                }
            );
    }

    @PluginMethod
    public Task<DataReadResponse> getWeight(final PluginCall call) throws ParseException {
        final GoogleSignInAccount account = getAccount();

        if (account == null) {
            call.reject("No access");
            return null;
        }

        long startTime = dateToTimestamp(call.getString("startTime"));
        long endTime = dateToTimestamp(call.getString("endTime"));

        if (startTime == -1 || endTime == -1) {
            call.reject("Must provide a start time and end time");

            return null;
        }
        DataReadRequest readRequest = new DataReadRequest.Builder()
            .read(DataType.TYPE_WEIGHT)
            .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
            .enableServerQueries()
            .build();

        return Fitness
            .getHistoryClient(getActivity(), account)
            .readData(readRequest)
            .addOnSuccessListener(
                new OnSuccessListener<DataReadResponse>() {
                    @Override
                    public void onSuccess(DataReadResponse dataReadResponse) {
                        DataSet weightDataSet = dataReadResponse.getDataSet(DataType.TYPE_WEIGHT);

                        JSONArray weights = new JSONArray();
                        for (DataPoint dp : weightDataSet.getDataPoints()) {
                            for (Field field : dp.getDataType().getFields()) {
                                JSONObject weightEntry = new JSONObject();
                                try {
                                    weightEntry.put("startTime", timestampToDate(dp.getStartTime(TimeUnit.MILLISECONDS)));
                                    weightEntry.put("endTime", timestampToDate(dp.getEndTime(TimeUnit.MILLISECONDS)));
                                    weightEntry.put("value", dp.getValue(field).asFloat());
                                    weights.put(weightEntry);
                                } catch (JSONException e) {
                                    call.reject(e.getMessage());
                                    return;
                                }
                            }
                        }
                        JSObject result = new JSObject();
                        result.put("weights", weights);
                        call.resolve(result);
                    }
                }
            );
    }

    @PluginMethod
    public Task<DataReadResponse> getActivities(final PluginCall call) throws ParseException {
        final GoogleSignInAccount account = getAccount();

        if (account == null) {
            call.reject("No access");
            return null;
        }

        long startTime = dateToTimestamp(call.getString("startTime"));
        long endTime = dateToTimestamp(call.getString("endTime"));
        String timeUnitInput = call.getString("timeUnit", "HOURS");

        TimeUnit timeUnit = stringToTimeUnit(timeUnitInput);

        int bucketSize = call.getInt("bucketSize", 1);

        if (startTime == -1 || endTime == -1 || bucketSize == -1) {
            call.reject("Must provide a start time and end time");

            return null;
        }
        DataReadRequest readRequest = new DataReadRequest.Builder()
            .aggregate(DataType.TYPE_CALORIES_EXPENDED)
            .aggregate(DataType.AGGREGATE_CALORIES_EXPENDED)
            .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
            .bucketByActivitySegment(bucketSize, timeUnit)
            .enableServerQueries()
            .build();

        return Fitness
            .getHistoryClient(getActivity(), account)
            .readData(readRequest)
            .addOnSuccessListener(
                new OnSuccessListener<DataReadResponse>() {
                    @Override
                    public void onSuccess(DataReadResponse dataReadResponse) {
                        List<Bucket> activityBuckets = dataReadResponse.getBuckets();

                        JSONArray activities = new JSONArray();

                        for (Bucket bucket : activityBuckets) {
                            JSONObject activity = new JSONObject();
                            try {
                                activity.put("startTime", timestampToDate(bucket.getStartTime(TimeUnit.MILLISECONDS)));
                                activity.put("endTime", timestampToDate(bucket.getEndTime(TimeUnit.MILLISECONDS)));
                                List<DataSet> activityDataSets = bucket.getDataSets();

                                for (DataSet ds : activityDataSets) {
                                    for (DataPoint dp : ds.getDataPoints()) {
                                        for (Field field : dp.getDataType().getFields()) {
                                            String dataTypeName = dp.getDataType().getName();
                                            switch (dataTypeName) {
                                                case "com.google.calories.expended":
                                                    activity.put("calories", dp.getValue(field).asFloat());
                                                    break;
                                            }
                                        }
                                    }
                                }
                                activity.put("name", bucket.getActivity());
                                activities.put(activity);
                            } catch (JSONException e) {
                                call.reject(e.getMessage());
                                return;
                            }
                        }
                        JSObject result = new JSObject();
                        result.put("activities", activities);

                        call.resolve(result);
                    }
                }
            );
    }

    private void dumpDataSet(DataSet dataSet) {
        Log.i(TAG, "Data returned for Data type: " + dataSet.getDataType().getName());
        for (DataPoint dp : dataSet.getDataPoints()) {
            Log.i(TAG, "Data point:");
            Log.i(TAG, "\tType: " + dp.getDataType().getName());
            Log.i(TAG, "\tStart: " + dp.getStartTime(TimeUnit.MILLISECONDS));
            Log.i(TAG, "\tEnd: " + dp.getEndTime(TimeUnit.MILLISECONDS));
            for (Field field : dp.getDataType().getFields()) {
                Log.i(TAG, "\tField: " + field.getName() + "   Value: " + dp.getValue(field));
            }
        }
    }

    private String timestampToDate(long timestamp) {
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        return df.format(cal.getTime());
    }

    private long dateToTimestamp(String date) {
        if (date.isEmpty()) {
            return -1;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        try {
            return f.parse(date).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    private TimeUnit stringToTimeUnit(String inputString) {
        switch (inputString) {
            case "NANOSECONDS":
                return TimeUnit.NANOSECONDS;
            case "MICROSECONDS":
                return TimeUnit.MICROSECONDS;
            case "MILLISECONDS":
                return TimeUnit.MILLISECONDS;
            case "SECONDS":
                return TimeUnit.SECONDS;
            case "MINUTES":
                return TimeUnit.MINUTES;
            case "HOURS":
                return TimeUnit.HOURS;
            case "DAYS":
                return TimeUnit.DAYS;
            default:
                return TimeUnit.HOURS;
        }
    }
}
