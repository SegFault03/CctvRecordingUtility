package com.tcs.ion.livestreaming.beans;

import java.util.Objects;

/**
 * Represents configuration details for a camera recording.
 * This class holds the necessary information to identify and connect to a camera.
 */
public class CameraRecordingDetails {
    /** Unique identifier for the camera */
    private int res_id;
    /** Name of the camera used for directory organization */
    private String cameraName;
    /** RTSP URL for the camera stream */
    private String rtspUrl;

    /**
     * Gets the resource ID of the camera.
     *
     * @return The unique identifier for the camera
     */
    public int getRes_id() {
        return res_id;
    }

    /**
     * Sets the resource ID of the camera.
     *
     * @param res_id The unique identifier for the camera
     */
    public void setRes_id(int res_id) {
        this.res_id = res_id;
    }

    /**
     * Gets the name of the camera.
     *
     * @return The camera name used for directory organization
     */
    public String getCameraName() {
        return cameraName;
    }

    /**
     * Sets the name of the camera.
     *
     * @param cameraName The camera name used for directory organization
     */
    public void setCameraName(String cameraName) {
        this.cameraName = cameraName;
    }

    /**
     * Gets the RTSP URL for the camera stream.
     *
     * @return The RTSP URL
     */
    public String getRtspUrl() {
        return rtspUrl;
    }

    /**
     * Sets the RTSP URL for the camera stream.
     *
     * @param rtspUrl The RTSP URL
     */
    public void setRtspUrl(String rtspUrl) {
        this.rtspUrl = rtspUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        CameraRecordingDetails that = (CameraRecordingDetails) o;
        return res_id == that.res_id && Objects.equals(cameraName, that.cameraName) && Objects.equals(rtspUrl, that.rtspUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(res_id, cameraName, rtspUrl);
    }

    @Override
    public String toString() {
        return "CameraRecordingDetails{" +
                "res_id=" + res_id +
                ", cameraName='" + cameraName + '\'' +
                ", rtspUrl='" + rtspUrl + '\'' +
                '}';
    }
}
