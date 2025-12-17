import Foundation
import NitroModules
@preconcurrency import Vision
import UIKit
import CoreImage
import Accelerate

class HybridOcr: HybridOcrSpec {

    func scan(input: String) throws -> String {
        return input
    }

    func scanFrame(frame: Frame) throws -> Promise<String> {
        return Promise.async {
            return try await self.recognizeText(frame)
        }
    }

    func scanImage(path: String) throws -> Promise<String> {
        return Promise.async {
            return try await self.recognizeTextFromImage(path, region: nil)
        }
    }

    func scanImageWithRegion(path: String, x: Double, y: Double, width: Double, height: Double, digitsOnly: Bool?, contrast: Double?) throws -> Promise<String> {
        return Promise.async {
            let region = CGRect(x: x, y: y, width: width, height: height)
            return try await self.recognizeTextFromImage(path, region: region, digitsOnly: digitsOnly ?? false, contrast: contrast ?? 1.0)
        }
    }

    public func recognizeText(_ frame: Frame) async throws -> String {
        guard frame.isValid else {
            throw NSError(domain: "HybridOcr", code: 0, userInfo: [NSLocalizedDescriptionKey: "Invalid frame"])
        }

        let nativeBuffer = try await frame.getNativeBuffer().await()
        let pixelBufferPtr = UnsafeMutableRawPointer(bitPattern: UInt(nativeBuffer.pointer))
        guard let pixelBuffer = pixelBufferPtr?.assumingMemoryBound(to: CVPixelBuffer.self).pointee else {
            throw NSError(domain: "HybridOcr", code: 0, userInfo: [NSLocalizedDescriptionKey: "Invalid CVPixelBuffer pointer"])
        }

        return try await performOCR(on: pixelBuffer, region: nil, digitsOnly: false)
    }

    private func recognizeTextFromImage(_ path: String, region: CGRect?, digitsOnly: Bool = false, contrast: Double = 1.0) async throws -> String {
        var cleanPath = path
        if cleanPath.hasPrefix("file://") {
            cleanPath = String(cleanPath.dropFirst(7))
        }
        
        guard let image = UIImage(contentsOfFile: cleanPath) else {
            throw NSError(domain: "HybridOcr", code: 1, userInfo: [NSLocalizedDescriptionKey: "Could not load image from path: \(cleanPath)"])
        }
        
        // Normalize orientation
        let fixedImage = normalizeImageOrientation(image)
        
        guard let cgImage = fixedImage.cgImage else {
            throw NSError(domain: "HybridOcr", code: 2, userInfo: [NSLocalizedDescriptionKey: "Could not get CGImage from UIImage"])
        }

        var imageToProcess = cgImage
        var croppedImagePath: String? = nil
        
        print("HybridOcr: Original Image: \(cgImage.width)x\(cgImage.height)")
        
        // Step 1: Crop if region specified
        if let region = region {
            print("HybridOcr: Region Normalized: x=\(region.minX), y=\(region.minY), w=\(region.width), h=\(region.height)")
            
            let width = Double(cgImage.width)
            let height = Double(cgImage.height)
            let cropRect = CGRect(
                x: region.minX * width,
                y: region.minY * height,
                width: region.width * width,
                height: region.height * height
            )
            
            print("HybridOcr: Calculated Crop: \(cropRect)")
            
            if let cropped = cgImage.cropping(to: cropRect) {
                imageToProcess = cropped
            } else {
                print("HybridOcr: Cropping failed!")
            }
        }
        
        // Step 2: Upscale if needed (critical for small regions)
        imageToProcess = upscaleIfNeeded(imageToProcess)
        
        // Step 3: Apply preprocessing for better OCR
        imageToProcess = preprocessForOCR(imageToProcess, contrast: contrast, digitsOnly: digitsOnly)
        
        // Step 4: Save processed image for debug/UI
        if region != nil || contrast != 1.0 {
            croppedImagePath = saveProcessedImage(imageToProcess)
        }

        let ocrText = try await performOCR(on: imageToProcess, region: nil, digitsOnly: digitsOnly)
        
        // Return results as JSON
        if croppedImagePath != nil || region != nil {
            let result: [String: Any] = [
                "text": ocrText,
                "croppedImagePath": croppedImagePath ?? ""
            ]
            
            let jsonData = try JSONSerialization.data(withJSONObject: result, options: [])
            return String(data: jsonData, encoding: .utf8) ?? ocrText
        }
        
        return ocrText
    }
    
