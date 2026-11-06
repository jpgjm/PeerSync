import Foundation
import AVFoundation

/// Delays background suspension by playing silent audio (optional feature).
/// Generates a silent WAV at runtime and loops it with AVAudioPlayer.
/// Used together with UIBackgroundModes: audio. No entitlement needed.
/// Note: drains battery, and App Store review wouldn't allow it (assumes SideStore distribution).
final class BackgroundKeepAlive {
    private static let tag = "KeepAlive"

    static let shared = BackgroundKeepAlive()

    private var player: AVAudioPlayer?

    private init() {}

    var isRunning: Bool { player?.isPlaying ?? false }

    func start() {
        guard player == nil else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, options: [.mixWithOthers])
            try session.setActive(true)

            let p = try AVAudioPlayer(data: Self.silentWavData(), fileTypeHint: AVFileType.wav.rawValue)
            p.numberOfLoops = -1
            p.volume = 0.0
            guard p.play() else {
                qlog(.warning, Self.tag, "Couldn't start silent playback")
                return
            }
            player = p
            qlog(.info, Self.tag, "keep-alive started")
        } catch {
            qlog(.warning, Self.tag, "keep-alive start failed: \(error)")
            player = nil
        }
    }

    func stop() {
        player?.stop()
        player = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        qlog(.info, Self.tag, "keep-alive stopped")
    }

    /// Generates a 1-second silent WAV (16-bit / mono / 8 kHz) in memory.
    private static func silentWavData() -> Data {
        let sampleRate: UInt32 = 8000
        let numSamples: UInt32 = sampleRate // 1 second
        let dataSize = numSamples * 2 // 16bit mono

        var d = Data()
        func le32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func le16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }

        d.append(contentsOf: Array("RIFF".utf8))
        le32(36 + dataSize)
        d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8))
        le32(16)                 // fmt chunk size
        le16(1)                  // PCM
        le16(1)                  // mono
        le32(sampleRate)
        le32(sampleRate * 2)     // byte rate
        le16(2)                  // block align
        le16(16)                 // bits per sample
        d.append(contentsOf: Array("data".utf8))
        le32(dataSize)
        d.append(Data(count: Int(dataSize))) // Silence (all zeros)
        return d
    }
}
