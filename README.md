# CCTV Recording Utility

A Java-based utility for scheduling and managing CCTV camera recordings using RTSP streams and ffmpeg.

## Overview

This application allows users to schedule and record video from multiple CCTV cameras using RTSP streams. The utility uses ffmpeg to capture and segment video into manageable chunks, with configurable recording times and segment durations.

## Features

- Schedule recording start and end times
- Support for multiple cameras simultaneously
- Configurable video segment size
- Automatic directory management
- Runtime configuration updates
- Graceful shutdown handling

## Requirements

- Java 8 or higher
- ffmpeg installed and available in system PATH
- Valid RTSP stream URLs

## Configuration

The application requires a JSON configuration file located at `config/camera-config.json` with the following structure:

```json
{
  "chunkSize": 60,
  "startTime": "2023-07-27 10:00:00",
  "endTime": "2023-07-27 18:00:00",
  "cameraDetails": [
    {
      "res_id": 1,
      "cameraName": "frontdoor",
      "rtspUrl": "rtsp://username:password@192.168.1.100:554/stream1"
    },
    {
      "res_id": 2,
      "cameraName": "backyard",
      "rtspUrl": "rtsp://username:password@192.168.1.101:554/stream1"
    }
  ]
}
```

- `chunkSize`: Duration of each video segment in seconds
- `startTime`: Scheduled start time in "yyyy-MM-dd HH:mm:ss" format
- `endTime`: Scheduled end time in "yyyy-MM-dd HH:mm:ss" format
- `cameraDetails`: Array of camera configurations
    - `res_id`: Unique identifier for the camera
    - `cameraName`: Name used for organizing output files
    - `rtspUrl`: RTSP URL for the camera stream

## Output Structure

Recordings are saved in the `cctv-recording-output` directory with the following structure:

```
cctv-recording-output/
├── {cameraName1}/
│   ├── output/
│   │   ├── recording_YYYYMMDD-HHMMSS.mp4
│   │   └── ...
│   └── log/
│       └── ffmpeg_process.log
├── {cameraName2}/
│   ├── output/
│   │   └── ...
│   └── log/
│       └── ...
└── ...
```

## Usage

### Building the Project

```bash
mvn clean package
```

### Running the Application

```bash
java -jar CCTVRecordingUtility-1.0-SNAPSHOT.jar
```

## Runtime Behavior

1. The application starts and waits for the configured start time
2. At the start time, ffmpeg processes begin recording from each configured camera
3. Video is saved in segments of the configured chunk size
4. At the end time, all ffmpeg processes are gracefully terminated
5. If the configuration file is modified during runtime, the application automatically reloads it

## Extending the Project

The codebase uses a modular design that makes it easy to extend:

- Add new camera types by enhancing the `CameraRecordingDetails` class
- Implement additional recording formats by modifying the ffmpeg command parameters
- Create more sophisticated scheduling by extending the scheduler implementation

## Troubleshooting

Check the log files in the following locations:

1. Application logs in the `logs` directory
2. ffmpeg process logs in each camera's log directory