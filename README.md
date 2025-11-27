# NLSNFC

NLSNFC is a simple Android application for stress reading testing of NFC tags. It provides information about scanned NFC tags, including their UID, type, and supported technologies. The app also maintains a history of scanned tags and logs any errors encountered during the reading process.

## Features

- **Real-time NFC Tag Reading:** Scans and displays information from NFC tags in real-time.
- **Tag Analysis:** Shows the tag's UID, type (e.g., MIFARE Classic, NFC-A), and supported protocols.
- **Scan History:** Keeps a log of all successfully scanned tags.
- **Error Logging:** Captures and displays any errors that occur during the NFC reading process.
- **NFC Availability Check:** Detects if the device has NFC capabilities and prompts the user to enable it if it's turned off.
- **Continuous Reading Mode:** Allows for repeated scanning of the same tag without having to move it away from the device (this works on Android 11+, does not work on Android 7.1.1).
- **Optional Server Update:** After every successful read, the app attempts to POST the last read info to https://labndevor.leoaidc.com/create using application/x-www-form-urlencoded with fields: DEV_TYPE, DEV_SN, NFC-COUNTER, NFC-UID, NFC-DATETIME.
- **Device Info Line:** Shows device model and serial. On Newland devices (e.g., NLS-MT90), the serial is read from /sys/bus/platform/devices/newland-misc/SN. If unavailable, the app falls back to ANDROID_ID.

## Getting Started

### Prerequisites

- Android Studio
- An Android device with NFC capabilities

### Installation

1. Clone the repo
   ```sh
   git clone https://github.com/orellanauser/NLSNFC.git
   ```
2. Open the project in Android Studio.
3. Build and run the project on your Android device.

## Usage

1. Launch the application.
2. If NFC is disabled, you will be prompted to enable it. Tap "Open Settings" to go to the NFC settings and turn it on.
3. Hold an NFC tag close to the back of your device.
4. The app will automatically read the tag and display its information.
5. You can switch between the "History" and "Errors" tabs to see the logs.

### Server update details (optional)

- The app sends a background POST request similar to:
  ```sh
  curl -X POST https://labndevor.leoaidc.com/create \
       -d "DEV_TYPE=YOUR_DEVICE_TYPE" \
       -d "DEV_SN=YOUR_DEVICE_SN" \
       -d "NFC-COUNTER=CURRENT-COUNTER" \
       -d "NFC-UID=YOUR_NFC_UID" \
       -d "NFC-DATETIME=YYYY-MM-DD%20HH:MM:SS"
  ```
- DEV_TYPE is the device model only (manufacturer omitted), e.g., "Pixel 7" or "NLS-MT90".
- DEV_SN is read from Newland's sysfs path when available: /sys/bus/platform/devices/newland-misc/SN. Otherwise, it falls back to ANDROID_ID as a stable per-device identifier for the app.
- If the request fails, a concise error entry is added to the Errors tab, but reading continues uninterrupted.

### Permissions

- NFC: Required to read tags.
- INTERNET: Required for the optional server POST after successful reads.
- NFC_ADAPTER (OEM/hidden): Declared defensively for certain OEMs that expose adapter toggling; it is ignored on most devices.

### Logging

- All diagnostic logs use the single tag: `NLSNFC`.
- To inspect logs:
  - Android Studio: Logcat filter by Tag = `NLSNFC`.
  - ADB: `adb logcat | grep NLSNFC`.
- Around each POST you should see: `POST start`, followed by either `POST success`, `POST http_error`, or `POST exception`.

### Privacy

- When server update is enabled (always on in this build), the following fields are sent to the server:
  - DEV_TYPE: device model only (e.g., "NLS-MT90").
  - DEV_SN: on Newland devices, read from `/sys/bus/platform/devices/newland-misc/SN`; otherwise ANDROID_ID.
  - NFC-COUNTER: monotonically increasing counter within the app session.
  - NFC-UID: UID of the scanned tag, uppercase hex with colons.
  - NFC-DATETIME: local device time formatted as `yyyy-MM-dd HH:mm:ss`.

### Troubleshooting

- If server updates don’t appear:
  - Check device connectivity.
  - Inspect Logcat (tag `NLSNFC`) for `POST http_error` or `POST exception` details.
  - Verify the device serial source in logs: either Newland sysfs or ANDROID_ID.

### Branching note

- This repository’s active development branch is `work-branch`. Please keep contributions on `work-branch` and do not switch the default to `main` for this push.

## License

MIT License

Copyright (c) 2025 Luis E. Orellana

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including, without limitation,n the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES, OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT, OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OF OTHER DEALINGS IN THE
SOFTWARE.

