package com.example.obdemo;

import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.storage.UpCompletionHandler;
import com.qiniu.android.storage.UploadManager;
import com.qiniu.util.Auth;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static java.util.UUID.randomUUID;

public class MainActivity extends AppCompatActivity {

    private static final int RESULT_LOAD_IMAGE = 1;
    private ImageView imageView;
    private Executor executor = Executors.newSingleThreadExecutor();
    private ObjectDetector detector;
    private MqttAsyncClient mqttAsyncClient;
    private static final String QINIU_KEY="七牛ACCESS_KEY";
    private static final String QINIU_SECRET="七牛ACCESS_SECRET";
    private static final String QINIU_BUCKET="bucket名称";
    private static final String QINIU_DOMAIN="bucket对应的域名";
    private UploadManager uploadManager = new UploadManager();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button select = (Button) findViewById(R.id.select);
        imageView = (ImageView) findViewById(R.id.image);
        select.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent i = new Intent(
                        Intent.ACTION_PICK,
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);

                startActivityForResult(i, RESULT_LOAD_IMAGE);
            }
        });

        detector = new ObjectDetector("labels.txt", "model.pb", getAssets());
        try {
            detector.load();
        } catch (IOException e) {
            Toast.makeText(getApplicationContext(), "加载失败", Toast.LENGTH_SHORT).show();
        }

        try {
            startConnection();
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "连接失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void startConnection() throws MqttException {
        if (mqttAsyncClient == null) {
            String clientId = "client_" + Settings.Secure.getString(getApplicationContext().getContentResolver(), Settings.Secure.ANDROID_ID);
            mqttAsyncClient = new MqttAsyncClient("tcp://iot.eclipse.org:1883", clientId,
                    new MqttDefaultFilePersistence(getApplicationContext().getApplicationInfo().dataDir));
            mqttAsyncClient.connect(null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "已连接到Broker", Toast.LENGTH_LONG).show();
                        }
                    });
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, final Throwable exception) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(), "连接Broker失败:" + exception.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        }
    }

    private void publishResult(final List<DetectionResult> results, Bitmap image){
        final long timestamp = System.currentTimeMillis() / 1000;
        Auth auth = Auth.create(QINIU_KEY, QINIU_SECRET);
        String upToken = auth.uploadToken(QINIU_BUCKET);
        uploadManager.put(getBytesFromBitmap(image), null, upToken, new UpCompletionHandler() {
            @Override
            public void complete(String key, ResponseInfo info, JSONObject response) {
                 if(info.isOK()){
                     List<String> objects = new ArrayList<>();
                     for(DetectionResult detectionResult: results){
                         objects.add(detectionResult.getLabel());
                     }
                     try {
                         JSONObject jsonMesssage = new JSONObject();
                         jsonMesssage.put("id", randomUUID());
                         jsonMesssage.put("timestamp", timestamp);
                         jsonMesssage.put("objects", objects);
                         jsonMesssage.put("image_url", "http://" + QINIU_DOMAIN + "\\" + response.getString("key"));
                         mqttAsyncClient.publish("front_door/detection/objects", new MqttMessage(jsonMesssage.toString().getBytes()));
                     } catch (JSONException e) {
                         e.printStackTrace();
                     } catch (MqttPersistenceException e) {
                         e.printStackTrace();
                     } catch (MqttException e) {
                         e.printStackTrace();
                     }
                 }
            }
        }, null);

    }

    public byte[] getBytesFromBitmap(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
        return stream.toByteArray();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RESULT_LOAD_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri selectedImage = data.getData();
            String[] filePathColumn = {MediaStore.Images.Media.DATA};

            Cursor cursor = getContentResolver().query(selectedImage,
                    filePathColumn, null, null, null);
            cursor.moveToFirst();

            int columnIndex = cursor.getColumnIndex(filePathColumn[0]);
            String picturePath = cursor.getString(columnIndex);
            cursor.close();

            final Bitmap originImage = BitmapFactory.decodeFile(picturePath);
            imageView.setImageBitmap(originImage);
            final ProgressDialog progress = new ProgressDialog(this);
            progress.setTitle("请等待");
            progress.setMessage("正在识别");
            progress.setCancelable(false); // disable dismiss by tapping outside of the dialog
            progress.show();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap bitmapInput = Bitmap.createBitmap(detector.getInputSize(), detector.getInputSize(), Bitmap.Config.ARGB_8888);
                    final Matrix originToInput = Utils.getImageTransformationMatrix(
                            originImage.getWidth(), originImage.getHeight(), detector.getInputSize(), detector.getInputSize(),
                            0, false);
                    final Canvas canvas = new Canvas(bitmapInput);
                    canvas.drawBitmap(originImage, originToInput, null);

                    final List<DetectionResult> results = detector.detect(bitmapInput, 0.6f);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progress.hide();
                            final Bitmap copiedImage = originImage.copy(Bitmap.Config.ARGB_8888, true);
                            final Canvas resultCanvas = new Canvas(copiedImage);
                            final Paint paint = new Paint();
                            paint.setColor(Color.RED);
                            paint.setStyle(Paint.Style.STROKE);
                            paint.setStrokeWidth(5.0f);

                            Paint textPaint = new Paint();
                            textPaint.setColor(Color.WHITE);
                            textPaint.setStyle(Paint.Style.FILL);
                            textPaint.setTextSize((float) (0.04 * copiedImage.getWidth()));
                            Matrix inputToOrigin = new Matrix();
                            originToInput.invert(inputToOrigin);
                            for (DetectionResult result : results) {
                                RectF box = result.getBox();
                                inputToOrigin.mapRect(box);
                                resultCanvas.drawRect(box, paint);
                                resultCanvas.drawText(result.getLabel(), box.left, box.top, textPaint);
                            }
                            imageView.setImageBitmap(copiedImage);
                            publishResult(results, copiedImage);
                        }
                    });
                }
            });
        }
    }
}
