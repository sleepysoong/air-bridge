import Foundation

final class AppPreferences {
    private enum Keys {
        static let relayBaseURLText = "relay_base_url_text"
        static let deviceName = "device_name"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var relayBaseURLText: String? {
        get { defaults.string(forKey: Keys.relayBaseURLText) }
        set { defaults.set(newValue, forKey: Keys.relayBaseURLText) }
    }

    var deviceName: String? {
        get { defaults.string(forKey: Keys.deviceName) }
        set { defaults.set(newValue, forKey: Keys.deviceName) }
    }
}
