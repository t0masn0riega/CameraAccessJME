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

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.ViewGroup;

import com.jme3.app.AndroidHarness;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

//include packages for Android Location API
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

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

	private boolean stopPreview = false;
	Image cameraJMEImageRGB565;

	private LocationManager locationManager;
	private Location mLocation;

	private SensorManager sensorManager;
	Sensor rotationVectorSensor;
	Sensor gyroscopeSensor;
	Sensor magneticFieldSensor;
	Sensor accelSensor;
	Sensor linearAccelSensor;

	//Sensor Fusion
	//code from:
	//http://www.thousand-thoughts.com/2012/03/android-sensor-fusion-tutorial/
	// angular speeds from gyro
	private float[] gyro = new float[3];

	// rotation matrix from gyro data
	private float[] gyroMatrix = new float[9];

	// orientation angles from gyro matrix
	private float[] gyroOrientation = new float[3];

	// magnetic field vector
	private float[] magnet = new float[3];

	// accelerometer vector
	private float[] accel = new float[3];

	// orientation angles from accel and magnet
	private float[] accMagOrientation = new float[3];

	// final orientation angles from sensor fusion
	private float[] fusedOrientation = new float[3];

	// accelerometer and magnetometer based rotation matrix
	private float[] rotationMatrix = new float[9];

	public static final float EPSILON = 0.000001f;
	private static final float NS2S = 1.0f / 1000000000.0f;
	private float timestamp;
	private boolean initState = true;

	public static final int TIME_CONSTANT = 50;
	public static final float FILTER_COEFFICIENT = 0.98f;

	private Timer fuseTimer = new Timer();

	// calculates orientation angles from accelerometer and magnetometer output
	public void calculateAccMagOrientation() {
		if(SensorManager.getRotationMatrix(rotationMatrix, null, accel, magnet)) {
			SensorManager.getOrientation(rotationMatrix, accMagOrientation);
		}
	}

	// This function is borrowed from the Android reference
	// at http://developer.android.com/reference/android/hardware/SensorEvent.html#values
	// It calculates a rotation vector from the gyroscope angular speed values.
	private void getRotationVectorFromGyro(float[] gyroValues,
										   float[] deltaRotationVector,
										   float timeFactor)
	{
		float[] normValues = new float[3];

		// Calculate the angular speed of the sample
		float omegaMagnitude =
				(float)Math.sqrt(gyroValues[0] * gyroValues[0] +
						gyroValues[1] * gyroValues[1] +
						gyroValues[2] * gyroValues[2]);

		// Normalize the rotation vector if it's big enough to get the axis
		if(omegaMagnitude > EPSILON) {
			normValues[0] = gyroValues[0] / omegaMagnitude;
			normValues[1] = gyroValues[1] / omegaMagnitude;
			normValues[2] = gyroValues[2] / omegaMagnitude;
		}

		// Integrate around this axis with the angular speed by the timestep
		// in order to get a delta rotation from this sample over the timestep
		// We will convert this axis-angle representation of the delta rotation
		// into a quaternion before turning it into the rotation matrix.
		float thetaOverTwo = omegaMagnitude * timeFactor;
		float sinThetaOverTwo = (float)Math.sin(thetaOverTwo);
		float cosThetaOverTwo = (float)Math.cos(thetaOverTwo);
		deltaRotationVector[0] = sinThetaOverTwo * normValues[0];
		deltaRotationVector[1] = sinThetaOverTwo * normValues[1];
		deltaRotationVector[2] = sinThetaOverTwo * normValues[2];
		deltaRotationVector[3] = cosThetaOverTwo;
	}

	// This function performs the integration of the gyroscope data.
	// It writes the gyroscope based orientation into gyroOrientation.
	public void gyroFunction(SensorEvent event) {
		// don't start until first accelerometer/magnetometer orientation has been acquired
		if (accMagOrientation == null)
			return;

		// initialisation of the gyroscope based rotation matrix
		if(initState) {
			float[] initMatrix = new float[9];
			initMatrix = getRotationMatrixFromOrientation(accMagOrientation);
			float[] test = new float[3];
			SensorManager.getOrientation(initMatrix, test);
			gyroMatrix = matrixMultiplication(gyroMatrix, initMatrix);
			initState = false;
		}

		// copy the new gyro values into the gyro array
		// convert the raw gyro data into a rotation vector
		float[] deltaVector = new float[4];
		if(timestamp != 0) {
			final float dT = (event.timestamp - timestamp) * NS2S;
			System.arraycopy(event.values, 0, gyro, 0, 3);
			getRotationVectorFromGyro(gyro, deltaVector, dT / 2.0f);
		}

		// measurement done, save current time for next interval
		timestamp = event.timestamp;

		// convert rotation vector into rotation matrix
		float[] deltaMatrix = new float[9];
		SensorManager.getRotationMatrixFromVector(deltaMatrix, deltaVector);

		// apply the new rotation interval on the gyroscope based rotation matrix
		gyroMatrix = matrixMultiplication(gyroMatrix, deltaMatrix);

		// get the gyroscope based orientation from the rotation matrix
		SensorManager.getOrientation(gyroMatrix, gyroOrientation);
	}

	private float[] getRotationMatrixFromOrientation(float[] o) {
		float[] xM = new float[9];
		float[] yM = new float[9];
		float[] zM = new float[9];

		float sinX = (float)Math.sin(o[1]);
		float cosX = (float)Math.cos(o[1]);
		float sinY = (float)Math.sin(o[2]);
		float cosY = (float)Math.cos(o[2]);
		float sinZ = (float)Math.sin(o[0]);
		float cosZ = (float)Math.cos(o[0]);

		// rotation about x-axis (pitch)
		xM[0] = 1.0f; xM[1] = 0.0f; xM[2] = 0.0f;
		xM[3] = 0.0f; xM[4] = cosX; xM[5] = sinX;
		xM[6] = 0.0f; xM[7] = -sinX; xM[8] = cosX;

		// rotation about y-axis (roll)
		yM[0] = cosY; yM[1] = 0.0f; yM[2] = sinY;
		yM[3] = 0.0f; yM[4] = 1.0f; yM[5] = 0.0f;
		yM[6] = -sinY; yM[7] = 0.0f; yM[8] = cosY;

		// rotation about z-axis (azimuth)
		zM[0] = cosZ; zM[1] = sinZ; zM[2] = 0.0f;
		zM[3] = -sinZ; zM[4] = cosZ; zM[5] = 0.0f;
		zM[6] = 0.0f; zM[7] = 0.0f; zM[8] = 1.0f;

		// rotation order is y, x, z (roll, pitch, azimuth)
		float[] resultMatrix = matrixMultiplication(xM, yM);
		resultMatrix = matrixMultiplication(zM, resultMatrix);
		return resultMatrix;
	}

	private float[] matrixMultiplication(float[] A, float[] B) {
		float[] result = new float[9];

		result[0] = A[0] * B[0] + A[1] * B[3] + A[2] * B[6];
		result[1] = A[0] * B[1] + A[1] * B[4] + A[2] * B[7];
		result[2] = A[0] * B[2] + A[1] * B[5] + A[2] * B[8];

		result[3] = A[3] * B[0] + A[4] * B[3] + A[5] * B[6];
		result[4] = A[3] * B[1] + A[4] * B[4] + A[5] * B[7];
		result[5] = A[3] * B[2] + A[4] * B[5] + A[5] * B[8];

		result[6] = A[6] * B[0] + A[7] * B[3] + A[8] * B[6];
		result[7] = A[6] * B[1] + A[7] * B[4] + A[8] * B[7];
		result[8] = A[6] * B[2] + A[7] * B[5] + A[8] * B[8];

		return result;
	}

	class calculateFusedOrientationTask extends TimerTask {
		public void run() {
			float oneMinusCoeff = 1.0f - FILTER_COEFFICIENT;

			/*
			* Fix for 179° <--> -179° transition problem:
			* Check whether one of the two orientation angles (gyro or accMag) is negative while the other one is positive.
			* If so, add 360° (2 * math.PI) to the negative value, perform the sensor fusion, and remove the 360° from the result
			* if it is greater than 180°. This stabilizes the output in positive-to-negative-transition cases.
			*/

			// azimuth
			if (gyroOrientation[0] < -0.5 * Math.PI && accMagOrientation[0] > 0.0) {
				fusedOrientation[0] = (float) (FILTER_COEFFICIENT * (gyroOrientation[0] + 2.0 * Math.PI) + oneMinusCoeff * accMagOrientation[0]);
				fusedOrientation[0] -= (fusedOrientation[0] > Math.PI) ? 2.0 * Math.PI : 0;
			}
			else if (accMagOrientation[0] < -0.5 * Math.PI && gyroOrientation[0] > 0.0) {
				fusedOrientation[0] = (float) (FILTER_COEFFICIENT * gyroOrientation[0] + oneMinusCoeff * (accMagOrientation[0] + 2.0 * Math.PI));
				fusedOrientation[0] -= (fusedOrientation[0] > Math.PI)? 2.0 * Math.PI : 0;
			}
			else {
				fusedOrientation[0] = FILTER_COEFFICIENT * gyroOrientation[0] + oneMinusCoeff * accMagOrientation[0];
			}

			// pitch
			if (gyroOrientation[1] < -0.5 * Math.PI && accMagOrientation[1] > 0.0) {
				fusedOrientation[1] = (float) (FILTER_COEFFICIENT * (gyroOrientation[1] + 2.0 * Math.PI) + oneMinusCoeff * accMagOrientation[1]);
				fusedOrientation[1] -= (fusedOrientation[1] > Math.PI) ? 2.0 * Math.PI : 0;
			}
			else if (accMagOrientation[1] < -0.5 * Math.PI && gyroOrientation[1] > 0.0) {
				fusedOrientation[1] = (float) (FILTER_COEFFICIENT * gyroOrientation[1] + oneMinusCoeff * (accMagOrientation[1] + 2.0 * Math.PI));
				fusedOrientation[1] -= (fusedOrientation[1] > Math.PI)? 2.0 * Math.PI : 0;
			}
			else {
				fusedOrientation[1] = FILTER_COEFFICIENT * gyroOrientation[1] + oneMinusCoeff * accMagOrientation[1];
			}

			// roll
			if (gyroOrientation[2] < -0.5 * Math.PI && accMagOrientation[2] > 0.0) {
				fusedOrientation[2] = (float) (FILTER_COEFFICIENT * (gyroOrientation[2] + 2.0 * Math.PI) + oneMinusCoeff * accMagOrientation[2]);
				fusedOrientation[2] -= (fusedOrientation[2] > Math.PI) ? 2.0 * Math.PI : 0;
			}
			else if (accMagOrientation[2] < -0.5 * Math.PI && gyroOrientation[2] > 0.0) {
				fusedOrientation[2] = (float) (FILTER_COEFFICIENT * gyroOrientation[2] + oneMinusCoeff * (accMagOrientation[2] + 2.0 * Math.PI));
				fusedOrientation[2] -= (fusedOrientation[2] > Math.PI)? 2.0 * Math.PI : 0;
			}
			else {
				fusedOrientation[2] = FILTER_COEFFICIENT * gyroOrientation[2] + oneMinusCoeff * accMagOrientation[2];
			}

			// overwrite gyro matrix and orientation with fused orientation
			// to compensate gyro drift
			gyroMatrix = getRotationMatrixFromOrientation(fusedOrientation);
			System.arraycopy(fusedOrientation, 0, gyroOrientation, 0, 3);

			if ((com.ar4android.cameraAccessJME.JmeARapplication) app != null) {
				((com.ar4android.cameraAccessJME.JmeARapplication) app).setRotation((float) (fusedOrientation[2]), (float) (-fusedOrientation[0]), (float) (fusedOrientation[1]));
			}
		}
	}

	private final CameraWrapper.PreviewCallback mCameraCallback = new CameraWrapper.PreviewCallback() {
		public void onPreviewFrame(byte[] data) {
			if (data != null && stopPreview == false) {
				mPreviewByteBufferRGB565.clear();
				// Perform processing on the camera preview data.
				yCbCrToRGB565(data, mPreviewWidth, mPreviewHeight,
						mPreviewBufferRGB565);
				mPreviewByteBufferRGB565.put(mPreviewBufferRGB565);
				cameraJMEImageRGB565.setData(mPreviewByteBufferRGB565);
				if ((com.ar4android.cameraAccessJME.JmeARapplication) app != null) {
					((com.ar4android.cameraAccessJME.JmeARapplication) app)
							.setTexture(cameraJMEImageRGB565);
				}
			}
		}
	};

	private LocationListener locListener= new LocationListener() {

		private static final String TAG = "LocationListener";

		@Override
		public void onLocationChanged(Location location) {
			Log.d(TAG, "onLocationChanged: " + location.toString());
			mLocation = location;
			if ((com.ar4android.cameraAccessJME.JmeARapplication) app != null) {
				((com.ar4android.cameraAccessJME.JmeARapplication) app).setUserLocation(mLocation);
			}
		}

		@Override
		public void onProviderDisabled(String provider) {
			Log.d(TAG, "onProviderDisabled: " + provider);
		}

		@Override
		public void onProviderEnabled(String provider) {
			Log.d(TAG, "onProviderEnabled: " + provider);
		}

		@Override
		public void onStatusChanged(String provider, int status, Bundle extras) {
			Log.d(TAG, "onStatusChanged: " + status);
		}

	};

	private SensorEventListener sensorListener = new SensorEventListener() {

		final double NS2S = 1.0f / 1000000000.0f;

		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
			Log.d(TAG, "onAccuracyChanged: " + accuracy);

		}

		@Override
		public void onSensorChanged(SensorEvent event) {
			Log.d(TAG, "onSensorChanged: " + event.toString());

			switch(event.sensor.getType()) {
				case Sensor.TYPE_ACCELEROMETER:
					// copy new accelerometer data into accel array and calculate orientation
					System.arraycopy(event.values, 0, accel, 0, 3);
					calculateAccMagOrientation();
					break;
				case Sensor.TYPE_MAGNETIC_FIELD:
					// copy new magnetometer data into magnet array
					System.arraycopy(event.values, 0, magnet, 0, 3);
					break;
				case Sensor.TYPE_GYROSCOPE:
					// process gyro data
					gyroFunction(event);
					break;
				case Sensor.TYPE_ROTATION_VECTOR:
					float[] rotationVector= {event.values[0],event.values[1], event.values[2]};
					Log.d(TAG, "Sensor.TYPE_ROTATION_VECTO: rotationVector:[" + Arrays.toString(rotationVector) + "]");
					float[] quaternion = {0.f,0.f,0.f,0.f};
					sensorManager.getQuaternionFromVector(quaternion,rotationVector);
					float qw = quaternion[0]; float qx = quaternion[1];
					float qy = quaternion[2]; float qz = quaternion[3];
					double headingQ = Math.atan2(2*qy*qw-2*qx*qz , 1 - 2*qy*qy - 2*qz*qz);
					double pitchQ = Math.asin(2*qx*qy + 2*qz*qw);
					double rollQ = Math.atan2(2*qx*qw-2*qy*qz , 1 - 2*qx*qx - 2*qz*qz);
					if ((com.ar4android.cameraAccessJME.JmeARapplication) app != null) {
//						((com.ar4android.cameraAccessJME.JmeARapplication) app).setRotation((float)pitchQ, (float)rollQ, (float)headingQ);
					}
					break;

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

	protected Sensor initSingleSensor( int type, String name ){
		Sensor newSensor = sensorManager.getDefaultSensor(type);
		if(newSensor != null){
			if(sensorManager.registerListener(sensorListener, newSensor, SensorManager.SENSOR_DELAY_GAME)) {
				Log.i(TAG, name + " successfully registered default");
			} else {
				Log.e(TAG, name + " not registered default");
			}
		} else {
			List<Sensor> deviceSensors = sensorManager.getSensorList(type);
			if(deviceSensors.size() > 0){
				Sensor mySensor = deviceSensors.get(0);
				if(sensorManager.registerListener(sensorListener, mySensor, SensorManager.SENSOR_DELAY_GAME)) {
					Log.i(TAG, name + " successfully registered to " + mySensor.getName());
				} else {
					Log.e(TAG, name + " not registered to " + mySensor.getName());
				}
			} else {
				Log.e(TAG, "No " + name + " sensor!");
			}
		}
		return newSensor;
	}

	protected void initSensors(){
		// look specifically for the gyroscope first and then for the rotation_vector_sensor (underlying sensors vary from platform to platform)
		gyroscopeSensor = initSingleSensor(Sensor.TYPE_GYROSCOPE, "TYPE_GYROSCOPE");
		rotationVectorSensor = initSingleSensor(Sensor.TYPE_ROTATION_VECTOR, "TYPE_ROTATION_VECTOR");
		accelSensor = initSingleSensor(Sensor.TYPE_ACCELEROMETER, "TYPE_ACCELEROMETER");
		linearAccelSensor = initSingleSensor(Sensor.TYPE_LINEAR_ACCELERATION, "TYPE_LINEAR_ACCELERATION");
		magneticFieldSensor = initSingleSensor(Sensor.TYPE_MAGNETIC_FIELD, "TYPE_MAGNETIC_FIELD");
	}


	public Camera2AccessJMEActivity() {
		// Set the application class to run
//		appClass = "com.ar4android.cameraAccessJME.CameraAccessJME";
//		appClass = "com.ar4android.cameraAccessJME.SuperimposeJME";
//		appClass = "com.ar4android.cameraAccessJME.LocationAccessJME";
		appClass = "com.ar4android.cameraAccessJME.SensorAccessJME";

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

		//sensor fusion setup
		gyroOrientation[0] = 0.0f;
		gyroOrientation[1] = 0.0f;
		gyroOrientation[2] = 0.0f;

		// initialise gyroMatrix with identity matrix
		gyroMatrix[0] = 1.0f; gyroMatrix[1] = 0.0f; gyroMatrix[2] = 0.0f;
		gyroMatrix[3] = 0.0f; gyroMatrix[4] = 1.0f; gyroMatrix[5] = 0.0f;
		gyroMatrix[6] = 0.0f; gyroMatrix[7] = 0.0f; gyroMatrix[8] = 1.0f;


		// sensor setup
		sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
		List<Sensor> deviceSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
		Log.d(TAG, "Integrated sensors:");
		for(int i = 0; i < deviceSensors.size(); ++i ) {
			Sensor curSensor = deviceSensors.get(i);
			Log.d(TAG, curSensor.getName() + "\t" + curSensor.getType() + "\t" + curSensor.getMinDelay() / 1000.0f);
		}
		initSensors();

		// wait for one second until gyroscope and magnetometer/accelerometer
		// data is initialised then scedule the complementary filter task
		fuseTimer.scheduleAtFixedRate(new calculateFusedOrientationTask(), 1000, TIME_CONSTANT);

		cameraJMEImageRGB565 = new Image(Image.Format.RGB565, 0,
				0, null, ColorSpace.Linear);
		mPreview = new Camera2Preview(this, mCameraCallback, mCameraPreviewSizeCallback);

		Log.i(TAG, " ***** onCreate");
	}

	@Override
    public void onResume() {
    	super.onResume();

		// make sure the AndroidGLSurfaceView view is on top of the view
		// hierarchy
		view.setZOrderOnTop(true);

		// Choose screen orientation
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

		locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
		locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, locListener);

		if (mLocation == null) {
			try {
				if (locationManager != null) {
					mLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
				}
				Log.d(TAG, "Setting initial location: " + mLocation.toString());
				if ((com.ar4android.cameraAccessJME.JmeARapplication) app != null) {
					((com.ar4android.cameraAccessJME.JmeARapplication) app).setUserLocation(mLocation);
				}
			} catch (Exception e){
				AlertDialog.Builder builder = new AlertDialog.Builder(this);
				//Chain together various setter methods to set the dialog characteristics
				builder.setMessage("Please make sure you enabled your GPS sensor and already retrieved an initial position.").setTitle("GPS Error");
				builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						// do nothing
					}
				});
				// Get the AlertDialog from create()
				AlertDialog dialog = builder.create();
				dialog.show();

			}

		}


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

	@Override
	public void onStop() {
		super.onStop();
		sensorManager.unregisterListener(sensorListener);
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
