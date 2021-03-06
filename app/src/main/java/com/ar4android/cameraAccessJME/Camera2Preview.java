/* CameraPreview - CameraAccessJME Example
 * 
 * Example Chapter 2
 * accompanying the book
 * "Augmented Reality for Android Application Development", Packt Publishing, 2013.
 * 
 * Copyright  2013 Jens Grubert, Raphael Grasset / Packt Publishing.
 * 
 */
package com.ar4android.cameraAccessJME;

import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

public class Camera2Preview extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "CameraPreview";
	private SurfaceHolder mHolder;

    private CameraWrapper mCamera2Util;
    private Activity mActivity;
    private Size mPreviewSize;
    CameraWrapper.PreviewCallback mCameraCallback;
    CameraWrapper.PreviewSizeCallback mPreviewSizeCallback;

    public Camera2Preview(Context context, CameraWrapper.PreviewCallback cameraCallback, CameraWrapper.PreviewSizeCallback previewSizeCallback) {
        super(context);
        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        mHolder = getHolder();
        mHolder.addCallback(this);
        mActivity = (Activity) context;
        mCameraCallback = cameraCallback;
        mPreviewSizeCallback = previewSizeCallback;
        Log.i(TAG, " ***** instantiated.");
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, " ***** surfaceCreated - mActivity:[" + mActivity + "] holder.getSurface():" + holder.getSurface() + "]");
        mCamera2Util = new Camera2WrapperImpl((CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE), mMessageHandler, mActivity.getWindowManager().getDefaultDisplay(), holder.getSurface());
        mCamera2Util.setPreviewCallback(mCameraCallback);
        mCamera2Util.setPreviewSizeCallback(mPreviewSizeCallback);
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, " ***** surfaceDestroyed");
        mCamera2Util.closeCamera();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        // If your preview can change or rotate, take care of those events here.
        // Make sure to stop the preview before resizing or reformatting it.

        Log.i(TAG, " ***** surfaceChanged");
        // If your preview can change or rotate, take care of those events here.
        // Make sure to stop the preview before resizing or reformatting it.
        if (mHolder.getSurface() == null){
            // preview surface does not exist
            return;
        }
        mPreviewSize = mCamera2Util.openCamera(w, h);

    }

    private Handler mMessageHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Toast.makeText(mActivity, (String) msg.obj, Toast.LENGTH_SHORT).show();
        }
    };


}