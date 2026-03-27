import Foundation
import UserNotifications

final class LocalNotificationGateway {
    private let center: UNUserNotificationCenter

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    func currentAuthorizationStatus() async -> UNAuthorizationStatus {
        await withCheckedContinuation { continuation in
            center.getNotificationSettings { settings in
                continuation.resume(returning: settings.authorizationStatus)
            }
        }
    }

    func requestAuthorization() async throws -> Bool {
        try await center.requestAuthorization(options: [.badge, .sound])
    }

    func apply(_ payload: NotificationPayload) async throws {
        let identifier = "airbridge.notification.\(payload.remoteIdentifier)"

        switch payload.event {
        case .removed:
            center.removeDeliveredNotifications(withIdentifiers: [identifier])
            center.removePendingNotificationRequests(withIdentifiers: [identifier])
        case .posted, .updated:
            center.removeDeliveredNotifications(withIdentifiers: [identifier])
            center.removePendingNotificationRequests(withIdentifiers: [identifier])

            let content = UNMutableNotificationContent()
            content.title = payload.title.isEmpty ? payload.appName : payload.title
            if let subtitle = payload.subtitle, !subtitle.isEmpty {
                content.subtitle = subtitle
            }
            content.body = payload.body
            content.userInfo = [
                "remote_identifier": payload.remoteIdentifier,
                "app_name": payload.appName,
            ]

            let request = UNNotificationRequest(
                identifier: identifier,
                content: content,
                trigger: nil
            )
            try await center.add(request)
        }
    }
}
