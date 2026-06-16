// OpenWarden parent — iOS entry point (SCAFFOLD; built on macOS later).
// Wires libsodium init at launch and registers the BGAppRefreshTask id
// (PARENT_KMP_STRUCTURE.md §6, §9). Fill in when building on a Mac.
import SwiftUI
// import OpenWardenShared   // XCFramework produced by :shared on macOS

@main
struct OpenWardenApp: App {
    init() {
        // Task { try? await CryptoBootstrapKt.bootstrapCrypto() }  // SKIE: suspend -> async
        // BGTaskScheduler.shared.register(forTaskWithIdentifier: "com.openwarden.parent.refresh", ...)
    }

    var body: some Scene {
        WindowGroup {
            DashboardView()
        }
    }
}
