import SwiftUI

@main
struct AirBridgeApp: App {
    @StateObject private var appState: AppState
    @StateObject private var pairingViewModel: PairingViewModel

    private let appContainer: AppContainer

    init() {
        DesktopFileLogger.installRuntimeLogging()
        DesktopFileLogger.log("AirBridge app init started")
        let state = AppState()
        let container = AppContainer(appState: state)

        _appState = StateObject(wrappedValue: state)
        _pairingViewModel = StateObject(wrappedValue: container.makePairingViewModel())
        appContainer = container
        DesktopFileLogger.log("AirBridge app init completed")
    }

    var body: some Scene {
        MenuBarExtra("AirBridge", systemImage: appState.statusImageName) {
            StatusMenuView(
                appState: appState,
                pairingViewModel: pairingViewModel
            )
            .task {
                DesktopFileLogger.log("MenuBarExtra task started")
                await appContainer.startIfNeeded()
            }
        }
        .menuBarExtraStyle(.window)

        Settings {
            PairingView(
                viewModel: pairingViewModel,
                appState: appState
            )
            .frame(minWidth: 560, minHeight: 720)
            .task {
                DesktopFileLogger.log("Settings task started")
                await appContainer.startIfNeeded()
            }
        }
    }
}
