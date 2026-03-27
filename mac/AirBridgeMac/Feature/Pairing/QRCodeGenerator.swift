import AppKit
import CoreImage.CIFilterBuiltins
import Foundation

enum QRCodeGeneratorError: LocalizedError {
    case generationFailed

    var errorDescription: String? {
        switch self {
        case .generationFailed:
            return "페어링 QR 코드 생성에 실패했어요."
        }
    }
}

final class QRCodeGenerator {
    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()

    func makeImage(from payload: String, size: CGSize) throws -> NSImage {
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else {
            throw QRCodeGeneratorError.generationFailed
        }

        let scaleX = size.width / outputImage.extent.width
        let scaleY = size.height / outputImage.extent.height
        let transformed = outputImage.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))
        guard let cgImage = context.createCGImage(transformed, from: transformed.extent) else {
            throw QRCodeGeneratorError.generationFailed
        }

        let rep = NSBitmapImageRep(cgImage: cgImage)
        let image = NSImage(size: NSSize(width: size.width, height: size.height))
        image.addRepresentation(rep)
        return image
    }
}
