import React, { useEffect, useRef, useState } from 'react'
import { StyleSheet, Text, View, TouchableOpacity, ScrollView } from 'react-native'
import {
    Camera,
    useCameraDevice,
} from 'react-native-vision-camera'
import { HybridOcr } from 'react-native-ocr'

export default function CameraView() {
    const camera = useRef<Camera>(null)
    const device = useCameraDevice('back')
    const [ocrText, setOcrText] = useState('')
    const [isScanning, setIsScanning] = useState(false)

    useEffect(() => {
        (async () => {
            const status = await Camera.requestCameraPermission()
            if (status !== 'granted') console.warn('Camera permission not granted')
        })()
    }, [])

    const captureAndScan = async () => {
        if (!camera.current) return

        setIsScanning(true)
        try {
            // Take a photo
            const photo = await camera.current.takePhoto()

            console.log('Photo captured:', photo.path)

            // Define the focus region (normalized coordinates 0-1)
            // This matches the overlay box on screen
            const focusRegion = {
                x: 0.1,      // 10% from left
                y: 0.3,      // 30% from top
                width: 0.8,  // 80% of width
                height: 0.4  // 40% of height
            }

            // Run OCR on the photo with focus region
            const results = await HybridOcr.scanImageWithRegion(
                photo.path,
                focusRegion.x,
                focusRegion.y,
                focusRegion.width,
                focusRegion.height
            )

            console.log('OCR Results:', results)
            setOcrText(results || 'No text detected')

        } catch (error: any) {
            console.error('Error:', error)
            setOcrText('Error: ' + error.message)
        } finally {
            setIsScanning(false)
        }
    }

    if (!device) return null

    return (
        <View style={styles.container}>
            <Camera
                ref={camera}
                style={styles.preview}
                device={device}
                isActive={true}
                photo={true}
            />

            {/* Focus Frame Overlay */}
            <View style={styles.focusFrame}>
                <View style={[styles.corner, styles.topLeft]} />
                <View style={[styles.corner, styles.topRight]} />
                <View style={[styles.corner, styles.bottomLeft]} />
                <View style={[styles.corner, styles.bottomRight]} />
                <Text style={styles.focusText}>Position text here</Text>
            </View>

            <TouchableOpacity
                style={[styles.captureButton, isScanning && styles.captureButtonDisabled]}
                onPress={captureAndScan}
                disabled={isScanning}
            >
                <Text style={styles.captureText}>
                    {isScanning ? 'Scanning...' : 'Scan Text'}
                </Text>
            </TouchableOpacity>

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
})