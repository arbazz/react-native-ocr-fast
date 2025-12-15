import React, { useState, useRef, useEffect } from 'react'
import { View, Text, TouchableOpacity, Image, StyleSheet } from 'react-native'
import { Camera, useCameraDevice } from 'react-native-vision-camera'
import { HybridOcr } from 'react-native-ocr'

const SCAN_REGION_PCT = {
    left: 10,
    top: 30,
    width: 80,
    height: 20,
}

export default function CroppedCamera() {
    const device = useCameraDevice('back')
    const camera = useRef<Camera>(null)
    const [capturedImage, setCapturedImage] = useState<string | null>(null)
    const [isProcessing, setIsProcessing] = useState(false)
    const captureTimeoutRef = useRef<NodeJS.Timeout | null>(null)

    // Calculate normalized values (0-1) for native module
    const scanRegionNormalized = {
        x: SCAN_REGION_PCT.left / 100,
        y: SCAN_REGION_PCT.top / 100,
        width: SCAN_REGION_PCT.width / 100,
        height: SCAN_REGION_PCT.height / 100
    }

    // Handle the capture flow
    const handleCapture = async () => {
        if (isProcessing || !camera.current) return

        setIsProcessing(true)
        try {
            const photo = await camera.current.takePhoto()

            // Perform OCR on cropped region
            // Note: We assume native module handles the cropping
            const result = await HybridOcr.scanImageWithRegion(
                photo.path,
                scanRegionNormalized.x,
                scanRegionNormalized.y,
                scanRegionNormalized.width,
                scanRegionNormalized.height
            )

            // Depending on implementation, result might be JSON or simple text
            // If it returns path to cropped image, we can display it
            // For now, let's try to parse if it's JSON as seen in Camera.tsx
            try {
                const parsed = JSON.parse(result)
                if (parsed.croppedImagePath) {
                    // Since we don't have direct base64 of cropped image yet unless we read the file
                    // We will use the file path
                    setCapturedImage(parsed.croppedImagePath)
                } else if (parsed.text) {
                    // If no image path, just show the full image but maybe we want to show success
                    console.log("OCT Text:", parsed.text)
                    // For UI feedback we might just show "Captured" standard logic
                }
            } catch (e) {
                console.log("OCR Result (text):", result)
                // Fallback to showing the full photo if parsing fails or its just text
                setCapturedImage(photo.path)
            }

        } catch (e) {
            console.error(e)
        }

        // Reset logic - 3 second delay to show the result
        if (captureTimeoutRef.current) clearTimeout(captureTimeoutRef.current)
        captureTimeoutRef.current = setTimeout(() => {
            setCapturedImage(null)
            setIsProcessing(false)
        }, 3000)
    }

    if (!device) return null

    return (
        <View style={{ flex: 1, backgroundColor: 'black' }}>
            <Camera
                ref={camera}
                style={StyleSheet.absoluteFill}
                device={device}
                isActive={true}
                photo={true}
                pixelFormat='yuv'
            />

            {/* Static Overlay */}
            <View style={styles.overlay}>
                <View style={[
                    styles.cropRegion,
                    {
                        left: `${SCAN_REGION_PCT.left}%`,
                        top: `${SCAN_REGION_PCT.top}%`,
                        width: `${SCAN_REGION_PCT.width}%`,
                        height: `${SCAN_REGION_PCT.height}%`
                    }
                ]} />
            </View>

            {capturedImage && (
                <View style={styles.capturedContainer}>
                    <Image
                        source={{ uri: capturedImage.startsWith('file://') ? capturedImage : `file://${capturedImage}` }}
                        style={styles.preview}
                    />
                    <Text style={styles.capturedText}>Image Captured!</Text>
                </View>
            )}

            <TouchableOpacity
                style={[styles.captureButton, isProcessing && styles.captureButtonDisabled]}
                onPress={handleCapture}
                disabled={isProcessing}
            >
                <Text style={{ color: 'white' }}>
                    {isProcessing ? 'PROCESSING...' : 'CAPTURE'}
                </Text>
            </TouchableOpacity>
        </View>
    )
}

const styles = StyleSheet.create({
    overlay: {
        ...StyleSheet.absoluteFillObject,
        // We can add semi-transparent background logic if needed, 
        // but simplest is just the border box
    },
    cropRegion: {
        position: 'absolute',
        borderColor: 'red',
        borderWidth: 2,
    },
    preview: {
        width: '100%',
        height: '75%',
        resizeMode: 'contain',
        marginTop: 40,
    },
    capturedContainer: {
        ...StyleSheet.absoluteFillObject,
        backgroundColor: 'rgba(0,0,0,0.8)',
        justifyContent: 'center',
        alignItems: 'center',
        zIndex: 10
    },
    capturedText: {
        color: 'white',
        fontSize: 18,
        fontWeight: 'bold',
        marginTop: 20,
        position: 'absolute',
        top: 50,
    },
    captureButton: {
        position: 'absolute',
        bottom: 40,
        alignSelf: 'center',
        backgroundColor: '#111',
        paddingVertical: 14,
        paddingHorizontal: 30,
        borderRadius: 10,
        zIndex: 20
    },
    captureButtonDisabled: {
        backgroundColor: '#444',
        opacity: 0.6,
    },
})