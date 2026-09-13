#!/usr/bin/env swift
// 渲染应用图标：圆角渐变背景 + 白色 SF Symbol，输出 1024×1024 PNG
// 用法：swift gen_icon.swift <out.png>
import AppKit
import Foundation

let outPath = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "/tmp/appicon-1024.png"
let size = 1024
let s = NSSize(width: size, height: size)

let image = NSImage(size: s)
image.lockFocus()

// 圆角矩形渐变背景
let cornerRadius = CGFloat(224) * CGFloat(size) / 1024
let bgPath = NSBezierPath(roundedRect: NSRect(origin: .zero, size: s), xRadius: cornerRadius, yRadius: cornerRadius)
NSGraphicsContext.saveGraphicsState()
bgPath.addClip()
let gradient = NSGradient(colors: [
    NSColor(srgbRed: 0.08, green: 0.36, blue: 0.92, alpha: 1.0),
    NSColor(srgbRed: 0.16, green: 0.74, blue: 0.88, alpha: 1.0)
])!
gradient.draw(in: NSRect(origin: .zero, size: s), angle: -90)
NSGraphicsContext.restoreGraphicsState()

// SF Symbol：深蓝→青色波形
let symbolName = "waveform.path.ecg"
if let base = NSImage(systemSymbolName: symbolName, accessibilityDescription: nil) {
    let mono = NSImage.SymbolConfiguration(paletteColors: [NSColor.white])
    let cfg = NSImage.SymbolConfiguration(pointSize: CGFloat(size) * 0.62, weight: .bold)
        .applying(mono)
    let sym = base.withSymbolConfiguration(cfg) ?? base
    let target = NSSize(width: CGFloat(size) * 0.62, height: CGFloat(size) * 0.62)
    let rect = NSRect(
        x: (CGFloat(size) - target.width) / 2,
        y: (CGFloat(size) - target.height) / 2,
        width: target.width, height: target.height
    )
    sym.draw(in: rect, from: NSRect(origin: .zero, size: sym.size), operation: .sourceOver, fraction: 1.0)
}

image.unlockFocus()

guard let tiff = image.tiffRepresentation,
      let rep = NSBitmapImageRep(data: tiff),
      let png = rep.representation(using: .png, properties: [:]) else {
    FileHandle.standardError.write(Data("failed to render png\n".utf8))
    exit(1)
}
try png.write(to: URL(fileURLWithPath: outPath))
print("wrote \(outPath) (\(png.count) bytes)")
