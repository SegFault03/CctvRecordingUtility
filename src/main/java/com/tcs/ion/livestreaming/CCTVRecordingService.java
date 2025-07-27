package com.tcs.ion.livestreaming;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tcs.ion.livestreaming.beans.CameraRecordingDetails;
import com.tcs.ion.livestreaming.beans.TaskBean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * Service for managing CCTV camera recordings using RTSP streams and ffmpeg.
 * This service handles scheduling recordings, managing ffmpeg processes,
 * and organizing output files for multiple cameras.
 */
public class CCTVRecordingService {
    /** Base directory for all camera recordings */
    private final String RECORDING_OUTPUT_PATH;
    /** Path to JSON configuration file */
    private final String CAMERA_CONFIG_FILE_PATH;
    /** Queue size */
    private final int BUFFER_SIZE;
    /** Size of each video segment in seconds */
    private int chunkSize;
    /** Scheduled recording start time in format "yyyy-MM-dd HH:mm:ss" */
    private String startTime;
    /** Scheduled recording end time in format "yyyy-MM-dd HH:mm:ss" */
    private String endTime;
    /** Map of active camera configurations indexed by resource ID */
    private ConcurrentHashMap<Integer, CameraRecordingDetails> currentlyRecordingCameraDetailMap;
    /** Map of running tasks and processes indexed by camera resource ID */
    private ConcurrentHashMap<Integer, TaskBean> futureTaskMap;
    /** BlockingQueue for storing new recording files to be processed and uploaded */
    private BlockingQueue<String> filesPathsToProcess;
    /** Thread pool for file system watch services */
    private ExecutorService watchServiceThreadPool;
    /** Thread pool for ffmpeg processes */
    private ExecutorService ffmpegTaskThreadPool;
    /** Thread pool for scheduling start/stop events */
    private ScheduledExecutorService startStopRecordingScheduler;
    /** JSON parser */
    private static final Gson gson = new Gson();
    /** Singleton instance */
    private static CCTVRecordingService cctvRecordingService;
    /** Logger instance */
    private static final Log logger  = LogFactory.getLog(CCTVRecordingService.class);

    /**
     * Private constructor for singleton pattern.
     * 
     * @param RECORDING_OUTPUT_PATH Base directory where all recordings will be stored
     * @param CAMERA_CONFIG_FILE_PATH Path to the JSON configuration file
     */
    private CCTVRecordingService(String RECORDING_OUTPUT_PATH, String CAMERA_CONFIG_FILE_PATH, int bufferSize) {
        this.RECORDING_OUTPUT_PATH = RECORDING_OUTPUT_PATH;
        this.CAMERA_CONFIG_FILE_PATH = CAMERA_CONFIG_FILE_PATH;
        this.BUFFER_SIZE = bufferSize;
    }

    /**
     * Gets the singleton instance of the CCTVRecordingService.
     * Creates a new instance if one doesn't exist.
     * 
     * @param outputRecordingPath Base directory where all recordings will be stored
     * @param cameraConfigFilePath Path to the JSON configuration file
     * @return The singleton instance of CCTVRecordingService
     */
    public static CCTVRecordingService getInstance(String outputRecordingPath, String cameraConfigFilePath, int bufferSize) {
        if(cctvRecordingService == null) {
            cctvRecordingService = new CCTVRecordingService(outputRecordingPath, cameraConfigFilePath, bufferSize);
        }
        return cctvRecordingService;
    }

    /**
     * Starts the CCTV recording service.
     * Initializes data structures, clears output directories, and initiates the service.
     * This method is called when the application starts.
     */
    public void start() {
        logger.info("Application started");
        futureTaskMap = new ConcurrentHashMap<>();
        // TODO handle exceptions from initService and clearDirectories here
        clearAllDirectories();
        initiateService();
    }

    /**
     * Stops all recording processes and services.
     * This method is called when the application is shutting down,
     * typically from a shutdown hook.
     */
    public void stop() {
        logger.warn("External shutdown initiated");
        stopAllServices();
    }

