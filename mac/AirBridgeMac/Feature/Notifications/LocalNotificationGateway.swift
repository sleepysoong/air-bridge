import Foundation
import UserNotifications

final class LocalNotificationGateway {
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
            UNUserNotificationCenter.current().requestAuthorization(options: [.badge, .sound]) { granted, error in
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
}
