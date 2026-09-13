#!/usr/bin/env swift
// 由 1024×1024 PNG 渲染 macOS AppIcon.icns（10 个标准尺寸）
// 用 NSImage 缩放各尺寸，CGImageDestination 直接写 icns（不依赖 sips/iconutil）
// 用法：swift png_to_icns.swift <input.png> <output.icns>
import AppKit
import Foundation
import ImageIO
import UniformTypeIdentifiers

guard CommandLine.arguments.count >= 3 else {
    FileHandle.standardError.write(Data("usage: png_to_icns <in.png> <out.icns>\n".utf8))
    exit(2)
}
let inPath = CommandLine.arguments[1]
let outPath = CommandLine.arguments[2]

struct Size { let dim: Int; let tag: String; let type: String }
let sizes: [Size] = [
    Size(dim: 16,   tag: "16x16",         type: "icp4"),
    Size(dim: 32,   tag: "16x16@2x",      type: "icp5"),
    Size(dim: 32,   tag: "32x32",         type: "icp5"),
    Size(dim: 64,   tag: "32x32@2x",      type: "icp6"),
    Size(dim: 128,  tag: "128x128",       type: "ic07"),
    Size(dim: 256,  tag: "128x128@2x",    type: "ic08"),
    Size(dim: 256,  tag: "256x256",       type: "ic08"),
    Size(dim: 512,  tag: "256x256@2x",    type: "ic09"),
    Size(dim: 512,  tag: "512x512",       type: "ic09"),
    Size(dim: 1024, tag: "512x512@2x",    type: "ic10")
]

guard let src = NSImage(contentsOfFile: inPath),
      src.size.width > 0 else {
    FileHandle.standardError.write(Data("failed to load input image: \(inPath)\n".utf8))
    exit(1)
}

func render(_ s: Size) -> CGImage? {
    let target = NSSize(width: CGFloat(s.dim), height: CGFloat(s.dim))
    let img = NSImage(size: target)
    img.lockFocus()
    let g = NSGraphicsContext.current
    g?.imageInterpolation = .high
    src.draw(in: NSRect(origin: .zero, size: target),
             from: NSRect(origin: .zero, size: src.size),
             operation: .copy, fraction: 1.0)
    img.unlockFocus()
    guard let tiff = img.tiffRepresentation,
          let rep = NSBitmapImageRep(data: tiff) else { return nil }
    return rep.cgImage
}

let utType = UTType.icns.identifier as CFString
let data = NSMutableData()
guard let dest = CGImageDestinationCreateWithData(data, utType, sizes.count, nil) else {
    FileHandle.standardError.write(Data("CGImageDestinationCreateWithData failed\n".utf8))
    exit(1)
}

for s in sizes {
    guard let cg = render(s) else {
        FileHandle.standardError.write(Data("render \(s.tag) failed\n".utf8))
        exit(1)
    }
    let props: [String: Any] = [
        kCGImagePropertyPixelWidth as String: s.dim,
        kCGImagePropertyPixelHeight as String: s.dim,
        "ImageDescription": s.type
    ]
    CGImageDestinationAddImage(dest, cg, props as CFDictionary)
}

guard CGImageDestinationFinalize(dest) else {
    FileHandle.standardError.write(Data("CGImageDestinationFinalize failed\n".utf8))
    exit(1)
}
do {
    try data.write(to: URL(fileURLWithPath: outPath), options: .atomic)
} catch {
    FileHandle.standardError.write(Data("write failed: \(error)\n".utf8))
    exit(1)
}
print("wrote \(outPath)")
