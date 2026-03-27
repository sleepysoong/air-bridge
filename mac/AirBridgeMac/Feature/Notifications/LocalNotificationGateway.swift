import Foundation
import UniformTypeIdentifiers
import UserNotifications

final class LocalNotificationGateway: Sendable {
    init() {}

    func currentAuthorizationStatus() async -> UNAuthorizationStatus {
        await withCheckedContinuation { continuation in
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                continuation.resume(returning: settings.authorizationStatus)
            }
        }
    }

    func requestAuthorization() async throws -> Bool {
        try await withCheckedThrowingContinuation { continuation in
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                continuation.resume(returning: granted)
            }
        }
    }

    @MainActor
    func apply(_ payload: NotificationPayload) async throws {
        let notificationCenter = UNUserNotificationCenter.current()
        let identifier = "airbridge.notification.\(payload.remoteIdentifier)"

        switch payload.event {
        case .removed:
            notificationCenter.removeDeliveredNotifications(withIdentifiers: [identifier])
            notificationCenter.removePendingNotificationRequests(withIdentifiers: [identifier])
        case .posted, .updated:
            notificationCenter.removeDeliveredNotifications(withIdentifiers: [identifier])
            notificationCenter.removePendingNotificationRequests(withIdentifiers: [identifier])

            let content = UNMutableNotificationContent()
            let resolvedTitle = payload.title?.trimmingCharacters(in: .whitespacesAndNewlines)
            content.title = (resolvedTitle?.isEmpty == false) ? resolvedTitle! : payload.appName

            let subtitleComponents = [
                payload.appName.trimmingCharacters(in: .whitespacesAndNewlines),
                payload.subtitle?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            ].filter { !$0.isEmpty }
            if !subtitleComponents.isEmpty {
                content.subtitle = subtitleComponents.joined(separator: " · ")
            }

            let resolvedBody = payload.body?.trimmingCharacters(in: .whitespacesAndNewlines)
            content.body = (resolvedBody?.isEmpty == false) ? resolvedBody! : "새 알림을 받아왔어요."
            content.userInfo = [
                "remote_identifier": payload.remoteIdentifier,
                "app_name": payload.appName,
                "package_name": payload.packageName,
            ]
            content.attachments = try buildAttachments(for: payload, identifier: identifier)

            let request = UNNotificationRequest(
                identifier: identifier,
                content: content,
                trigger: nil
            )
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                notificationCenter.add(request) { error in
                    if let error {
                        continuation.resume(throwing: error)
                        return
                    }
                    continuation.resume(returning: ())
                }
            }
        }
    }

    private func buildAttachments(for payload: NotificationPayload, identifier: String) throws -> [UNNotificationAttachment] {
        guard let assets = payload.assets else {
            return []
        }

        let candidates = [
            ("hero", assets.heroImage),
            ("large", assets.largeIcon),
            ("app", assets.appIcon),
        ]

        var attachments: [UNNotificationAttachment] = []
        for (kind, asset) in candidates {
            guard let asset,
                  let attachment = try writeAttachment(for: asset, identifier: identifier, kind: kind) else {
                continue
            }
            attachments.append(attachment)
            if attachments.count >= 2 {
                break
            }
        }

        return attachments
    }

    private func writeAttachment(
        for asset: NotificationImagePayload,
        identifier: String,
        kind: String
    ) throws -> UNNotificationAttachment? {
        let data = try Data(rawBase64Encoded: asset.dataBase64)
        let fileExtension = fileExtension(for: asset.mimeType)
        let directory = try attachmentDirectory()
        let fileURL = directory.appendingPathComponent("\(identifier).\(kind).\(fileExtension)")

        if FileManager.default.fileExists(atPath: fileURL.path) {
            try FileManager.default.removeItem(at: fileURL)
        }
        try data.write(to: fileURL, options: .atomic)

        let attachmentOptions: [AnyHashable: Any]
        if let type = uniformTypeIdentifier(for: asset.mimeType) {
            attachmentOptions = [UNNotificationAttachmentOptionsTypeHintKey: type.identifier]
        } else {
            attachmentOptions = [:]
        }

        return try UNNotificationAttachment(identifier: "\(identifier).\(kind)", url: fileURL, options: attachmentOptions)
    }

    private func attachmentDirectory() throws -> URL {
        let baseDirectory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let directory = baseDirectory.appendingPathComponent("AirBridgeNotifications", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func fileExtension(for mimeType: String) -> String {
        switch mimeType {
        case "image/png":
            return "png"
        case "image/jpeg":
            return "jpg"
        default:
            return "bin"
        }
    }

    private func uniformTypeIdentifier(for mimeType: String) -> UTType? {
        switch mimeType {
        case "image/png":
            return .png
        case "image/jpeg":
            return .jpeg
        default:
            return nil
        }
    }
}
