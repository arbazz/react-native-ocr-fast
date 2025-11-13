
-----

# 📄 `react-native-ocr-fast` Usage Documentation

This documentation provides instructions for setting up and using the Optical Character Recognition (OCR) functionality shown in the accompanying React Native component.

## 🚀 Installation

To use the OCR functionality, you need to install the core package and its native dependencies, including `react-native-vision-camera`, which is used for camera access.

### 1\. Install NPM Packages

Install the necessary dependencies in your React Native project:

```bash
npm install react-native-ocr-fast 
# or
yarn add react-native-ocr-fast 
```

### 2\. Install Peer Dependencies

The `react-native-ocr-fast` library may depend on a core native module that needs to be linked. You should install `react-native-nitro-modules` if required by the OCR package:

```bash
npm install react-native-nitro-modules
# or
yarn add react-native-nitro-modules
```

### 3\. iOS Setup (using CocoaPods)

Navigate to your `ios` directory and install the pods. This step is crucial for linking the native camera and OCR modules.

```bash
cd ios
pod install
cd ..
```

### 4\. Android Permissions

Ensure you have the necessary camera permission in your `AndroidManifest.xml` (usually located at `android/app/src/main/AndroidManifest.xml`):

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
```

## 💻 Example Usage Component

The following code demonstrates how to integrate the camera, capture an image, and run OCR on a specific region of the image using the `HybridOcr.scanImageWithRegion` method from `react-native-ocr-fast`.

### `CameraView.tsx`

```tsx
import React, { useEffect, useRef, useState } from 'react'
import { StyleSheet, Text, View, TouchableOpacity, ScrollView } from 'react-native'
import {
    Camera,
    useCameraDevice,
} from 'react-native-vision-camera'
// Use the correct package name: 'react-native-ocr-fast'
import { HybridOcr } from 'react-native-ocr-fast' 

