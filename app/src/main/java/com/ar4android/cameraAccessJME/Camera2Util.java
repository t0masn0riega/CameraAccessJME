/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ar4android.cameraAccessJME;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.renderscript.RenderScript;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;
import android.widget.Toast;

import com.jme3.app.Application;
import com.jme3.texture.image.ColorSpace;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2Util {

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2Util";

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;
    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;
    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */

    private CameraCaptureSession mCaptureSession;
    /**
     * A reference to the opened {@link CameraDevice}.
     */

    private CameraDevice mCameraDevice;
    /**
     * The {@link Size} of camera preview.
     */

    private Size mPreviewSize;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
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
        }

    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;
    private ImageReader mJmeImageReader;

    /**
     * This is the output file for our picture.
     */
    private File mFile;

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), mFile));
        }

    };

    private final ImageReader.OnImageAvailableListener mOnJmeImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageJmeProcessing(reader,mPreviewBufferRGB565));
        }

    };

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private CaptureRequest mPreviewRequest;

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private CameraManager mCameraManager;

    private Handler mMessageHandler;

    private Display mDisplay;

    private Surface mSurface;

    private byte[] mPreviewBufferRGB565 = null;
    private java.nio.ByteBuffer mPreviewByteBufferRGB565;
    private boolean pixelFormatConversionNeeded = true;
    private com.jme3.texture.Image mCameraJMEImageRGB565;
    private com.jme3.app.Application mJMEapp;

    private RenderScript mRS;
    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {
                    int afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_WAITING_NON_PRECAPTURE;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                                        CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            process(result);
        }

    };

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(String text) {
        // We show a Toast by sending request message to mMessageHandler. This makes sure that the
        // Toast is shown on the UI thread.
        Message message = Message.obtain();
        message.obj = text;
        if (mMessageHandler != null) {
            mMessageHandler.sendMessage(message);
        }
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
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<Size>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    public Camera2Util(CameraManager manager, Handler messageHandler, Display display, Surface surface, com.jme3.texture.Image jmeImage, Application jmeApp) {
        mCameraManager = manager;
        mMessageHandler = messageHandler;
        mDisplay = display;
        mSurface = surface;
//        mCameraJMEImageRGB565 = jmeImage;
        mJMEapp = jmeApp;
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private Size setUpCameraOutputs(int width, int height) {

        Size previewSize = null;
        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = mCameraManager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                if (characteristics.get(CameraCharacteristics.LENS_FACING)
                        == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        mOnImageAvailableListener, mBackgroundHandler);

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        width, height, largest);

                Log.i(TAG, "***** setUpCameraOutputs - largest.getWidth():[" + largest.getWidth() + "] largest.getHeight():[" + largest.getHeight() + "] previewSize.getWidth():[" + previewSize.getWidth() + "] previewSize.getHeight():[" + previewSize.getHeight() + "]");

                mJmeImageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(),
                ImageFormat.YUV_420_888, /*maxImages*/1);
                mJmeImageReader.setOnImageAvailableListener(
                        mOnJmeImageAvailableListener, mBackgroundHandler);

                preparePreviewCallbackBuffer(previewSize);

                mCameraId = cameraId;
                break;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        return previewSize;
    }

    /**
     * Opens the camera specified by {@link Camera2Util#mCameraId}.
     */
    public Size openCamera(int width, int height) {
        startBackgroundThread();

        Log.i(TAG, " ***** openCamera height:[" + height + "] width:[" + width + "]");
        mPreviewSize = setUpCameraOutputs(width, height);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            mCameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }

        return mPreviewSize;
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    public void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.stopRepeating();

                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
            if (null != mJmeImageReader) {
                mJmeImageReader.close();
                mJmeImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } catch (CameraAccessException e) {
            throw new RuntimeException("Interrupted while trying to stopRepeating.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }

        stopBackgroundThread();
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
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            assert mSurface != null;

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mSurface);
            mPreviewRequestBuilder.addTarget(mJmeImageReader.getSurface());

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(mSurface, mImageReader.getSurface(), mJmeImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // Flash is automatically enabled when necessary.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Initiate a still image capture.
     * @param picFile
     */
    public void takePicture(File picFile) {
        mFile = picFile;
        lockFocus();
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when we
     * get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {@link #lockFocus()}.
     */
    private void captureStillPicture() {
        try {
            if (null == mCameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            // Orientation
            int displayRotation = mDisplay.getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(displayRotation));

            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                               TotalCaptureResult result) {
                    showToast("Saved: " + mFile);
                    unlockFocus();
                }
            };

            mCaptureSession.stopRepeating();
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is finished.
     */
    private void unlockFocus() {
        try {
            // Reset the autofucos trigger
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaver implements Runnable {

        /**
         * The JPEG image
         */
        private final Image mImage;
        /**
         * The file we save the image into.
         */
        private final File mFile;

        public ImageSaver(Image image, File file) {
            mImage = image;
            mFile = file;
        }

        @Override
        public void run() {
            Log.i(TAG, "***** ImageSaver - run() - mImage:" + mImage + "] mFile:" + mFile + "]");

            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }

    private class ImageJmeProcessing implements Runnable {

        /**
         * The JPEG image
         */
        Image mImage;
        /**
         * The file we save the image into.
         */
        byte[] mPreviewBufferRGB565;

        ImageReader mReader;

        public ImageJmeProcessing(ImageReader reader, byte[] previewBufferRGB565) {
            mPreviewBufferRGB565 = previewBufferRGB565;
            mReader = reader;
        }

        @Override
        public void run() {
            Log.i(TAG, "***** ImageJmeProcessing - run() - mPreviewBufferRGB565:" + mPreviewBufferRGB565 + "]");

            try {
                mImage = mReader.acquireNextImage();
                Log.i(TAG, "***** ImageJmeProcessing - run() - mImage.getWidth():" + mImage.getWidth() + "]  mImage.getHeight():" + mImage.getHeight() + "] mImage.getPlanes().length:[" + mImage.getPlanes().length + "] mImage.getFormat():[" + mImage.getFormat() + "]");
                ByteBuffer yBuf = mImage.getPlanes()[0].getBuffer();
                Log.i(TAG, "***** ImageJmeProcessing - run() - mImage.getPlanes()[0].getRowStride():" + mImage.getPlanes()[0].getRowStride() + "]  mImage.getPlanes()[0].getPixelStride():" + mImage.getPlanes()[0].getPixelStride() + "]");
                Log.i(TAG, "***** ImageJmeProcessing - run() - vBuf.remaining() -1:" + yBuf.remaining() + "]");
                byte[] yBytes = new byte[yBuf.remaining()];
                yBuf.rewind();
                yBuf.get(yBytes);
                Log.i(TAG, "***** ImageJmeProcessing - run() -  yBuf.remaining() -2:" + yBuf.remaining() + "] yBytes.length -2:" + yBytes.length + "]");

                ByteBuffer uBuf = mImage.getPlanes()[1].getBuffer();
                Log.i(TAG, "***** ImageJmeProcessing - run() - mImage.getPlanes()[1].getRowStride():" + mImage.getPlanes()[1].getRowStride() + "]  mImage.getPlanes()[1].getPixelStride():" + mImage.getPlanes()[1].getPixelStride() + "]");
                byte[] uBytes = new byte[uBuf.remaining()];
                Log.i(TAG, "***** ImageJmeProcessing - run() - uBuf.remaining() -1:" + uBuf.remaining() + "]");
                uBuf.rewind();
                uBuf.get(uBytes);
                Log.i(TAG, "***** ImageJmeProcessing - run() -  uBuf.remaining() -2:" + uBuf.remaining() + "] uBytes.length -2:" + uBytes.length + "]");

                ByteBuffer vBuf = mImage.getPlanes()[2].getBuffer();
                Log.i(TAG, "***** ImageJmeProcessing - run() - mImage.getPlanes()[2].getRowStride():" + mImage.getPlanes()[2].getRowStride() + "]  mImage.getPlanes()[2].getPixelStride():" + mImage.getPlanes()[2].getPixelStride() + "]");
                byte[] vBytes = new byte[vBuf.remaining()];
                Log.i(TAG, "***** ImageJmeProcessing - run() - vBuf.remaining() -1:" + vBuf.remaining() + "]");
                vBuf.rewind();
                vBuf.get(vBytes);
                Log.i(TAG, "***** ImageJmeProcessing - run() -  vBuf.remaining() -2:" + vBuf.remaining() + "] vBytes.length -2:" + vBytes.length + "]");

                /**
                 * Convert YUV_420_888 image from  planar
                 * to semi-planar
                 */
                ByteArrayOutputStream yuv420semiPlanar = new ByteArrayOutputStream();
                try {
                    yuv420semiPlanar.write(yBytes);
                    Log.i(TAG, "***** ImageJmeProcessing - run() - vBytes.length:" + vBytes.length + "]");
                    for (int i=0;i<vBytes.length;i++) {
                        yuv420semiPlanar.write(uBytes[i]);
                        yuv420semiPlanar.write(vBytes[i]);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

                /**
                 * Convert YUV_420_888 semi-planar into RGB.
                 */
                yCbCrToRGB565(yuv420semiPlanar.toByteArray(), mImage.getWidth(), mImage.getHeight(),
                        mPreviewBufferRGB565);

                mImage.close();

                mPreviewByteBufferRGB565.clear();
                mPreviewByteBufferRGB565.put(mPreviewBufferRGB565);

                mCameraJMEImageRGB565.setData(mPreviewByteBufferRGB565);
                if ((com.ar4android.cameraAccessJME.CameraAccessJME) mJMEapp != null) {
                    Log.i(TAG, "***** ImageJmeProcessing -  mJMEapp != null");
                    ((com.ar4android.cameraAccessJME.CameraAccessJME) mJMEapp)
                            .setTexture(mCameraJMEImageRGB565);
                }

            } finally {
                if (mImage != null) {
                    mImage.close();
                }
            }
        }

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

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage("This device doesn't support Camera2 API.")
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }

    private static void yCbCrToRGB565(byte[] YCBCRs, int width, int height,
                                     byte[] rgbs) {

        Log.i(TAG, "***** yCbCrToRGB565 - YCBCRs.length:[" + YCBCRs.length + "] rgbs.length:[" + rgbs.length + "] width:[" + width + "] height:[" + height + "]");
        // the end of the luminance data
        final int lumEnd = width * height;
        // points to the next luminance value pair
        int lumPtr = 0;
        // points to the next chromiance value pair
        int chrPtr = lumEnd;
        // points to the next byte output pair of RGB565 value
        int outPtr = 0;
        // the end of the current luminance scanline
        int lineEnd = width;

        Log.i(TAG, "***** yCbCrToRGB565 - lumEnd:[" + lumEnd + "] lineEnd:[" + lineEnd + "]");

        while (true) {

            // skip back to the start of the chromiance values when necessary
            if (lumPtr == lineEnd) {
                if (lumPtr == lumEnd)
                    break; // we've reached the end
                // division here is a bit expensive, but's only done once per
                // scanline
                chrPtr = lumEnd + ((lumPtr >> 1) / width) * width;
                lineEnd += width;
            }

            // read the luminance and chromiance values
            final int Y1 = YCBCRs[lumPtr++] & 0xff;
            final int Y2 = YCBCRs[lumPtr++] & 0xff;
            final int Cr = (YCBCRs[chrPtr++] & 0xff) - 128;
            final int Cb = (YCBCRs[chrPtr++] & 0xff) - 128;
            int R, G, B;

            // generate first RGB components
            B = Y1 + ((454 * Cb) >> 8);
            if (B < 0)
                B = 0;
            else if (B > 255)
                B = 255;
            G = Y1 - ((88 * Cb + 183 * Cr) >> 8);
            if (G < 0)
                G = 0;
            else if (G > 255)
                G = 255;
            R = Y1 + ((359 * Cr) >> 8);
            if (R < 0)
                R = 0;
            else if (R > 255)
                R = 255;
            // NOTE: this assume little-endian encoding
            rgbs[outPtr++] = (byte) (((G & 0x3c) << 3) | (B >> 3));
            rgbs[outPtr++] = (byte) ((R & 0xf8) | (G >> 5));

            // generate second RGB components
            B = Y2 + ((454 * Cb) >> 8);
            if (B < 0)
                B = 0;
            else if (B > 255)
                B = 255;
            G = Y2 - ((88 * Cb + 183 * Cr) >> 8);
            if (G < 0)
                G = 0;
            else if (G > 255)
                G = 255;
            R = Y2 + ((359 * Cr) >> 8);
            if (R < 0)
                R = 0;
            else if (R > 255)
                R = 255;
            // NOTE: this assume little-endian encoding
            rgbs[outPtr++] = (byte) (((G & 0x3c) << 3) | (B >> 3));
            rgbs[outPtr++] = (byte) ((R & 0xf8) | (G >> 5));
        }
    }

    private void preparePreviewCallbackBuffer(Size mPreviewSize) {
        // The actual preview width and height.
        // They can differ from the requested width mDesiredCameraPreviewWidth
        int mPreviewWidth = mPreviewSize.getWidth();
        int mPreviewHeight = mPreviewSize.getHeight();
        int bufferSizeRGB565 = mPreviewWidth * mPreviewHeight * 2 + 4096;
        //Delete buffer before creating a new one.
        mPreviewBufferRGB565 = null;
        mPreviewBufferRGB565 = new byte[bufferSizeRGB565];
        mPreviewByteBufferRGB565 = ByteBuffer.allocateDirect(mPreviewBufferRGB565.length);
        mCameraJMEImageRGB565 = new com.jme3.texture.Image(com.jme3.texture.Image.Format.RGB565, mPreviewWidth,
                mPreviewHeight, mPreviewByteBufferRGB565, ColorSpace.Linear);
    }

}