    private func normalizeImageOrientation(_ image: UIImage) -> UIImage {
        if image.imageOrientation == .up {
            return image
        }
        
        UIGraphicsBeginImageContextWithOptions(image.size, false, image.scale)
        image.draw(in: CGRect(origin: .zero, size: image.size))
        let normalizedImage = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        
        return normalizedImage ?? image
    }
    
    private func upscaleIfNeeded(_ cgImage: CGImage) -> CGImage {
        // Vision framework works best with images at least 640px on smallest side
        let minDimension: CGFloat = 640
        let currentMin = min(cgImage.width, cgImage.height)
        
        if currentMin < Int(minDimension) {
            let scale = minDimension / CGFloat(currentMin)
            let newWidth = Int(CGFloat(cgImage.width) * scale)
            let newHeight = Int(CGFloat(cgImage.height) * scale)
            
            print("HybridOcr: Upscaling from \(cgImage.width)x\(cgImage.height) to \(newWidth)x\(newHeight)")
            
            let colorSpace = CGColorSpaceCreateDeviceRGB()
            guard let context = CGContext(
                data: nil,
                width: newWidth,
                height: newHeight,
                bitsPerComponent: 8,
                bytesPerRow: 0,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else {
                return cgImage
            }
            
            context.interpolationQuality = .high
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: newWidth, height: newHeight))
            
            return context.makeImage() ?? cgImage
        }
        
