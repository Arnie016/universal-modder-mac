// umhelper: the native half of `um mac`. Compiled on first use by um/mac.py (swiftc, cached by hash in
// ~/.universal-modder/bin), so the package ships source and needs no build step - like um/ps1 on Windows.
//
//   umhelper perms [--request]          {"screen": bool, "accessibility": bool}
//   umhelper windows [--pid N] [--all]  on-screen windows: id, pid, owner, title, bounds (points), scale
//   umhelper front                      pid + name of the frontmost app
//   umhelper drive --pid N              line protocol on stdin (WinDrive-compatible, see DRIVE below)
//   umhelper record --window ID --out F.mov [--seconds S] [--fps 30] [--no-audio] [--cursor]
//
// Coordinates: CoreGraphics global points, top-left origin. Screenshots from `screencapture -l` are in
// pixels (2x on Retina), so drive's click/move take *screenshot pixels* relative to the window and divide by
// the window's backing scale; append "pt" to pass points instead.
import AppKit
import ApplicationServices
import AVFoundation
import CoreGraphics
import Foundation
import ScreenCaptureKit

setvbuf(stdout, nil, _IOLBF, 0)

func out(_ s: String) { print(s); fflush(stdout) }
func json(_ o: Any) {
    let d = try! JSONSerialization.data(withJSONObject: o, options: [.prettyPrinted, .sortedKeys])
    out(String(data: d, encoding: .utf8)!)
}
func fail(_ m: String) -> Never {
    FileHandle.standardError.write(("umhelper: " + m + "\n").data(using: .utf8)!)
    exit(1)
}
func arg(_ name: String) -> String? {
    let a = CommandLine.arguments
    if let i = a.firstIndex(of: name), i + 1 < a.count { return a[i + 1] }
    return nil
}
func flag(_ name: String) -> Bool { CommandLine.arguments.contains(name) }

// ---------------------------------------------------------------------------------------------- windows

struct Win {
    let id: UInt32, pid: Int32, owner: String, title: String, bounds: CGRect, layer: Int
}

func screenScale(for r: CGRect) -> CGFloat {
    // NSScreen frames are bottom-left origin; flip into CG's top-left space using the primary screen.
    guard let primary = NSScreen.screens.first else { return 2 }
    var best: (CGFloat, CGFloat) = (0, primary.backingScaleFactor)
    for s in NSScreen.screens {
        let f = s.frame
        let cg = CGRect(x: f.minX, y: primary.frame.maxY - f.maxY, width: f.width, height: f.height)
        let inter = cg.intersection(r)
        let area = inter.isNull ? 0 : inter.width * inter.height
        if area > best.0 { best = (area, s.backingScaleFactor) }
    }
    return best.1
}

