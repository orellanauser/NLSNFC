# NLSNFC - NFC Stress Read Test

NLSNFC is a specialized Android application designed for **NFC stress reading and stability testing**, primarily targeting **Newland Android 11+ devices**. Its core purpose is to continuously and robustly read NFC tags, provide real-time feedback on read events, and optionally report comprehensive data to a remote server, all while maintaining an uninterrupted testing process.

## Key Features

*   **Real-time NFC Tag Reading:** Scans and displays essential information from NFC tags in real-time, including their Unique Identifier (UID), detected type (e.g., MIFARE Classic, NFC-A), and supported communication protocols.
*   **Continuous Stress Testing (Polling):** Implements an aggressive polling mechanism to ensure reliable, repeated detection of the *same* NFC tag without requiring its physical removal and re-presentation. This is fundamental for prolonged stress test scenarios.
*   **"Ghost" Error Mitigation:** Features an advanced NFC adapter reset capability. This involves reflective calls to hidden `NfcAdapter.disable()` and `enable()` methods, specifically designed to reinitialize the NFC stack and address potential "ghost" tag errors or intermittent read failures during intense or long-duration testing.
*   **Read Counter & History:** Provides a real-time, incrementing counter for successful tag reads and maintains a navigable history of recent tag interactions for easy review.
*   **Comprehensive Error Logging:** Captures and displays any exceptions or issues encountered during the NFC reading process in a dedicated error log. All diagnostic logs use the unified tag: `NLSNFC` for simplified filtering in Logcat.
*   **NFC Availability Check & Guidance:** Proactively detects the device's NFC hardware status and, if NFC is disabled, guides the user with a prompt to enable it via system settings.
*   **Precise Device Identification:** Automatically identifies the device model and, for Newland devices, attempts to extract the serial number directly from a dedicated system file (`/sys/bus/platform/devices/newland-misc/SN`) for accurate test environment reporting. Falls back to `Settings.Secure.ANDROID_ID` if the OEM path is unavailable.
*   **Optional Background Server Reporting:** After each successful tag read, the application can asynchronously POST detailed read data to a remote server. This operation runs in the background to ensure it does not interrupt the continuous NFC reading process.

## Technical Implementation Details for Developers/Maintainers

### NFC Stack Management
*   **Continuous Reading Logic:** The application uses `NfcAdapter.enableReaderMode()` with `FLAG_READER_NO_PLATFORM_SOUNDS` and other technology flags. A `Handler` periodically disables and re-enables reader mode (default `pollIntervalMs` of 1000ms) to ensure the NFC controller aggressively rescans for tags, enabling consecutive reads of a stationary tag.
*   **Adaptive NFC Reset:** The `resetNfcAndStartReaderMode()` function employs Java reflection to invoke hidden `NfcAdapter.disable()` and `NfcAdapter.enable()` methods. This is a critical step for mitigating "ghost" tag issues or potential NFC stack instability, providing a more robust stress testing environment.
    *   **Caution:** Reliance on hidden APIs is inherently brittle. Future Android or OEM firmware updates may alter or remove these methods. The implementation includes a robust fallback to standard `enableReaderMode` if reflection fails.

### Device Information
*   **Newland-Specific Serial Number:** The `getDeviceSn()` method prioritizes reading the serial number from `/sys/bus/platform/devices/newland-misc/SN`. This direct hardware access ensures the most accurate device identification for Newland units. If this path is not readable, it defaults to `Settings.Secure.ANDROID_ID`.

### Server Reporting
*   **Endpoint:** The default POST endpoint is `https://labndevor.leoaidc.com/create`. This URL is currently hardcoded in `MainActivity.kt` and can be modified for different reporting destinations.
*   **Data Fields (application/x-www-form-urlencoded):**
    *   `DEV_TYPE`: Device model (e.g., "Pixel 7", "NLS-MT90"). Manufacturer is omitted.
    *   `DEV_SN`: Device Serial Number (from Newland sysfs path or `ANDROID_ID`).
    *   `NFC-COUNTER`: Monotonically increasing counter for reads within the current app session.
    *   `NFC-UID`: Unique Identifier of the scanned tag (uppercase hex with colons).
    *   `NFC-DATETIME`: Local device timestamp of the read (formatted as `yyyy-MM-dd HH:mm:ss`).
*   **Error Handling:** Failed POST requests are logged to the "Errors" tab and Logcat without interrupting the NFC reading process.

## Getting Started

### Prerequisites

*   Android Studio (latest stable version recommended)
*   An Android device with NFC capabilities (targeting Newland Android 11+ for full feature set)

