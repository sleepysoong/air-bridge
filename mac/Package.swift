// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "AirBridgeMac",
    platforms: [
        .macOS(.v14),
    ],
    products: [
        .executable(
            name: "AirBridgeMac",
            targets: ["AirBridgeMac"]
        ),
    ],
    targets: [
        .executableTarget(
            name: "AirBridgeMac",
            path: "AirBridgeMac"
        ),
        .testTarget(
            name: "AirBridgeMacTests",
            dependencies: ["AirBridgeMac"],
            path: "Tests/AirBridgeMacTests"
        ),
    ]
)