    /**
     * Initializes or reinitializes the recording service.
     * This method is synchronized to prevent concurrent initialization.
     * It performs the following steps:
     * 1. Stops any currently running services
     * 2. Reads the configuration file
     * 3. Validates configuration data
     * 4. Creates thread pools for various tasks
     * 5. Creates output directories
     * 6. Sets up file system watchers
     * 7. Schedules recording start and stop times
     */
    synchronized private void initiateService() {
        stopAllServices();
        currentlyRecordingCameraDetailMap = new ConcurrentHashMap<>(readConfigFile());
        System.out.println(currentlyRecordingCameraDetailMap);
        if(currentlyRecordingCameraDetailMap.isEmpty()) {
            logger.info("No camera configs found. Waiting for updated configs...");
            return;
        }
        long startDelay = getTimeDifference(startTime);
        long endDelay = getTimeDifference(endTime);
        if(endDelay <= startDelay || endDelay == -1 ) {
            logger.error("endTime must be greater than startTime. Terminating service.");
            return;
        }
        watchServiceThreadPool = Executors.newFixedThreadPool(currentlyRecordingCameraDetailMap.size() + 1);
        ffmpegTaskThreadPool = Executors.newFixedThreadPool(currentlyRecordingCameraDetailMap.size());
        startStopRecordingScheduler = Executors.newSingleThreadScheduledExecutor();
        filesPathsToProcess = new LinkedBlockingQueue<>(BUFFER_SIZE);
        createOutputDirectories();
        setUpWatchServices();
        startStopRecordingScheduler.schedule(this::startRecording, getTimeDifference(startTime), TimeUnit.MILLISECONDS);
        startStopRecordingScheduler.schedule(this::stopAllServices, getTimeDifference(endTime), TimeUnit.MILLISECONDS);
    }

    /**
     * Gracefully stops all services and processes.
     * This method is synchronized to prevent concurrent shutdown operations.
     * It performs the following steps:
     * 1. Cancels any scheduled future tasks
     * 2. Stops any running ffmpeg processes
     * 3. Waits for all stop operations to complete
     * 4. Shuts down all thread pools
     */
    synchronized private void stopAllServices() {
        logger.info("Initiating shutdown of all services...");

        // Cancel and stop all scheduled and running tasks
        if (futureTaskMap != null && !futureTaskMap.isEmpty()) {
            List<Future<?>> stopFutures = new ArrayList<>();
            for (Map.Entry<Integer, TaskBean> entry : futureTaskMap.entrySet()) {
                TaskBean taskBean = entry.getValue();

                // Cancel scheduled/running ffmpeg task
                Future<?> future = taskBean.getFutureTask();
                if (future != null && !future.isDone()) {
                    boolean cancelled = future.cancel(true);
                    logger.info("Cancelled future task for cameraId " + entry.getKey() + ": " + cancelled);
                }

                // Stop ffmpeg process if running
                Process ffmpegProcess = taskBean.getFfmpegProcess();
                if (ffmpegProcess != null && ffmpegProcess.isAlive()) {
                    String cameraName = currentlyRecordingCameraDetailMap.get(entry.getKey()).getCameraName();
                    stopFfmpegAsync(stopFutures, cameraName, ffmpegProcess);
                }
            }

            // Wait for all ffmpeg stop tasks to finish
            if (!stopFutures.isEmpty() && ffmpegTaskThreadPool != null) {
                for (Future<?> stopFuture : stopFutures) {
                    try {
                        stopFuture.get();
                    } catch (Exception e) {
                        logger.error("Error waiting for ffmpeg process to stop: " + e.getMessage(), e);
                    }
                }
            }
        } else {
            logger.info("No processes or tasks need to be shutdown. Resuming normal flow");
        }

        // Shutdown all executors
        shutdownExecutor(watchServiceThreadPool, "watchServiceThreadPool");
        shutdownExecutor(startStopRecordingScheduler, "startStopRecordingScheduler");
        shutdownExecutor(ffmpegTaskThreadPool, "ffmpegTaskThreadPool");
        logger.info("All services have been shut down.");
    }

    /**
     * Asynchronously stops an ffmpeg process.
     * Submits a task to stop the ffmpeg process to the thread pool.
     * 
     * @param stopFutures List to collect futures for all stop operations
     * @param cameraName Name of the camera for logging purposes
     * @param ffmpegProcess The ffmpeg process to stop
     */
    private void stopFfmpegAsync(List<Future<?>> stopFutures, String cameraName, Process ffmpegProcess) {
        if (ffmpegTaskThreadPool != null && !ffmpegTaskThreadPool.isShutdown()) {
            Future<?> stopFuture = ffmpegTaskThreadPool.submit(() -> {
                try {
                    stopFfmpegProcess(cameraName, ffmpegProcess);
                } catch (InterruptedException e) {
                    logger.error("Interrupted while stopping ffmpeg process for camera: " + cameraName, e);
                    Thread.currentThread().interrupt();
                }
            });
            stopFutures.add(stopFuture);
        }
    }

