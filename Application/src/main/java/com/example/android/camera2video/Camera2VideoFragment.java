
package com.example.android.camera2video;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;

import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.support.v4.app.ActivityCompat;
import android.text.format.Time;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


public class Camera2VideoFragment extends Fragment
        implements View.OnClickListener, FragmentCompat.OnRequestPermissionsResultCallback {

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private static final String TAG = "Camera2VideoFragment";
    private static final int REQUEST_VIDEO_PERMISSIONS = 1;
    private static final String FRAGMENT_DIALOG = "dialog";

    private static final String[] VIDEO_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
    };

    private static final int SETTINGS_RESULT = 1;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }
	public static float focusDistance;
	public static long exposureDuration =1000000000l / 30;
	public static int iso = 4000;
	
    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * Button to record video
     */
    private ImageView btnCapture;

    /**
     * Button to  change
     */
    ImageView btnCamera;

    /**
     * A refernce to the opened {@link android.hardware.camera2.CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * A reference to the current {@link android.hardware.camera2.CameraCaptureSession} for
     * preview.
     */
    private CameraCaptureSession mPreviewSession;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture,
                                              int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                                                int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }

    };
    /**
     * Camera Management
     */
    private CameraManager mCameraManager;
    /**
     * Id currently use camera
     */
    private String mCameraId;
    /**
     * The {@link android.util.Size} of camera preview.
     */
    private static Size mPreviewSize;

    /**
     * The {@link android.util.Size} of video recording.
     */
    private static Size mVideoSize;

    static boolean isBackCamera = true;

    /**
     * Camera preview.
     */
    public CaptureRequest.Builder mPreviewBuilder;

    /**
     * MediaRecorder
     */
    private MediaRecorder mMediaRecorder;

    /**
     * Whether the app is recording video now
     */
    private boolean mIsRecordingVideo;

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;


    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
	

    DiscreteSeekBar discreteSeekBar;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its status.
     */
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            startPreview();
            mCameraOpenCloseLock.release();
            if (null != mTextureView) {
                configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
            }
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };

    public static Camera2VideoFragment newInstance() {
        return new Camera2VideoFragment();
    }

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    private static Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 16 / 9 && size.getHeight() <= 1080) {
                return size;
            }
        }
        Log.e(TAG, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
//    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
//        // Collect the supported resolutions that are at least as big as the preview Surface
//        List<Size> bigEnough = new ArrayList<Size>();
//        int w = aspectRatio.getWidth();
//        int h = aspectRatio.getHeight();
//        for (Size option : choices) {
//            if (option.getHeight() == option.getWidth() * h / w &&
//                    option.getWidth() <= width && option.getHeight() <= height) {
//                bigEnough.add(option);
//            }
//        }
//
//        // Pick the smallest of those, assuming we found any
//        if (bigEnough.size() > 0) {
//            return Collections.max(bigEnough, new CompareSizesByArea());
//        } else {
////            Log.e(TAG, "Couldn't find any suitable preview size");
//            return choices[0];
//        }
//    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<Size>();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * height / width &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size"+choices[0].getWidth()+"--"+choices[0].getHeight());
            return choices[0];
        }
    }
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2_video, container, false);
    }
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Bundle bundle = getArguments();
        if (bundle != null) {
            mCameraId = bundle.getString("cameraid");
        } else {
            mCameraId = PreferenceHelper.getCurrentCameraid(getActivity());
            isBackCamera = true;
        }

    }
    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);

        btnCapture = (ImageView)view.findViewById(R.id.btn_capture);
        btnCapture.setOnClickListener(this);
        ImageView btnSetting = (ImageView) view.findViewById(R.id.btn_setting);
        btnSetting.setOnClickListener(this);

        btnCamera = (ImageView) view.findViewById(R.id.btn_camera);
        btnCamera.setOnClickListener(this);

        discreteSeekBar = (DiscreteSeekBar) view.findViewById(R.id.discrete1);
        discreteSeekBar.setNumericTransformer(new DiscreteSeekBar.NumericTransformer() {
            @Override
            public int transform(int progress) {
                int temp = 30;
                if (0 <= progress && progress < 5) {
                    temp = 2;
                }
                if (5 <= progress && progress < 10) {
                    temp = 4;
                }
                if (110 <= progress && progress < 15) {
                    temp = 6;
                }
                if (15 <= progress && progress < 20) {
                    temp = 8;
                }
                if (20 <= progress && progress < 25) {
                    temp = 15;
                }
                if (25 <= progress && progress < 30) {
                    temp = 30;
                }
                if (30 <= progress && progress < 35) {
                    temp = 60;
                }
                if (35 <= progress && progress < 40) {
                    temp = 100;
                }
                if (40 <= progress && progress < 45) {
                    temp = 125;
                }
                if (45 <= progress && progress < 50) {
                    temp = 250;
                }
                if (50 <= progress && progress < 55) {
                    temp = 500;
                }
                if (55 <= progress && progress < 60) {
                    temp = 750;
                }
                if (60 <= progress && progress < 65) {
                    temp = 1000;
                }
                if (65 <= progress && progress < 70) {
                    temp = 1500;
                }
                if (70 <= progress && progress < 75) {
                    temp = 2000;
                }
                if (75 <= progress && progress < 80) {
                    temp = 3000;
                }
                if (80 <= progress && progress < 85) {
                    temp = 4000;
                }
                if (85 <= progress && progress < 90) {
                    temp = 6000;
                }
                if (90 <= progress && progress <= 100) {
                    temp = 8000;
                }
                return temp;
            }
        });
        MySeekBarListener2 listener = new MySeekBarListener2();
        discreteSeekBar.setOnProgressChangeListener(listener);

    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.btn_capture: {
                if (mIsRecordingVideo)
                {
                    stopRecordingVideo();
                } else
                {
                    startRecordingVideo();
                }
                break;
            }
            case R.id.btn_setting: {
                Activity activity = getActivity();
                if (null != activity)
                {
                    Intent intent = new Intent(activity,SettingActivity.class);
                    startActivityForResult(intent, SETTINGS_RESULT);
                }
                break;
            }case R.id.btn_camera:
            {
                if (!isBackCamera)
                {
                    mCameraId = "0";
                    isBackCamera = true;
                    startBackgroundThread();
                    if (mTextureView.isAvailable())
                    {
                        reOpenCamera(mTextureView.getWidth(), mTextureView.getHeight());
                    }
                    else
                    {
                        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
                    }
                }
                else
                {
                    mCameraId = "1";
                    isBackCamera = false;
                    startBackgroundThread();
                    if (mTextureView.isAvailable())
                    {
                        reOpenCamera(mTextureView.getWidth(), mTextureView.getHeight());
                    }
                    else
                    {
                        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
                    }
                }
                break;
            }
        }
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode==SETTINGS_RESULT)
        {
            displayUserSettings();
        }

    }

    static  int indexBackCameraResolution =0;
    static  int indexFontCameraResolution =0;
    private void displayUserSettings()
    {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        String prefBack = sharedPrefs.getString("listBack", "NULL");
        String prefFont = sharedPrefs.getString("listFont", "NULL");
        if(mCameraId.contains("0"))
        {
            switch (prefBack)
            {
                case "3840x2160":
                    indexBackCameraResolution = 0;
                    break;
                case "3288x2480":
                    indexBackCameraResolution = 1;
                    break;
                case "3200x2400":
                    indexBackCameraResolution = 2;
                    break;
                case "2976x2976":
                    indexBackCameraResolution = 3;
                    break;
                case "2592x1944":
                    indexBackCameraResolution = 4;
                    break;
                case "2688x1512":
                    indexBackCameraResolution = 5;
                    break;
                case "2048x1536":
                    indexBackCameraResolution = 6;
                    break;
                case "1920x1080":
                    indexBackCameraResolution = 7;
                    break;
                case "1600x1200":
                    indexBackCameraResolution = 8;
                    break;
                case "1440x1080":
                    indexBackCameraResolution = 9;
                    break;
                case "1280x960":
                    indexBackCameraResolution = 10;
                    break;
                case "1280x768":
                    indexBackCameraResolution = 11;
                    break;
                case "1280x720":
                    indexBackCameraResolution = 12;
                    break;
                case "1024x768":
                    indexBackCameraResolution = 13;
                    break;
                case "800x600":
                    indexBackCameraResolution = 14;
                    break;
                case "864x480":
                    indexBackCameraResolution = 15;
                    break;
                default:
                    indexBackCameraResolution = 7;
                    break;
            }
        }
        else
        {
            switch (prefFont)
            {
                case "2592x1944":
                    indexFontCameraResolution = 0;
                    break;
                case "2048x1536":
                    indexFontCameraResolution = 1;
                    break;
                case "1920x1080":
                    indexFontCameraResolution = 2;
                    break;
                case "1600x1200":
                    indexFontCameraResolution = 3;
                    break;
                case "1440x1080":
                    indexFontCameraResolution = 4;
                    break;
                case "1280x960":
                    indexFontCameraResolution = 5;
                    break;
                case "1280x768":
                    indexFontCameraResolution = 6;
                    break;
                case "1280x720":
                    indexFontCameraResolution = 7;
                    break;
                case "1024x768":
                    indexFontCameraResolution = 8;
                    break;
                case "800x600":
                    indexFontCameraResolution = 9;
                    break;
                case "864x480":
                    indexFontCameraResolution = 10;
                    break;
                default:
                    indexFontCameraResolution = 7;
                    break;
            }
        }
    }


    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private boolean shouldShowRequestPermissionRationale(String[] permissions) {
        for (String permission : permissions) {
            if (FragmentCompat.shouldShowRequestPermissionRationale(this, permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Requests permissions needed for recording video.
     */
    private void requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            FragmentCompat.requestPermissions(this, VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult");
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.length == VIDEO_PERMISSIONS.length) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                                .show(getChildFragmentManager(), FRAGMENT_DIALOG);
                        break;
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private boolean hasPermissionsGranted(String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(getActivity(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }


    /**
     * Tries to open a {@link CameraDevice}. The result is listened by `mStateCallback`.
     */
    private void openCamera(int width, int height) {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            requestVideoPermissions();
            return;
        }
        final Activity activity = getActivity();
        if (null == activity || activity.isFinishing()) {
            return;
        }
        mCameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

        try {

            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            // Choose the sizes for camera preview and video recording
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraId);

            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
//            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            Size[] sizes = map.getOutputSizes(MediaRecorder.class);
            if(mCameraId.contains("0"))
            {
                mVideoSize = sizes[indexBackCameraResolution+2];
            }
            else
            {
                mVideoSize = sizes[0+indexFontCameraResolution];
            }
//            Log.d(TAG, "reOpenCamera-- mVideoSize"+mVideoSize.getWidth()+"--"+mVideoSize.getHeight());
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    width, height);
            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE)
            {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            }
            else
            {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }
            configureTransform(width, height);
            mMediaRecorder = new MediaRecorder();
            mCameraManager.openCamera(mCameraId, mStateCallback, null);
        } catch (CameraAccessException e) {
            Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
            activity.finish();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }
    private void reOpenCamera(int viewWidth, int viewHeight) {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            mCameraOpenCloseLock.release();
        }

        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map.getOutputSizes(MediaRecorder.class);

            if(mCameraId.contains("0"))
            {
                mVideoSize = sizes[indexBackCameraResolution+2];
            }
            else
            {
                mVideoSize = sizes[indexFontCameraResolution+0];
            }
//            Log.d(TAG, "reOpenCamera-- mVideoSize"+mVideoSize.getWidth()+"--"+mVideoSize.getHeight());
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    viewWidth, viewHeight);
            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE)
            {

                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            }
            else
            {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }
            configureTransform(viewWidth, viewHeight);
            mMediaRecorder = new MediaRecorder();
            mCameraManager.openCamera(mCameraId, mStateCallback, null);

        } catch (CameraAccessException e) {

            e.printStackTrace();
        }
    }
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mMediaRecorder) {
                mMediaRecorder.release();
                mMediaRecorder = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    protected void setupRequest( CaptureRequest.Builder request)
    {
//        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
//        switch (focus)
//        {
//            case AutoFocus:
//               request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
//            case ManualFocus:
//                request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
//                request.set(CaptureRequest.LENS_FOCUS_DISTANCE, SettingCamera.focusDistance);
//        }

//        switch(exposure)
//        {
//            case AutoExposure:
//                request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
//            case ManualExposure:
                request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureDuration);
//                request.set(CaptureRequest.SENSOR_SENSITIVITY, SettingCamera.iso);
//        }
//        request.set(CaptureRequest.SENSOR_FRAME_DURATION, minFrameDuration);
    }
    /**
     * Start the camera preview.
     */
    private void startPreview() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }
        try {
            setUpMediaRecorder();
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
			// Init value for Video
            setupRequest(mPreviewBuilder);
						
            List<Surface> surfaces = new ArrayList<Surface>();

            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilder.addTarget(recorderSurface);

            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                    mPreviewSession = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                    Activity activity = getActivity();
                    if (null != activity) {
                        Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show();
                    }
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Update the camera preview. {@link #startPreview()} needs to be called in advance.
     */
    public void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }
        try {
            mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private void setUpMediaRecorder() throws IOException {
        final Activity activity = getActivity();
        if (null == activity) {
            return;
        }
        realFilePath = tmpFilePath = getVideoFile(activity).getAbsolutePath();
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setAudioChannels(2);
        mMediaRecorder.setAudioEncodingBitRate(384000);
        mMediaRecorder.setAudioSamplingRate(44100);
        mMediaRecorder.setOutputFile(getVideoFile(activity).getAbsolutePath());
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(30);
//        Log.d(TAG, "----setVideoSize " + mVideoSize.getWidth() + "----" + mVideoSize.getHeight());
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int orientation = ORIENTATIONS.get(rotation);
        mMediaRecorder.setOrientationHint(orientation);
        mMediaRecorder.prepare();
    }

    String tmpFilePath = createPathIfNotExist(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/Camera/") + "Original.mp4";
    public String createPathIfNotExist(String path)
    {
        File file = new File(path);
        if (!file.exists())
        {
            file.mkdirs();
        }
        return path;
    }
    private File getVideoFile(Context context) {
        return new File(context.getExternalFilesDir(null), "video.mp4");
    }
    public static String realFilePath;
	public void  renameFile(String src, String dst)
	{
        new File(src).renameTo(new File(dst)) ;
	}
    private void startRecordingVideo() {
        try {
            // UI
            btnCapture.setImageResource(R.drawable.btn_cam_pressed);
            mIsRecordingVideo = true;
            btnCamera.setEnabled(false);
            // Start recording
            mMediaRecorder.start();


        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    private void stopRecordingVideo() {
        // UI
        mIsRecordingVideo = false;
        btnCapture.setImageResource(R.drawable.btn_cam);
        btnCamera.setEnabled(true);
        // Stop recording
        mMediaRecorder.stop();
        mMediaRecorder.reset();
        Time time = new Time();
        time.setToNow();

        Activity activity = getActivity();
        realFilePath = getVideoFile(activity).getAbsolutePath().replace("video.mp4",time.format("VID_%Y%m%d_%H%M%S.mp4"));
        renameFile(tmpFilePath, realFilePath);
        if (null != activity) {
            Toast.makeText(activity, "Video saved: " + getVideoFile(activity),
                    Toast.LENGTH_SHORT).show();
        }
        startPreview();
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }

    public static class ConfirmationDialog extends DialogFragment {

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.permission_request)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            FragmentCompat.requestPermissions(parent, VIDEO_PERMISSIONS,
                                    REQUEST_VIDEO_PERMISSIONS);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    parent.getActivity().finish();
                                }
                            })
                    .create();
        }

    }

    private class MySeekBarListener2 implements DiscreteSeekBar.OnProgressChangeListener {
        //(2, 4, 6, 8, 15, 30, 60, 100, 125, 250, 500, 750, 1000, 1500, 2000, 3000, 4000, 5000, 6000, 8000)
        double temp = 500;
        @Override
        public void onProgressChanged(DiscreteSeekBar seekBar, int progress, boolean b)
        {
//            Log.d(TAG, "----------------------------------------"+progress);
            if (0 <= progress && progress < 5)
            {
                temp = 2;
            }
            if (5 <= progress && progress < 10)
            {
                temp = 4;
            }
            if (110 <= progress && progress < 15)
            {
                temp = 6;
            }
            if (15 <= progress && progress < 20)
            {
                temp = 8;
            }
            if (20 <= progress && progress < 25)
            {
                temp = 15;
            }
            if (25 <= progress && progress < 30)
            {
                temp = 30;
            }
			if (30 <= progress && progress < 35)
            {
                temp = 60;
            }
			if (35 <= progress && progress < 40)
            {
                temp = 100;
            }
			if (40 <= progress && progress < 45)
            {
                temp = 125;
            }
			if (45 <= progress && progress < 50)
            {
                temp = 250;
            }
			if (50 <= progress && progress < 55)
            {
                temp = 500;
            }if (55 <= progress && progress < 60)
            {
                temp = 750;
            }if (60 <= progress && progress < 65)
            {
                temp = 1000;
            }if (65 <= progress && progress < 70)
            {
                temp = 1500;
            }
			if (70 <= progress && progress < 75)
            {
                temp = 2000;
            }
            if (75 <= progress && progress < 80)
            {
                temp = 3000;
            }
            if (80 <= progress && progress < 85)
            {
                temp = 4000;
            }
            if (85 <= progress && progress < 90)
            {
                temp = 6000;
            }
            if (90 <= progress && progress <= 100)
            {
                temp = 8000;
            }

            if (mPreviewBuilder == null || getView() == null) {
                return;
            }
            long ae = (long)(1000000000l/temp);
            mPreviewBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ae);
            exposureDuration = ae;
            updatePreview();
        }

        @Override
        public void onStartTrackingTouch(DiscreteSeekBar seekBar) {
//            mSeekBarTextView.setVisibility(View.VISIBLE);
//            mSeekBarTextView.startAnimation(mAlphaInAnimation);
        }

        @Override
        public void onStopTrackingTouch(DiscreteSeekBar seekBar) {
//            mSeekBarTextView.startAnimation(mAlphaOutAnimation);
//           mSeekBarTextView.setVisibility(View.INVISIBLE);
        }


    } 
}