export default function CameraView() {
    const camera = useRef<Camera>(null)
    const device = useCameraDevice('back') // Select the back camera
    const [ocrText, setOcrText] = useState('')
    const [isScanning, setIsScanning] = useState(false)

    // Request Camera Permission on component mount
    useEffect(() => {
        (async () => {
            const status = await Camera.requestCameraPermission()
            if (status !== 'granted') console.warn('Camera permission not granted')
        })()
    }, [])

    /**
     * Captures a photo and runs OCR on a defined region.
     */
    const captureAndScan = async () => {
        if (!camera.current) return

        setIsScanning(true)
        try {
            // 1. Take a photo using react-native-vision-camera
            const photo = await camera.current.takePhoto({
                qualityPrioritization: 'speed', // Optimize for speed
                flash: 'off',
            })
            
            // 2. Define the focus region (normalized coordinates 0-1)
            // This region is overlaid on the screen with the focusFrame style.
            const focusRegion = {
                x: 0.1,     // 10% from left
                y: 0.3,     // 30% from top
                width: 0.8, // 80% of width
                height: 0.4  // 40% of height
            }

            // 3. Run OCR on the photo path with the defined focus region
            const results = await HybridOcr.scanImageWithRegion(
                photo.path,
                focusRegion.x,
                focusRegion.y,
                focusRegion.width,
                focusRegion.height
            )

            setOcrText(results || 'No text detected')
        } catch (error: any) {
            console.error('OCR Error:', error)
            setOcrText('Error: ' + error.message)
        } finally {
            setIsScanning(false)
        }
    }

    if (!device) return <Text style={{ color: 'white' }}>No camera device found</Text>

    return (
        <View style={styles.container}>
            {/* Camera Preview */}
            <Camera
                ref={camera}
                style={styles.preview}
                device={device}
                isActive={true}
                photo={true} // Enable photo capturing
            />
            
            {/* Focus Frame Overlay (Visual Guide) */}
            <View style={styles.focusFrame}>
                <View style={[styles.corner, styles.topLeft]} />
                <View style={[styles.corner, styles.topRight]} />
                <View style={[styles.corner, styles.bottomLeft]} />
                <View style={[styles.corner, styles.bottomRight]} />
                <Text style={styles.focusText}>Position text here</Text>
            </View>

            {/* Capture Button */}
            <TouchableOpacity
                style={[styles.captureButton, isScanning && styles.captureButtonDisabled]}
                onPress={captureAndScan}
                disabled={isScanning}
            >
                <Text style={styles.captureText}>
                    {isScanning ? 'Scanning...' : 'Scan Text'}
                </Text>
            </TouchableOpacity>

            {/* OCR Result Display */}
            {ocrText ? (
                <ScrollView style={styles.resultBox}>
                    <Text style={styles.resultText}>{ocrText}</Text>
                </ScrollView>
            ) : null}
        </View>
    )
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: 'black',
    },
    preview: {
        flex: 1,
    },
    focusFrame: {
        position: 'absolute',
        left: '10%',
        top: '30%',
        width: '80%',
        height: '40%',
        borderWidth: 2,
        borderColor: 'rgba(255, 255, 255, 0.8)',
        borderRadius: 12,
        justifyContent: 'center',
        alignItems: 'center',
    },
    corner: {
        position: 'absolute',
        width: 30,
        height: 30,
        borderColor: '#00ff00',
        borderWidth: 4,
    },
    topLeft: {
        top: -2,
        left: -2,
        borderRightWidth: 0,
        borderBottomWidth: 0,
        borderTopLeftRadius: 12,
    },
    topRight: {
        top: -2,
        right: -2,
        borderLeftWidth: 0,
        borderBottomWidth: 0,
        borderTopRightRadius: 12,
    },
    bottomLeft: {
        bottom: -2,
        left: -2,
        borderRightWidth: 0,
        borderTopWidth: 0,
        borderBottomLeftRadius: 12,
    },
    bottomRight: {
        bottom: -2,
        right: -2,
        borderLeftWidth: 0,
        borderTopWidth: 0,
        borderBottomRightRadius: 12,
    },
    focusText: {
        color: 'white',
        fontSize: 16,
        backgroundColor: 'rgba(0, 0, 0, 0.5)',
        paddingHorizontal: 12,
        paddingVertical: 6,
        borderRadius: 6,
    },
    captureButton: {
        position: 'absolute',
        bottom: 40,
        alignSelf: 'center',
        backgroundColor: 'white',
        paddingHorizontal: 30,
        paddingVertical: 15,
        borderRadius: 30,
    },
    captureButtonDisabled: {
        backgroundColor: '#cccccc',
    },
    captureText: {
        color: 'black',
        fontSize: 18,
        fontWeight: 'bold',
    },
    resultBox: {
        position: 'absolute',
        top: 100,
        left: 20,
        right: 20,
        maxHeight: 200,
        backgroundColor: 'rgba(0,0,0,0.8)',
        padding: 15,
        borderRadius: 10,
    },
    resultText: {
        color: 'white',
        fontSize: 16,
        lineHeight: 24,
    },
});
```

-----

## ⚙️ Key Concepts & API

### 1\. Camera Setup

  * **`useCameraDevice('back')`**: Hook from `react-native-vision-camera` to select the device's back camera.
  * **`<Camera ... photo={true} />`**: The component that displays the camera feed and is configured to allow photo capture.
  * **`Camera.requestCameraPermission()`**: **Must** be called to request access to the device's camera.

### 2\. OCR Function: `captureAndScan`

This asynchronous function orchestrates the image capture and text recognition process.

  * **`await camera.current.takePhoto()`**: Captures a high-resolution photo. The result (`photo`) object contains the **`photo.path`**, which is the local file path needed for the OCR engine.

  * **`focusRegion`**: Defines the area of the image to be analyzed for text.

    ```typescript
    const focusRegion = {
        x: 0.1,     // 10% from the left edge of the image
        y: 0.3,     // 30% from the top edge of the image
        width: 0.8, // 80% of the image width
        height: 0.4  // 40% of the image height
    }
    ```

    These values are **normalized coordinates** (ranging from 0 to 1), where $(0, 0)$ is the top-left corner and $(1, 1)$ is the bottom-right corner of the captured image.

  * **`HybridOcr.scanImageWithRegion(path, x, y, width, height)`**

    This is the core OCR function from `react-native-ocr-fast`.

      * **`path`**: The local file path of the captured image (`photo.path`).
      * **`x, y, width, height`**: The normalized coordinates defining the specific region of interest for OCR. This significantly speeds up the process and improves accuracy compared to scanning the entire image.

### 3\. Focus Frame Overlay

The `styles.focusFrame` and its inner `styles.corner` components create a visual rectangular guide on top of the camera feed. This helps the user position the text they want to scan precisely within the region defined by the `focusRegion` normalized coordinates.