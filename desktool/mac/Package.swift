// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "desktool",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "StreamDesk",
            path: "Sources/StreamDesk"
        )
    ]
)