### Installation

1.  **Clone the Repository:**
    ```sh
    git clone https://github.com/orellanauser/NLSNFC.git
    ```
2.  **Open in Android Studio:**
    Open the cloned project in Android Studio.
3.  **Build and Run:**
    Build and run the project onto your target Android device.

## Usage

1.  **Launch the Application:**
    Start the NLSNFC app on your device.
2.  **Enable NFC (if prompted):**
    If NFC is disabled, the app will prompt you. Tap "Open Settings" to navigate to NFC settings and enable it.
3.  **Present an NFC Tag:**
    Hold an NFC tag near the NFC antenna area of your device.
4.  **Monitor Readings:**
    The app will automatically detect and read the tag. The `Counter` will increment, and tag details will update in real-time.
5.  **Review Logs:**
    Use the "History" and "Errors" tabs to review a log of successful reads and any issues encountered.

## Permissions

*   `android.permission.NFC`: Essential for all NFC interactions (reading tags).
*   `android.permission.INTERNET`: Required for the optional background server reporting functionality.
*   `android.permission.NFC_ADAPTER` (OEM-specific/hidden): Included defensively. Some OEMs, particularly for industrial devices, expose this permission for programmatic control over the NFC adapter. It is typically ignored by the Android system on consumer devices.

## Logging

*   All application diagnostics and events are logged using the consistent tag: `NLSNFC`.
*   **To inspect logs:**
    *   **Android Studio Logcat:** Filter by Tag = `NLSNFC`.
    *   **ADB Command Line:** `adb logcat | grep NLSNFC`.
*   You will find detailed messages regarding NFC state changes, tag discoveries, tag processing, and server POST attempts (e.g., `POST start`, `POST success`, `POST http_error`, or `POST exception`).

## Privacy Considerations

When the optional server update is enabled (it is always active in this build), the following data fields are transmitted to the configured server endpoint:

*   `DEV_TYPE`: Device model only (e.g., "NLS-MT90", "Pixel 7").
*   `DEV_SN`: Device Serial Number. For Newland devices, this is read from `/sys/bus/platform/devices/newland-misc/SN`; otherwise, it defaults to the `ANDROID_ID`.
*   `NFC-COUNTER`: A session-specific, monotonically increasing count of successful NFC reads.
*   `NFC-UID`: The Unique Identifier of the scanned NFC tag (uppercase hexadecimal format with colons).
*   `NFC-DATETIME`: The local device date and time when the tag was read (formatted as `yyyy-MM-dd HH:mm:ss`).

## Troubleshooting

*   **Server Updates Not Appearing:**
    *   Verify your device's internet connectivity.
    *   Check Logcat (filter by `NLSNFC`) for `POST http_error` or `POST exception` messages to diagnose network or server-side issues.
    *   Confirm the `DEV_SN` source in the logs (Newland sysfs vs. `ANDROID_ID`) to ensure correct device identification.
*   **NFC Reading Issues:**
    *   Ensure NFC is enabled in device settings.
    *   Check Logcat for any `ERROR` messages related to NFC.
    *   For persistent issues, especially on specific OEM devices, consider the implications of the "Ghost" Error Mitigation and reflective calls.

## Branching Note

This repository's active development branch is `work-branch`.

- Do all work on `work-branch`.
- Do not switch the default branch to `main`.
- Open pull requests against `work-branch` only.

## Manual GitHub Push Checklist

Use this quick checklist before pushing your changes to GitHub manually:

1) Build and test locally

- From Android Studio: Build > Make Project
- Or via CLI:
  ```sh
  ./gradlew clean build
  ```

2) Verify branch

- Ensure you are on the correct branch:
  ```sh
  git status
  git branch --show-current  # should print: work-branch
  ```

3) Review changes and stage

```sh
git add -A
git status
git diff --staged   # optional: review staged changes
```

4) Commit with a clear message

```sh
git commit -m "Cleanups: remove unused imports; docs/readme improvements; prepare for push"
```

5) Push to origin

```sh
git push origin work-branch
```

Notes:

- Sensitive/local files are ignored by .gitignore (e.g., `local.properties`, keystores, build artifacts). If any such file appears in `git status`, ensure `.gitignore` is correct and untrack it if previously committed:
  ```sh
  git rm -r --cached path/to/file-or-folder
  ```
- Keep the branch as `work-branch`; do not rename or switch to `main`.

## License

MIT License

Copyright (c) 2025 Luis E. Orellana

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including, without limitation, the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.