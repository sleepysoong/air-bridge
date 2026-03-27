import AppKit
import Foundation

private func airBridgeUncaughtExceptionHandler(_ exception: NSException) {
    let reason = exception.reason ?? "Unknown reason"
    let callStack = exception.callStackSymbols.joined(separator: " | ")
    DesktopFileLogger.shared.log(
        "UNCAUGHT EXCEPTION [\(exception.name.rawValue)] \(reason) | stack=\(callStack)",
        level: .error
    )
}

final class DesktopFileLogger {
    enum Level: String {
        case info = "INFO"
        case error = "ERROR"
    }

    static let shared = DesktopFileLogger()

    private let queue = DispatchQueue(label: "com.airbridge.desktop-file-logger")
    private let fileURL: URL
    private var observerTokens: [NSObjectProtocol] = []
    private var didInstallRuntimeLogging = false
    private let timestampFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    private init(fileManager: FileManager = .default) {
        let desktopURL = fileManager.urls(for: .desktopDirectory, in: .userDomainMask).first
        let homeURL = fileManager.homeDirectoryForCurrentUser
        fileURL = (desktopURL ?? homeURL).appendingPathComponent("air-bridge.log")

        queue.sync {
            if !fileManager.fileExists(atPath: fileURL.path) {
                fileManager.createFile(atPath: fileURL.path, contents: nil)
            }
        }
    }

    func installRuntimeLogging() {
        guard !didInstallRuntimeLogging else {
            return
        }

        didInstallRuntimeLogging = true
        NSSetUncaughtExceptionHandler(airBridgeUncaughtExceptionHandler)

        let notificationCenter = NotificationCenter.default
        observerTokens = [
            notificationCenter.addObserver(
                forName: NSApplication.didFinishLaunchingNotification,
                object: nil,
                queue: .main
            ) { _ in
                DesktopFileLogger.shared.log("Application did finish launching")
            },
            notificationCenter.addObserver(
                forName: NSApplication.didBecomeActiveNotification,
                object: nil,
                queue: .main
            ) { _ in
                DesktopFileLogger.shared.log("Application did become active")
            },
            notificationCenter.addObserver(
                forName: NSApplication.didResignActiveNotification,
                object: nil,
                queue: .main
            ) { _ in
                DesktopFileLogger.shared.log("Application did resign active")
            },
            notificationCenter.addObserver(
                forName: NSApplication.willTerminateNotification,
                object: nil,
                queue: .main
            ) { _ in
                DesktopFileLogger.shared.log("Application will terminate")
            }
        ]

        log("Runtime logging installed")
    }

    func log(_ message: String, level: Level = .info) {
        let line = "[\(timestampFormatter.string(from: Date()))] [\(level.rawValue)] \(message)\n"
        queue.async {
            do {
                let data = Data(line.utf8)
                let handle = try FileHandle(forWritingTo: self.fileURL)
                defer { try? handle.close() }
                try handle.seekToEnd()
                try handle.write(contentsOf: data)
            } catch {
            }
        }
    }

    func log(error: Error, context: String) {
        log("[\(context)] \(error.localizedDescription)", level: .error)
    }

    func log(errorMessage: String, context: String) {
        log("[\(context)] \(errorMessage)", level: .error)
    }
}