func listWindows(pid: Int32? = nil, all: Bool = false) -> [Win] {
    // For one process, include windows on other Spaces / behind full-screen apps (the user switched away);
    // the global list stays on-screen only so `ps` shows what is actually visible.
    let opts: CGWindowListOption = pid != nil ? [.optionAll, .excludeDesktopElements] : [.optionOnScreenOnly, .excludeDesktopElements]
    guard let info = CGWindowListCopyWindowInfo(opts, kCGNullWindowID) as? [[String: Any]]
    else { return [] }
    var res: [Win] = []
    for w in info {
        let layer = w[kCGWindowLayer as String] as? Int ?? 0
        let wpid = (w[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value ?? -1
        if let p = pid, p != wpid { continue }
        if !all && layer != 0 { continue }
        var rect = CGRect.zero
        if let b = w[kCGWindowBounds as String] as? NSDictionary, let r = CGRect(dictionaryRepresentation: b) { rect = r }
        if rect.width < 32 || rect.height < 32 { continue }
        if pid != nil && (w[kCGWindowAlpha as String] as? Double ?? 1) == 0 { continue }
        res.append(Win(id: (w[kCGWindowNumber as String] as? NSNumber)?.uint32Value ?? 0, pid: wpid,
                       owner: w[kCGWindowOwnerName as String] as? String ?? "",
                       title: w[kCGWindowName as String] as? String ?? "", bounds: rect, layer: layer))
    }
    return res
}

/// The game's main window: the biggest normal window of that process.
func mainWindow(_ pid: Int32) -> Win? {
    listWindows(pid: pid).max { $0.bounds.width * $0.bounds.height < $1.bounds.width * $1.bounds.height }
}

// ---------------------------------------------------------------------------------------------- keys

// Mac virtual key codes (Carbon kVK_*). Names, Windows VK hex ("0x1B", so WinDrive scripts port over),
// or "mac:53" for a raw code.
let keyNames: [String: CGKeyCode] = [
    "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8, "v": 9, "b": 11, "q": 12, "w": 13, "e": 14,
    "r": 15, "y": 16, "t": 17, "1": 18, "2": 19, "3": 20, "4": 21, "6": 22, "5": 23, "=": 24, "equal": 24, "9": 25, "7": 26,
    "-": 27, "minus": 27, "8": 28, "0": 29, "]": 30, "o": 31, "u": 32, "[": 33, "i": 34, "p": 35, "return": 36, "enter": 36,
    "l": 37, "j": 38, "'": 39, "k": 40, ";": 41, "\\": 42, ",": 43, "/": 44, "n": 45, "m": 46, ".": 47, "tab": 48,
    "space": 49, "`": 50, "grave": 50, "backspace": 51, "delete": 51, "escape": 53, "esc": 53, "cmd": 55, "command": 55,
    "shift": 56, "capslock": 57, "alt": 58, "option": 58, "ctrl": 59, "control": 59, "rshift": 60, "ralt": 61, "rctrl": 62,
    "f5": 96, "f6": 97, "f7": 98, "f3": 99, "f8": 100, "f9": 101, "f11": 103, "f10": 109, "f12": 111, "home": 115,
    "pageup": 116, "forwarddelete": 117, "f4": 118, "end": 119, "f2": 120, "pagedown": 121, "f1": 122,
    "left": 123, "right": 124, "down": 125, "up": 126,
]
let winVK: [Int: String] = [
    0x08: "backspace", 0x09: "tab", 0x0D: "return", 0x10: "shift", 0x11: "ctrl", 0x12: "alt", 0x14: "capslock", 0x1B: "escape",
    0x20: "space", 0x21: "pageup", 0x22: "pagedown", 0x23: "end", 0x24: "home", 0x25: "left", 0x26: "up", 0x27: "right",
    0x28: "down", 0x2E: "forwarddelete", 0xBA: ";", 0xBB: "=", 0xBC: ",", 0xBD: "-", 0xBE: ".", 0xBF: "/", 0xC0: "`",
    0xDB: "[", 0xDC: "\\", 0xDD: "]", 0xDE: "'", 0xA0: "shift", 0xA1: "rshift", 0xA2: "ctrl", 0xA3: "rctrl",
]

func keyCode(_ s: String) -> CGKeyCode? {
    let k = s.lowercased()
    if k.hasPrefix("mac:"), let v = UInt16(k.dropFirst(4)) { return v }
    if k.hasPrefix("0x"), let v = Int(k.dropFirst(2), radix: 16) {
        if (0x30...0x39).contains(v) || (0x41...0x5A).contains(v) { return keyNames[String(UnicodeScalar(UInt8(v))).lowercased()] }
        if (0x70...0x7B).contains(v) { return keyNames["f\(v - 0x6F)"] }
        if let n = winVK[v] { return keyNames[n] }
        return nil
    }
    return keyNames[k]
}

// ---------------------------------------------------------------------------------------------- drive

let src = CGEventSource(stateID: .hidSystemState)

func post(_ e: CGEvent?) { e?.post(tap: .cghidEventTap) }

func frontPid() -> Int32 { NSWorkspace.shared.frontmostApplication?.processIdentifier ?? -1 }

func focus(_ pid: Int32) -> Bool {
    guard let app = NSRunningApplication(processIdentifier: pid) else { return false }
    if #available(macOS 14.0, *) { _ = app.activate(from: NSRunningApplication.current, options: []) }
    app.activate(options: [.activateAllWindows])
    for _ in 0..<40 {
        if frontPid() == pid { return true }
        RunLoop.current.run(until: Date().addingTimeInterval(0.05))
    }
    return frontPid() == pid
}

func modifierFlag(_ code: CGKeyCode) -> CGEventFlags? {
    switch code {
    case 56, 60: return .maskShift
    case 59, 62: return .maskControl
    case 58, 61: return .maskAlternate
    case 55: return .maskCommand
    default: return nil
    }
}
var heldFlags: CGEventFlags = []

func keyEvent(_ code: CGKeyCode, down: Bool) {
    if let m = modifierFlag(code) {
        if down { heldFlags.insert(m) } else { heldFlags.remove(m) }
        let e = CGEvent(source: src)
        e?.type = .flagsChanged
        e?.setIntegerValueField(.keyboardEventKeycode, value: Int64(code))
        e?.flags = heldFlags
        post(e)
        return
    }
    let e = CGEvent(keyboardEventSource: src, virtualKey: code, keyDown: down)
    e?.flags = heldFlags
    post(e)
}

func sleepMs(_ ms: Double) { usleep(useconds_t(max(0, ms) * 1000)) }

func drive(_ pid: Int32) {
    guard NSRunningApplication(processIdentifier: pid) != nil else { fail("no process \(pid)") }
    out(AXIsProcessTrusted() ? "ready \(pid)" : "ready \(pid) (warning: no Accessibility permission - input will be dropped)")
    var cursor = CGPoint.zero
    while let line = readLine() {
        let p = line.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        guard let cmd = p.first?.lowercased() else { continue }
        func num(_ i: Int) -> Double { i < p.count ? Double(p[i]) ?? 0 : 0 }
        let usePoints = p.last?.lowercased() == "pt"
        // window-relative -> global points
        func toGlobal(_ x: Double, _ y: Double) -> CGPoint? {
            guard let w = mainWindow(pid) else { return nil }
            let s = usePoints ? 1 : screenScale(for: w.bounds)
            return CGPoint(x: w.bounds.minX + CGFloat(x) / s, y: w.bounds.minY + CGFloat(y) / s)
        }
        let needsFront: Set<String> = ["click", "move", "rel", "drag", "key", "hold", "type", "wheel", "down", "up"]
        if needsFront.contains(cmd) && frontPid() != pid { out("error target is not the foreground app (focus first)"); continue }
        switch cmd {
        case "focus":
            out(focus(pid) ? "ok" : "error could not bring \(pid) to the foreground")
        case "rect":
            if let w = mainWindow(pid) {
                out("\(Int(w.bounds.minX)) \(Int(w.bounds.minY)) \(Int(w.bounds.width)) \(Int(w.bounds.height)) \(screenScale(for: w.bounds)) \(w.id)")
            } else { out("none") }
        case "move":
            guard let g = toGlobal(num(1), num(2)) else { out("error no window"); continue }
            cursor = g
            post(CGEvent(mouseEventSource: src, mouseType: .mouseMoved, mouseCursorPosition: g, mouseButton: .left))
            out("ok")
        case "rel":
            // relative look for mouselook games: deltas are what FPS cameras read
            let cur = CGEvent(source: nil)?.location ?? cursor
            let e = CGEvent(mouseEventSource: src, mouseType: .mouseMoved, mouseCursorPosition: cur, mouseButton: .left)
            e?.setIntegerValueField(.mouseEventDeltaX, value: Int64(num(1)))
            e?.setIntegerValueField(.mouseEventDeltaY, value: Int64(num(2)))
            post(e)
            out("ok")
        case "click", "down", "up":
            let right = p.contains("right")
            let pos: CGPoint
            if p.count >= 3, Double(p[1]) != nil, let g = toGlobal(num(1), num(2)) { pos = g } else { pos = CGEvent(source: nil)?.location ?? cursor }
            cursor = pos
            let (dn, upT, btn): (CGEventType, CGEventType, CGMouseButton) = right ? (.rightMouseDown, .rightMouseUp, .right) : (.leftMouseDown, .leftMouseUp, .left)
            post(CGEvent(mouseEventSource: src, mouseType: .mouseMoved, mouseCursorPosition: pos, mouseButton: btn))
            sleepMs(30)
            if cmd != "up" { post(CGEvent(mouseEventSource: src, mouseType: dn, mouseCursorPosition: pos, mouseButton: btn)) }
            if cmd == "click" { sleepMs(60) }
            if cmd != "down" { post(CGEvent(mouseEventSource: src, mouseType: upT, mouseCursorPosition: pos, mouseButton: btn)) }
            out("ok")
        case "drag":
            guard let a = toGlobal(num(1), num(2)), let b = toGlobal(num(3), num(4)) else { out("error no window"); continue }
            post(CGEvent(mouseEventSource: src, mouseType: .leftMouseDown, mouseCursorPosition: a, mouseButton: .left))
            for i in 1...20 {
                let t = CGFloat(i) / 20
                post(CGEvent(mouseEventSource: src, mouseType: .leftMouseDragged,
                             mouseCursorPosition: CGPoint(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t), mouseButton: .left))
                sleepMs(12)
            }
            post(CGEvent(mouseEventSource: src, mouseType: .leftMouseUp, mouseCursorPosition: b, mouseButton: .left))
            out("ok")
        case "key":
            guard p.count > 1, let code = keyCode(p[1]) else { out("error unknown key \(p.count > 1 ? p[1] : "")"); continue }
            let mode = p.count > 2 ? p[2].lowercased() : "tap"
            if mode != "up" { keyEvent(code, down: true) }
            if mode == "tap" { sleepMs(50) }
            if mode != "down" { keyEvent(code, down: false) }
            out("ok")
        case "hold":
            guard p.count > 2, let code = keyCode(p[1]) else { out("error usage: hold KEY MS"); continue }
            keyEvent(code, down: true)
            // games poll held keys per frame; auto-repeat keyDowns keep key-repeat style UIs happy too
            let end = Date().addingTimeInterval(num(2) / 1000)
            while Date() < end { sleepMs(30) }
            keyEvent(code, down: false)
            out("ok")
        case "type":
            let text = String(line.dropFirst(min(line.count, 5)))
            for ch in text.utf16 {
                var c = ch
                for down in [true, false] {
                    let e = CGEvent(keyboardEventSource: src, virtualKey: 0, keyDown: down)
                    e?.keyboardSetUnicodeString(stringLength: 1, unicodeString: &c)
                    post(e)
                }
                sleepMs(15)
            }
            out("ok")
        case "wheel":
            post(CGEvent(scrollWheelEvent2Source: src, units: .line, wheelCount: 1, wheel1: Int32(num(1)), wheel2: 0, wheel3: 0))
            out("ok")
        case "sleep":
            sleepMs(num(1))
            out("ok")
        case "quit", "exit":
            out("bye")
            exit(0)
        default:
            out("error unknown command \(cmd)")
        }
    }
}

// ---------------------------------------------------------------------------------------------- record

@available(macOS 15.0, *)
final class Recorder: NSObject, SCStreamDelegate, @unchecked Sendable, SCRecordingOutputDelegate {
    var stream: SCStream?
    var done = false
    var started = Date()
    var error: Error?

    // ScreenCaptureKit puts a recording indicator in the menu bar (an NSWindow), so capture must start on the
    // main thread of a running NSApplication - from a plain dispatchMain() worker it throws NSInternalInconsistency.
    @MainActor
    func start(windowID: UInt32, url: URL, fps: Int, audio: Bool, cursor: Bool) async throws -> [String: Any] {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        guard let win = content.windows.first(where: { $0.windowID == windowID }) else { fail("window \(windowID) not shareable (on screen? Screen Recording allowed?)") }
        guard let app = win.owningApplication else { fail("window has no owning app") }
        // A desktop-independent window filter follows the game's window even on another Space or behind other
        // windows; for that filter ScreenCaptureKit captures audio from the window's own app only. (A display filter
        // + sourceRect fails with "failure to process first sample buffer" once the window is off the display.)
        let filter = SCContentFilter(desktopIndependentWindow: win)
        let scale = CGFloat(filter.pointPixelScale)
        let cfg = SCStreamConfiguration()
        cfg.width = Int(win.frame.width * scale) & ~1
        cfg.height = Int(win.frame.height * scale) & ~1
        cfg.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        cfg.showsCursor = cursor
        cfg.capturesAudio = audio
        cfg.excludesCurrentProcessAudio = true
        cfg.sampleRate = 48000
        cfg.channelCount = 2
        cfg.queueDepth = 6
        let rc = SCRecordingOutputConfiguration()
        rc.outputURL = url
        rc.outputFileType = .mov
        rc.videoCodecType = .h264
        let ro = SCRecordingOutput(configuration: rc, delegate: self)
        let s = SCStream(filter: filter, configuration: cfg, delegate: self)
        try s.addRecordingOutput(ro)
        try await s.startCapture()
        stream = s
        started = Date()
        return ["app": app.applicationName, "pid": app.processID, "window": windowID, "width": cfg.width, "height": cfg.height,
                "fps": fps, "audio": audio, "out": url.path, "started": started.timeIntervalSince1970]
    }

    func stop() async {
        try? await stream?.stopCapture()
        for _ in 0..<100 where !done { try? await Task.sleep(nanoseconds: 100_000_000) }
    }

    func recordingOutputDidFinishRecording(_ recordingOutput: SCRecordingOutput) { done = true }
    func recordingOutput(_ recordingOutput: SCRecordingOutput, didFailWithError error: Error) { self.error = error; done = true }
    func stream(_ stream: SCStream, didStopWithError error: Error) { self.error = error; done = true }
}

func record() {
    guard #available(macOS 15.0, *) else { fail("record needs macOS 15+ (ScreenCaptureKit recording output)") }
    guard let ws = arg("--window"), let wid = UInt32(ws), let o = arg("--out") else { fail("usage: record --window ID --out F.mov") }
    let url = URL(fileURLWithPath: o)
    try? FileManager.default.removeItem(at: url)
    let rec = Recorder()
    let seconds = Double(arg("--seconds") ?? "")
    let stopSig = DispatchSemaphore(value: 0)
    signal(SIGINT, SIG_IGN); signal(SIGTERM, SIG_IGN)
    var sources: [DispatchSourceSignal] = []
    for sig in [SIGINT, SIGTERM] {
        let s = DispatchSource.makeSignalSource(signal: sig, queue: .global())
        s.setEventHandler { stopSig.signal() }
        s.resume()
        sources.append(s)
    }
    Task { @MainActor in
        do {
            let meta = try await rec.start(windowID: wid, url: url, fps: Int(arg("--fps") ?? "30") ?? 30,
                                           audio: !flag("--no-audio"), cursor: flag("--cursor"))
            json(meta)
        } catch { fail("could not start: \(error.localizedDescription)") }
        DispatchQueue.global().async {
            if let s = seconds { _ = stopSig.wait(timeout: .now() + s) } else {
                DispatchQueue.global().async { while let l = readLine() { if l.trimmingCharacters(in: .whitespaces).lowercased() == "stop" { stopSig.signal() } } ; stopSig.signal() }
                stopSig.wait()
            }
            Task {
                await rec.stop()
                let dur = Date().timeIntervalSince(rec.started)
                if let e = rec.error { FileHandle.standardError.write("umhelper: recording error: \(e.localizedDescription)\n".data(using: .utf8)!) }
                json(["out": url.path, "seconds": (dur * 100).rounded() / 100, "ok": FileManager.default.fileExists(atPath: url.path)])
                exit(rec.error == nil ? 0 : 1)
            }
        }
    }
    let app = NSApplication.shared
    app.setActivationPolicy(.accessory)   // no Dock icon, never steals focus from the game
    app.run()
}

// ---------------------------------------------------------------------------------------------- main

let cmd = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "help"
switch cmd {
case "perms":
    if flag("--request") {
        _ = CGRequestScreenCaptureAccess()  // shows the system prompt once; the user decides in System Settings
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(opts)
    }
    json(["screen": CGPreflightScreenCaptureAccess(), "accessibility": AXIsProcessTrusted(),
          "responsible_hint": "permissions belong to the app that launched this (Terminal, iTerm, VS Code, Claude...)"])
case "windows":
    let pid = arg("--pid").flatMap { Int32($0) }
    json(listWindows(pid: pid, all: flag("--all")).map { w in
        ["id": w.id, "pid": w.pid, "owner": w.owner, "title": w.title, "layer": w.layer, "scale": screenScale(for: w.bounds),
         "x": Int(w.bounds.minX), "y": Int(w.bounds.minY), "w": Int(w.bounds.width), "h": Int(w.bounds.height)] as [String: Any]
    })
case "front":
    let a = NSWorkspace.shared.frontmostApplication
    json(["pid": a?.processIdentifier ?? -1, "name": a?.localizedName ?? "", "bundle": a?.bundleIdentifier ?? ""])
case "drive":
    guard let p = arg("--pid"), let pid = Int32(p) else { fail("usage: drive --pid N") }
    drive(pid)
case "record":
    record()
default:
    out("umhelper perms|windows|front|drive|record  (see the header of umhelper.swift)")
}
