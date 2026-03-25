import Foundation

enum NotificationEventType: String, Codable {
    case posted
    case updated
    case removed
}

struct NotificationPayload: Codable, Equatable {
    let remoteIdentifier: String
    let event: NotificationEventType
    let appName: String
    let title: String
    let subtitle: String?
    let body: String
    let sentAt: Date

    enum CodingKeys: String, CodingKey {
        case remoteIdentifier = "remote_identifier"
        case event
        case appName = "app_name"
        case title
        case subtitle
        case body
        case sentAt = "sent_at"
    }
}
