import Foundation
import AVFoundation

/// 無音オーディオの再生でバックグラウンドのサスペンドを遅らせる（任意機能）。
/// 実行時に無音 WAV を生成して AVAudioPlayer でループ再生する。
/// UIBackgroundModes: audio と組み合わせて使う。entitlement は不要。
/// 注意: バッテリーを消費する。また App Store 審査では認められない手法（SideStore 配布前提）。
final class BackgroundKeepAlive {
    private static let tag = "KeepAlive"

    static let shared = BackgroundKeepAlive()

    private var player: AVAudioPlayer?
    private let log = AppLogger.shared

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
                log.warn(Self.tag, "無音再生を開始できませんでした")
                return
            }
            player = p
            log.info(Self.tag, "keep-alive started")
        } catch {
            log.warn(Self.tag, "keep-alive start failed: \(error)")
            player = nil
        }
    }

    func stop() {
        player?.stop()
        player = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        log.info(Self.tag, "keep-alive stopped")
    }

    /// 1 秒の無音 WAV（16bit / mono / 8kHz）をメモリ上で生成する。
    private static func silentWavData() -> Data {
        let sampleRate: UInt32 = 8000
        let numSamples: UInt32 = sampleRate // 1 秒
        let dataSize = numSamples * 2 // 16bit mono

        var d = Data()
        func le32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func le16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }

        d.append(contentsOf: Array("RIFF".utf8))
        le32(36 + dataSize)
        d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8))
        le32(16)                 // fmt チャンクサイズ
        le16(1)                  // PCM
        le16(1)                  // mono
        le32(sampleRate)
        le32(sampleRate * 2)     // byte rate
        le16(2)                  // block align
        le16(16)                 // bits per sample
        d.append(contentsOf: Array("data".utf8))
        le32(dataSize)
        d.append(Data(count: Int(dataSize))) // 無音（全て 0）
        return d
    }
}
