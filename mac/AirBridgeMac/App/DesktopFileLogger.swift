import AppKit
import Foundation

private func airBridgeUncaughtExceptionHandler(_ exception: NSException) {
    let reason = exception.reason ?? "Unknown reason"
    let callStack = exception.callStackSymbols.joined(separator: " | ")
    DesktopFileLogger.log(
        "UNCAUGHT EXCEPTION [\(exception.name.rawValue)] \(reason) | stack=\(callStack)",
        level: .error
    )
}

enum DesktopFileLogger {
    enum Level: String {
        case info = "INFO"
        case error = "ERROR"
    }

    private static let queue = DispatchQueue(label: "com.airbridge.desktop-file-logger")
    private static let fileURL: URL = {
        let fileManager = FileManager.default
        let desktopURL = fileManager.urls(for: .desktopDirectory, in: .userDomainMask).first
        let homeURL = fileManager.homeDirectoryForCurrentUser
        let fileURL = (desktopURL ?? homeURL).appendingPathComponent("air-bridge.log")

        queue.sync {
            if !fileManager.fileExists(atPath: fileURL.path) {
                fileManager.createFile(atPath: fileURL.path, contents: nil)
            }
        }
        return fileURL
    }()
    @MainActor private static var observerTokens: [NSObjectProtocol] = []
    @MainActor private static var didInstallRuntimeLogging = false

    @MainActor
    static func installRuntimeLogging() {
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
                DesktopFileLogger.log("Application did finish launching")
            },
            notificationCenter.addObserver(
                forName: NSApplication.didBecomeActiveNotification,
                object: nil,
                queue: .main
            ) { _ in
                DesktopFileLogger.log("Application did become active")
            },
            notificationCenter.addObserver(
                forName: NSApplication.didResignActiveNotification,
                object: nil,
                queue: .main
            ) { _ in
                DesktopFileLogger.log("Application did resign active")
            },
            notificationCenter.addObserver(
                forName: NSApplication.willTerminateNotification,
                object: nil,
                queue: .main
            ) { _ in
                DesktopFileLogger.log("Application will terminate")
            }
        ]

        log("Runtime logging installed")
    }

    static func log(_ message: String, level: Level = .info, sync: Bool = false) {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let line = "[\(formatter.string(from: Date()))] [\(level.rawValue)] \(message)\n"
        let fileURL = self.fileURL
        
        let writeBlock: @Sendable () -> Void = {
            do {
                let data = Data(line.utf8)
                let handle = try FileHandle(forWritingTo: fileURL)
                defer { try? handle.close() }
                try handle.seekToEnd()
                try handle.write(contentsOf: data)
            } catch {
            }
        }
        
        if sync {
            queue.sync(execute: writeBlock)
        } else {
            queue.async(execute: writeBlock)
        }
    }

    static func log(error: Error, context: String) {
        log("[\(context)] \(error.localizedDescription)", level: .error)
    }

    static func log(errorMessage: String, context: String) {
        log("[\(context)] \(errorMessage)", level: .error)
    }
}