    /**
     * Gracefully shuts down an executor service.
     * Attempts a graceful shutdown first, then forces shutdown if necessary.
     * 
     * @param executor The executor service to shut down
     * @param name Name of the executor for logging purposes
     */
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            logger.info("Shutting down executor: " + name);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                    logger.warn(name + " did not terminate in time. Forcing shutdown.");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted while shutting down " + name, e);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Stops an ffmpeg process, attempting a graceful shutdown first.
     * If the graceful shutdown fails, forces the process to terminate.
     * 
     * @param cameraName Name of the camera for logging purposes
     * @param ffmpegProcess The ffmpeg process to stop
     * @throws InterruptedException If the thread is interrupted while waiting for the process to stop
     */
    private void stopFfmpegProcess(String cameraName, Process ffmpegProcess) throws InterruptedException {
        logger.info("Attempting to stop ffmpeg process for camera: " + cameraName);
        ffmpegProcess.destroy();
        boolean hasFinished = ffmpegProcess.waitFor(15, TimeUnit.SECONDS);
        if(!hasFinished) {
            logger.info("Attempt to stop ffmpeg process for camera: " + cameraName + " gracefully failed. Initiating forceful shutdown");
            ffmpegProcess.destroyForcibly();
        }
        logger.info("Ffmpeg process for camera: " + cameraName + " shutdown successfully.");
    }

    /**
     * Clears all files and subdirectories in the output directory.
     * Ensures a clean slate for new recordings by removing any previous files.
     * Preserves the root output directory itself.
     */
    private void clearAllDirectories() {
        File outputDirectory = new File(RECORDING_OUTPUT_PATH);
        if(!(outputDirectory.exists() && outputDirectory.isDirectory())) {
            logger.info("Output directory:" + outputDirectory.getAbsolutePath() + " does not exist. No need to clear old files.");
            return;
        }
        logger.info("Output directory:" + outputDirectory.getAbsolutePath() + " exists. Deleting old files and sub-folders");
        try(Stream<Path> filePaths = Files.walk(Paths.get(RECORDING_OUTPUT_PATH))){
                filePaths.filter(filePath -> !filePath.toString().equals(RECORDING_OUTPUT_PATH))
                        .sorted(Comparator.reverseOrder()) // Delete files before directories
                        .forEach(filePath -> {
                            try {
                                Files.delete(filePath);
                            } catch (IOException e) {
                                logger.error("Failed to delete " + filePath + ": " + e.getMessage(), e);
                            }
                        });

        } catch (IOException e) {
            logger.error("Error processing directory " + RECORDING_OUTPUT_PATH + ": " + e.getMessage(), e);
        }
    }

