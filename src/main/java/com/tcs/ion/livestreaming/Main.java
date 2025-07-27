package com.tcs.ion.livestreaming;

/**
 * Main entry point for the CCTV Recording Utility application.
 * Initializes the recording service and sets up a shutdown hook.
 */
public class Main {
    /**
     * Main method that starts the application.
     * 
     * @param args Command line arguments (not used)
     */
    public static void main(String[] args) {
        // Default paths for output and configuration
        String outputPath = "cctv-recording-output";
        String configFilePath = "config/camera-config.json";
        int bufferSize = 100;

        // Get the singleton instance of the recording service
        CCTVRecordingService cctvRecordingService = CCTVRecordingService.getInstance(outputPath, configFilePath, bufferSize);

        // Start the recording service
        cctvRecordingService.start();

        // Register a shutdown hook to ensure graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(cctvRecordingService::stop, "ShutdownHook-StopAll"));
    }
}
