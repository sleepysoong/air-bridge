import AppKit
import Foundation

@MainActor
final class PasteboardMonitor {
    var onChange: (() -> Void)?

    private let pasteboard: NSPasteboard
    private let pollInterval: TimeInterval
    private var timer: Timer?
    private var lastChangeCount: Int

    init(pasteboard: NSPasteboard = .general, pollInterval: TimeInterval = 0.8) {
        self.pasteboard = pasteboard
        self.pollInterval = pollInterval
        lastChangeCount = pasteboard.changeCount
    }

    func start() {
        guard timer == nil else {
            return
        }

        lastChangeCount = pasteboard.changeCount
        timer = Timer.scheduledTimer(withTimeInterval: pollInterval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }

                if self.pasteboard.changeCount != self.lastChangeCount {
                    self.lastChangeCount = self.pasteboard.changeCount
                    self.onChange?()
                }
            }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }
}
