// Dashboard (SCAFFOLD; built on macOS later). Mirrors the Android dashboard;
// will bind to shared StateFlows via SKIE-generated async APIs
// (PARENT_KMP_STRUCTURE.md §6).
import SwiftUI

struct DashboardView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("OpenWarden").font(.largeTitle)
            Text("Parent app — scaffold").font(.body)
            Text("Not paired").font(.callout)
        }
        .padding(24)
    }
}
