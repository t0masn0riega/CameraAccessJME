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
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.renderscript.RenderScript;
import android.util.Log;
import android.util.Size;
import android.view.ViewGroup;

import com.jme3.app.AndroidHarness;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;

import java.nio.ByteBuffer;
import java.util.List;

//import com.jme3.system.android.AndroidConfigChooser.ConfigType;


public class Camera2AccessJMEActivity extends AndroidHarness {

	private static final String TAG = "CameraAccessJMEActivity";
	private Camera2Preview mPreview;

	// If we have to convert the camera preview image into RGB565 or can use it
	// directly
	Image cameraJMEImageRGB565;
	private RenderScript mRS;

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
		mPreview = new Camera2Preview(this, cameraJMEImageRGB565);
		Log.i(TAG, " ***** onCreate");
	}

	@Override
    public void onResume() {
    	super.onResume();

		setContentView(mPreview);

		if ((CameraAccessJME) app != null) {
			((CameraAccessJME) app)
					.setTexture(cameraJMEImageRGB565);
		}

		Log.i(TAG, " ***** onResume");
	}

	@Override
	protected void onPause() {
		super.onPause();
		Log.i(TAG, " ***** onPause");
	}
}
