import Foundation

enum NotificationEventType: String, Codable {
    case posted
    case updated
    case removed
}

struct NotificationPayload: Codable, Equatable {
    let schemaVersion: Int
    let event: NotificationEventType
    let notificationID: String
    let packageName: String
    let appName: String
    let title: String?
    let subtitle: String?
    let body: String?
    let postedAt: Date
    let observedAt: Date
    let isOngoing: Bool
    let category: String?
    let channelID: String?
    let channelName: String?
    let assets: NotificationAssetPayload?

    var remoteIdentifier: String {
        notificationID
    }

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case event
        case notificationID = "notification_id"
        case packageName = "package_name"
        case appName = "app_name"
        case title
        case subtitle
        case body
        case postedAt = "posted_at"
        case observedAt = "observed_at"
        case isOngoing = "is_ongoing"
        case category
        case channelID = "channel_id"
        case channelName = "channel_name"
        case assets
    }
}

struct NotificationAssetPayload: Codable, Equatable {
    let appIcon: NotificationImagePayload?
    let largeIcon: NotificationImagePayload?
    let heroImage: NotificationImagePayload?

    enum CodingKeys: String, CodingKey {
        case appIcon = "app_icon"
        case largeIcon = "large_icon"
        case heroImage = "hero_image"
    }
}

struct NotificationImagePayload: Codable, Equatable {
    let mimeType: String
    let dataBase64: String
    let width: Int
    let height: Int

    enum CodingKeys: String, CodingKey {
        case mimeType = "mime_type"
        case dataBase64 = "data_base64"
        case width
        case height
    }
}
