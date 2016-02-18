package com.ar4android.cameraAccessJME;

import android.util.Size;

import java.io.File;

/**
 * Created by norto02 on 2/18/2016.
 */
public interface CameraWrapper {
    interface PreviewCallback {
        public void onPreviewFrame(byte[] data);
    }

    interface PreviewSizeCallback {
        public void onPreviewSizeChange(Size previewSize);
    }

    Size openCamera(int width, int height);
    void closeCamera();
    void takePicture(File picFile);
    void setPreviewCallback(PreviewCallback cb);
    void setPreviewSizeCallback(PreviewSizeCallback cb);
}