        return cgImage
    }
    
    private func preprocessForOCR(_ cgImage: CGImage, contrast: Double, digitsOnly: Bool) -> CGImage {
        let ciImage = CIImage(cgImage: cgImage)
        let context = CIContext(options: [.useSoftwareRenderer: false])
        
        var processedImage = ciImage
        
        // 1. Sharpen the image for better character recognition
        if let sharpenFilter = CIFilter(name: "CISharpenLuminance") {
            sharpenFilter.setValue(processedImage, forKey: kCIInputImageKey)
            sharpenFilter.setValue(digitsOnly ? 0.8 : 0.5, forKey: kCIInputSharpnessKey) // More aggressive for digits
            if let output = sharpenFilter.outputImage {
                processedImage = output
            }
        }
        
        // 2. Adjust contrast (more aggressive for digits)
        let contrastValue = digitsOnly ? min(contrast * 1.4, 2.5) : min(contrast * 1.2, 2.0)
        
        if let colorControlsFilter = CIFilter(name: "CIColorControls") {
            colorControlsFilter.setValue(processedImage, forKey: kCIInputImageKey)
            colorControlsFilter.setValue(contrastValue, forKey: kCIInputContrastKey)
            
            // Increase brightness slightly for better visibility
            let brightnessAdjust = digitsOnly ? 0.08 : 0.05
            colorControlsFilter.setValue(brightnessAdjust, forKey: kCIInputBrightnessKey)
            
            // Reduce saturation for cleaner text detection
            colorControlsFilter.setValue(0.8, forKey: kCIInputSaturationKey)
            
            if let output = colorControlsFilter.outputImage {
                processedImage = output
            }
        }
        
        // 3. Apply tone curve for better text clarity (optional but effective)
        if digitsOnly, let toneCurveFilter = CIFilter(name: "CIToneCurve") {
            toneCurveFilter.setValue(processedImage, forKey: kCIInputImageKey)
            // Enhance midtones
            toneCurveFilter.setValue(CIVector(x: 0.0, y: 0.0), forKey: "inputPoint0")
            toneCurveFilter.setValue(CIVector(x: 0.25, y: 0.20), forKey: "inputPoint1")
            toneCurveFilter.setValue(CIVector(x: 0.5, y: 0.5), forKey: "inputPoint2")
            toneCurveFilter.setValue(CIVector(x: 0.75, y: 0.80), forKey: "inputPoint3")
            toneCurveFilter.setValue(CIVector(x: 1.0, y: 1.0), forKey: "inputPoint4")
            
            if let output = toneCurveFilter.outputImage {
                processedImage = output
            }
        }
        
        // 4. Apply unsharp mask for final enhancement
        if let unsharpFilter = CIFilter(name: "CIUnsharpMask") {
            unsharpFilter.setValue(processedImage, forKey: kCIInputImageKey)
            unsharpFilter.setValue(2.5, forKey: kCIInputRadiusKey)
            unsharpFilter.setValue(digitsOnly ? 1.0 : 0.7, forKey: kCIInputIntensityKey)
            if let output = unsharpFilter.outputImage {
                processedImage = output
            }
        }
        
        print("HybridOcr: Applied preprocessing with contrast=\(contrastValue), digitsOnly=\(digitsOnly)")
        
        // Convert back to CGImage
        if let outputCGImage = context.createCGImage(processedImage, from: processedImage.extent) {
            return outputCGImage
        }
        
        return cgImage
    }
    
    private func saveProcessedImage(_ cgImage: CGImage) -> String? {
        let tempDir = FileManager.default.temporaryDirectory
        let fileName = "processed_\(Date().timeIntervalSince1970).jpg"
        let fileURL = tempDir.appendingPathComponent(fileName)
        
        let uiImage = UIImage(cgImage: cgImage)
        if let data = uiImage.jpegData(compressionQuality: 0.95) {
            try? data.write(to: fileURL)
            print("HybridOcr: Saved processed image to: \(fileURL.absoluteString)")
            return fileURL.absoluteString
        }
        
        return nil
    }

    private func performOCR(on pixelBuffer: CVPixelBuffer, region: CGRect?, digitsOnly: Bool) async throws -> String {
        return try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error = error {
                    continuation.resume(throwing: error)
                    return
                }
                if let results = request.results as? [VNRecognizedTextObservation] {
                    var filteredResults = results
                    
                    // Filter results by region if specified
                    if let region = region {
                        filteredResults = results.filter { observation in
                            let bbox = observation.boundingBox
                            return region.intersects(bbox)
                        }
                    }
                    
                    // Get top candidates and sort by position (top to bottom)
                    let sortedResults = filteredResults.sorted { obs1, obs2 in
                        // Sort by Y coordinate (top to bottom in Vision coordinates)
                        obs1.boundingBox.origin.y > obs2.boundingBox.origin.y
                    }
                    
                    var recognizedText = sortedResults
                        .compactMap { $0.topCandidates(1).first?.string }
                        .joined(separator: "\n")
                    
                    print("HybridOcr: Raw OCR result: \(recognizedText)")
                    
                    if digitsOnly {
                        recognizedText = recognizedText.filter { 
                            $0.isNumber || $0 == "\n" || $0 == "." || $0 == "," || $0 == "-"
                        }
                    }
                    
                    continuation.resume(returning: recognizedText)
                } else {
                    continuation.resume(returning: "")
                }
            }
            
            // Configure for best accuracy
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = !digitsOnly
            request.revision = VNRecognizeTextRequestRevision3 // Use latest revision
            
            // For digits, customize recognition
            if digitsOnly {
                request.recognitionLanguages = ["en-US"]
                request.customWords = [] // Don't use dictionary for numbers
            }
            
            // Set region of interest if specified
            if let region = region {
                request.regionOfInterest = region
            }

            let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try handler.perform([request])
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private func performOCR(on cgImage: CGImage, region: CGRect?, digitsOnly: Bool) async throws -> String {
        return try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error = error {
                    continuation.resume(throwing: error)
                    return
                }
                if let results = request.results as? [VNRecognizedTextObservation] {
                    var filteredResults = results
                    
                    // Filter results by region if specified
                    if let region = region {
                        filteredResults = results.filter { observation in
                            let bbox = observation.boundingBox
                            return region.intersects(bbox)
                        }
                    }
                    
                    // Sort by position (top to bottom)
                    let sortedResults = filteredResults.sorted { obs1, obs2 in
                        obs1.boundingBox.origin.y > obs2.boundingBox.origin.y
                    }
                    
                    var recognizedText = sortedResults
                        .compactMap { $0.topCandidates(1).first?.string }
                        .joined(separator: "\n")
                    
                    print("HybridOcr: Raw OCR result: \(recognizedText)")
                    
                    if digitsOnly {
                        recognizedText = recognizedText.filter { 
                            $0.isNumber || $0 == "\n" || $0 == "." || $0 == "," || $0 == "-"
                        }
                    }
                    
                    continuation.resume(returning: recognizedText)
                } else {
                    continuation.resume(returning: "")
                }
            }
            
            // Configure for best accuracy
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = !digitsOnly
            request.revision = VNRecognizeTextRequestRevision3
            
            // For digits, optimize settings
            if digitsOnly {
                request.recognitionLanguages = ["en-US"]
                request.customWords = []
            }
            
            // Set region of interest if specified
            if let region = region {
                request.regionOfInterest = region
            }

            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try handler.perform([request])
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }
}