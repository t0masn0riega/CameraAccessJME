package com.ar4android.cameraAccessJME;

import android.location.Location;

import com.jme3.app.SimpleApplication;
import com.jme3.texture.Image;

/**
 * Created by norto02 on 2/19/2016.
 */
public abstract class JmeARapplication extends SimpleApplication {
    abstract void setTexture(final Image image);
    void setUserLocation(Location location) {};
    public void setRotation(float pitch, float roll, float heading){};
}
