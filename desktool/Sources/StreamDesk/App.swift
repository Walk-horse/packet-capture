import SwiftUI

@main
struct StreamDeskApp: App {
    @StateObject private var client = SyncClient()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(client)
                .frame(minWidth: 1080, minHeight: 680)
        }
        .windowToolbarStyle(.unified)
    }
}
