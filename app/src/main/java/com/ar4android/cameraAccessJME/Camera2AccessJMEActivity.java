/* CameraAccessJMEActivity - CameraAccessJME Example
 * 
 * Example Chapter 2
 * accompanying the book
 * "Augmented Reality for Android Application Development", Packt Publishing, 2013.
 * 
 * Copyright  2013 Jens Grubert, Raphael Grasset / Packt Publishing.
 * 
 */
package com.ar4android.cameraAccessJME;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.ViewGroup;

import com.jme3.app.AndroidHarness;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;

import java.nio.ByteBuffer;

//import com.jme3.system.android.AndroidConfigChooser.ConfigType;


public class Camera2AccessJMEActivity extends AndroidHarness {

	private static final String TAG = "CameraAccessJMEActivity";
	private Camera2Preview mPreview;
	private int mDesiredCameraPreviewWidth = 640;

	private byte[] mPreviewBufferRGB565 = null;
	java.nio.ByteBuffer mPreviewByteBufferRGB565;
	// the actual size of the preview images
	int mPreviewWidth;
	int mPreviewHeight;
	// If we have to convert the camera preview image into RGB565 or can use it
	// directly
	private boolean pixelFormatConversionNeeded = true;

	private boolean stopPreview = false;
	Image cameraJMEImageRGB565;

	private final CameraWrapper.PreviewCallback mCameraCallback = new CameraWrapper.PreviewCallback() {
		public void onPreviewFrame(byte[] data) {
			if (data != null && stopPreview == false) {
				mPreviewByteBufferRGB565.clear();
				// Perform processing on the camera preview data.
				yCbCrToRGB565(data, mPreviewWidth, mPreviewHeight,
						mPreviewBufferRGB565);
				mPreviewByteBufferRGB565.put(mPreviewBufferRGB565);
				cameraJMEImageRGB565.setData(mPreviewByteBufferRGB565);
				if ((com.ar4android.cameraAccessJME.CameraAccessJME) app != null) {
					((com.ar4android.cameraAccessJME.CameraAccessJME) app)
							.setTexture(cameraJMEImageRGB565);
				}
			}
		}
	};

	private final CameraWrapper.PreviewSizeCallback mCameraPreviewSizeCallback = new CameraWrapper.PreviewSizeCallback() {

		@Override
		public void onPreviewSizeChange(Size previewSize) {
			Log.i(TAG, " ***** onPreviewSizeChange - previewSize.getWidth():[" + previewSize.getWidth() +"] previewSize.getHeight():[" + previewSize.getHeight() + "]");
			mPreviewWidth = previewSize.getWidth();
			mPreviewHeight = previewSize.getHeight();

			preparePreviewCallbackBuffer(mPreviewWidth, mPreviewHeight);
		}
	};

	public Camera2AccessJMEActivity() {
		// Set the application class to run
		appClass = "com.ar4android.cameraAccessJME.CameraAccessJME";
		// Try ConfigType.FASTEST; or ConfigType.LEGACY if you have problems
//		eglConfigType = ConfigType.BEST;
		// Exit Dialog title & message
		exitDialogTitle = "Exit?";
		exitDialogMessage = "Press Yes";
		// Enable verbose logging
//		eglConfigVerboseLogging = false;
		// Choose screen orientation
//		screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
		// Invert the MouseEvents X (default = true)
		mouseEventsInvertX = true;
		// Invert the MouseEvents Y (default = true)
		mouseEventsInvertY = true;
	}

	// We override AndroidHarness.onCreate() to be able to add the SurfaceView
	// needed for camera preview
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		cameraJMEImageRGB565 = new Image(Image.Format.RGB565, 0,
				0, null, ColorSpace.Linear);
		mPreview = new Camera2Preview(this, mCameraCallback, mCameraPreviewSizeCallback);
		Log.i(TAG, " ***** onCreate");
	}

	@Override
    public void onResume() {
    	super.onResume();

		// Choose screen orientation
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

		ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(1, 1);
		addContentView(mPreview, lp);

		Log.i(TAG, " ***** onResume");
	}

	@Override
	protected void onPause() {
		super.onPause();
		// remove the SurfaceView
		ViewGroup parent = (ViewGroup) mPreview.getParent();
		parent.removeView(mPreview);

		Log.i(TAG, " ***** onPause");
	}

	// prepares the Camera preview callback buffers.
	private void preparePreviewCallbackBuffer(int mPreviewWidth, int mPreviewHeight) {
		Log.i(TAG, " ***** preparePreviewCallbackBuffer - mPreviewWidth:[" + mPreviewWidth +"] mPreviewHeight:[" + mPreviewHeight + "]");
		// The actual preview width and height.
		// They can differ from the requested width mDesiredCameraPreviewWidth
		int bufferSizeRGB565 = mPreviewWidth * mPreviewHeight * 2 + 4096;
		//Delete buffer before creating a new one.
		mPreviewBufferRGB565 = null;
		mPreviewBufferRGB565 = new byte[bufferSizeRGB565];
		mPreviewByteBufferRGB565 = ByteBuffer.allocateDirect(mPreviewBufferRGB565.length);
		cameraJMEImageRGB565 = new Image(Image.Format.RGB565, mPreviewWidth,
				mPreviewHeight, mPreviewByteBufferRGB565, ColorSpace.Linear);
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
}
