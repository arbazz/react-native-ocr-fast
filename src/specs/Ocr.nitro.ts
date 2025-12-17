import type { HybridObject } from 'react-native-nitro-modules'


export interface NativeBuffer {
    pointer: bigint
}

export type Orientation = 'portrait' | 'portrait-upside-down' | 'landscape-left' | 'landscape-right'

export type PixelFormat =
    | 'yuv'
    | 'rgb'
    | 'bgra'
    | 'unknown'

export interface Frame {
    readonly isValid: boolean
    readonly width: number
    readonly height: number
    readonly bytesPerRow: number
    readonly planesCount: number
    readonly isMirrored: boolean
    readonly timestamp: number
    readonly orientation: Orientation
    readonly pixelFormat: PixelFormat
    toArrayBuffer(): ArrayBuffer
    toString(): string
    getNativeBuffer(): NativeBuffer
}
export interface Ocr extends HybridObject<{
    ios: 'swift'
    android: 'kotlin'
}> {
    scan(input: string): string
    scanFrame(frame: Frame): Promise<string>
    scanImage(path: string): Promise<string>
    scanImageWithRegion(path: string, x: number, y: number, width: number, height: number, digitsOnly?: boolean, contrast?: number): Promise<string>
}