    /**
     * Reads and parses the camera configuration file.
     * Waits for the file to exist, polling every 30 seconds.
     * Extracts settings and camera details from the JSON configuration.
     * 
     * @return Map of camera details indexed by resource ID
     */
    private Map<Integer, CameraRecordingDetails> readConfigFile(){
        File cameraConfigPath = new File(CAMERA_CONFIG_FILE_PATH);
        Map<Integer, CameraRecordingDetails> cameraRecordingDetailsMap = new HashMap<>();
        while (!cameraConfigPath.exists()) {
            logger.info("config.json not found at: " + cameraConfigPath.getAbsolutePath() + ", retrying in 30 seconds...");
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
                Thread.currentThread().interrupt();
            }
        }
        logger.info("config.json found at: " + cameraConfigPath.getAbsolutePath() + ", attempting to read it.");
        try(FileReader configFileReader = new FileReader(cameraConfigPath)) {
            // TODO Implement JSON validation
            JsonObject configJsonObject = gson.fromJson(configFileReader, JsonObject.class);
            chunkSize = configJsonObject.get("chunkSize").getAsInt();
            startTime = configJsonObject.get("startTime").getAsString();
            endTime = configJsonObject.get("endTime").getAsString();
            for(JsonElement element: configJsonObject.getAsJsonArray("cameraDetails")) {
                if(element.isJsonObject()) {
                    CameraRecordingDetails cameraRecordingDetails = gson.fromJson(element, CameraRecordingDetails.class);
                    cameraRecordingDetailsMap.put(cameraRecordingDetails.getRes_id(), cameraRecordingDetails);
                }
            }
            logger.info("config.json read successfully");
        } catch (IOException e){
            logger.error("Failed to read config.json", e);
        }
        return cameraRecordingDetailsMap;
    }

    /**
     * Creates output and log directories for each camera.
     * For each camera, creates:
     * - An output directory for storing MP4 files
     * - A log directory for storing ffmpeg process logs
     */
    private void createOutputDirectories() {
        for(Map.Entry<Integer, CameraRecordingDetails> entry: currentlyRecordingCameraDetailMap.entrySet()){
            String mp4FilesOutputPath = RECORDING_OUTPUT_PATH + "/" + entry.getValue().getCameraName() + "/output";
            String logPath = RECORDING_OUTPUT_PATH + "/" + entry.getValue().getCameraName() + "/log";
            try {
                Files.createDirectories(Paths.get(mp4FilesOutputPath));
                Files.createDirectories(Paths.get(logPath));
            } catch (IOException e) {
                logger.error("Failed to create output or log directory for: " + entry.getValue().getCameraName(), e);
            }
        }
        logger.info("Successfully created output and log directories for each camera");
    }

    /**
     * Sets up file system watch services for monitoring file changes.
     * Creates two types of watch services:
     * 1. For each camera's output directory to detect new recordings
     * 2. For the configuration file directory to detect config changes
     * <p>
     * When the configuration file changes, the service is reinitialized.
     */
    private void setUpWatchServices() {
        // Set up watch services for each camera's output directory
        for(Map.Entry<Integer, CameraRecordingDetails> entry: currentlyRecordingCameraDetailMap.entrySet()){
            watchServiceThreadPool.submit(() -> {
                Path pathToWatch = Paths.get(RECORDING_OUTPUT_PATH + "/" + entry.getValue().getCameraName() + "/output");
                try(WatchService watchService = FileSystems.getDefault().newWatchService();) {
                    pathToWatch.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE);
                    logger.info("Successfully registered watch service for: " + entry.getValue().getCameraName());
                    while (true) {
                        WatchKey key;
                        try {
                            key = watchService.take(); // Waits for a key to be signaled
                        } catch (InterruptedException e) {
                            logger.error("WatchService Thread for " + entry.getValue().getCameraName() +" interrupted.", e);
                            Thread.currentThread().interrupt();
                            return; // Exit if interrupted
                        }

                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();

                            // Check for the overflow event first!
                            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                                logger.warn("File events may have been lost (queue overflow)!");
                                continue; // Skip to the next event
                            }

                            if(kind == StandardWatchEventKinds.ENTRY_CREATE) {
                                logger.info("New file: " + event.context() + " added at " + pathToWatch);
                                Path relativePath = (Path) event.context();
                                String absoluteFilePath = new File(pathToWatch.resolve(relativePath).toUri()).getAbsolutePath();
                                // Try to add to the queue but only wait for 2 seconds.
                                boolean added = filesPathsToProcess.offer(absoluteFilePath, 2, TimeUnit.SECONDS);
                                System.out.println("Queue: " + filesPathsToProcess);
                                if (!added) {
                                    // The queue was still full after 2 seconds.
                                    // Log this problem so you can investigate.
                                    logger.error("Could not add file to processing queue: " + absoluteFilePath);
                                }
                            } else {
                                logger.info("Invalid action (DELETION, MODIFY) performed at " + pathToWatch);
                            }
                        }

                        boolean valid = key.reset(); // Reset the key to receive further events
                        if (!valid) {
                            break; // Directory is no longer accessible or registered
                        }
                    }
                } catch (IOException e) {
                    logger.error("Failed to register watch service for: " + entry.getValue().getCameraName(), e);
                } catch (InterruptedException e) {
                    logger.error("Watch service Thread for: " + entry.getValue().getCameraName() +" was interrupted", e);
                }
            });
        }

        // Set up watch service for the configuration file
        watchServiceThreadPool.submit(()->{
            Path pathToWatch = Paths.get(CAMERA_CONFIG_FILE_PATH.substring(0, CAMERA_CONFIG_FILE_PATH.lastIndexOf("/")));
            try(WatchService watchService = FileSystems.getDefault().newWatchService()){
                pathToWatch.register(watchService,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                logger.info("Successfully registered watch service for: " + CAMERA_CONFIG_FILE_PATH);
                while(true){
                    WatchKey key;
                    try {
                        key = watchService.take(); // Waits for a key to be signaled
                    } catch (InterruptedException e) {
                        logger.error("WatchService Thread for " + CAMERA_CONFIG_FILE_PATH + " interrupted.", e);
                        Thread.currentThread().interrupt();
                        return; // Exit if interrupted
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();

                        if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                            logger.info("File: " + event.context() + " modified. Reloading changes");
                            initiateService();
                        } else {
                            logger.info("Invalid action (DELETION, INSERTION) performed at " + pathToWatch);
                        }
                    }

                    boolean valid = key.reset(); // Reset the key to receive further events
                    if (!valid) {
                        break; // Directory is no longer accessible or registered
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to register watch service for: " + CAMERA_CONFIG_FILE_PATH, e);
            }
        });
    }

    /**
     * Starts recording for all configured cameras.
     * For each camera, this method:
     * 1. Creates a task bean to track the process and future
     * 2. Submits an ffmpeg task to the thread pool
     * 3. Configures logging for the ffmpeg process
     * 4. Starts the ffmpeg process with the appropriate parameters
     * 5. Stores the task in the futureTaskMap for later management
     */
    private void startRecording() {
        for(Map.Entry<Integer, CameraRecordingDetails> entry: currentlyRecordingCameraDetailMap.entrySet()){
            TaskBean taskBean = new TaskBean();
            Future<?> ffmpegTask = ffmpegTaskThreadPool.submit(()->{
               List<String> ffmpegArgs = getFfmpegArgs(entry.getValue());
                String ffmpegLogFilePath = RECORDING_OUTPUT_PATH + "/" + entry.getValue().getCameraName() + "/log/ffmpeg_process.log";
                ProcessBuilder.Redirect fileRedirect = ProcessBuilder.Redirect.appendTo(new File(ffmpegLogFilePath));
                ProcessBuilder ffmpegProcessBuilder = new ProcessBuilder(ffmpegArgs);
                ffmpegProcessBuilder.redirectError(fileRedirect);
                try {
                    Process ffmpegProcess = ffmpegProcessBuilder.start();
                    taskBean.setFfmpegProcess(ffmpegProcess);
                    logger.info("Successfully started ffmpeg process for " + entry.getValue().getCameraName()
                            + ". Logs can be found at " + ffmpegLogFilePath);
                } catch (IOException e) {
                    logger.error("Failed to start ffmpeg process for " + entry.getValue().getCameraName(), e);
                }
            });
            taskBean.setFutureTask(ffmpegTask);
            futureTaskMap.put(entry.getKey(), taskBean);
        }
    }

    /**
     * Builds the ffmpeg command arguments for a camera.
     * Configures ffmpeg to:
     * - Read from the camera's RTSP stream
     * - Copy the stream without re-encoding
     * - Segment the output into time-based chunks
     * - Name output files with timestamps
     * 
     * @param cameraRecordingDetails Camera configuration details
     * @return List of command-line arguments for the ffmpeg process
     */
    private List<String> getFfmpegArgs(CameraRecordingDetails cameraRecordingDetails) {
        List<String> ffmpegArgs = new ArrayList<>();
        String outputFolder = RECORDING_OUTPUT_PATH + "/" + cameraRecordingDetails.getCameraName() +"/output/";
        ffmpegArgs.add("ffmpeg");
        ffmpegArgs.add("-fflags");
        ffmpegArgs.add("+genpts");         // Generate presentation timestamps
        ffmpegArgs.add("-i");
        ffmpegArgs.add(cameraRecordingDetails.getRtspUrl());
        ffmpegArgs.add("-c");
        ffmpegArgs.add("copy");            // Copy stream without re-encoding
        ffmpegArgs.add("-f");
        ffmpegArgs.add("segment");         // Output format: segmented
        ffmpegArgs.add("-segment_time");   // Segment duration
        ffmpegArgs.add(String.valueOf(chunkSize));
        ffmpegArgs.add("-strftime");
        ffmpegArgs.add("1");               // Enable timestamp in filename
        ffmpegArgs.add(outputFolder + "recording_%Y%m%d-%H%M%S.mp4");
        return ffmpegArgs;
    }

    /**
     * Calculates the time difference in milliseconds between now and a specified time.
     *
     * @param dateTimeStr Date and time string in format "yyyy-MM-dd HH:mm:ss"
     * @return Time difference in milliseconds, or -1 if the time is in the past or invalid
     */
    private long getTimeDifference(String dateTimeStr) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        try {
            LocalDateTime inputTime = LocalDateTime.parse(dateTimeStr, formatter);
            LocalDateTime now = LocalDateTime.now();
            if (inputTime.isBefore(now)) {
                return -1;
            }
            return Duration.between(now, inputTime).toMillis();
        } catch (Exception e) {
            logger.error("Invalid date time format: " + dateTimeStr, e);
            return -1;
        }
    }

}

