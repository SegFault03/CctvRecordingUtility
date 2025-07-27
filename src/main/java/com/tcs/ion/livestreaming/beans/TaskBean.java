package com.tcs.ion.livestreaming.beans;

import java.util.concurrent.Future;

/**
 * Bean class that tracks an ffmpeg process and its associated future task.
 * Used to manage and control running recording processes.
 */
public class TaskBean {
    /** The running ffmpeg process */
    private Process ffmpegProcess;
    /** Future representing the task in the thread pool */
    private Future<?> futureTask;

    /**
     * Gets the ffmpeg process.
     *
     * @return The ffmpeg process
     */
    public Process getFfmpegProcess() {
        return ffmpegProcess;
    }

    /**
     * Sets the ffmpeg process.
     *
     * @param ffmpegProcess The ffmpeg process to set
     */
    public void setFfmpegProcess(Process ffmpegProcess) {
        this.ffmpegProcess = ffmpegProcess;
    }

    /**
     * Gets the future task.
     *
     * @return The future task
     */
    public Future<?> getFutureTask() {
        return futureTask;
    }

    /**
     * Sets the future task.
     *
     * @param futureTask The future task to set
     */
    public void setFutureTask(Future<?> futureTask) {
        this.futureTask = futureTask;
    }
}
