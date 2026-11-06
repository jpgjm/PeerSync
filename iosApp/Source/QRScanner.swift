import UIKit
import AVFoundation
import VisionKit
import PeerSyncShared

/// Scans the pairing QR code. Shown as a modal with a NavigationController and a Cancel button.
/// 0.2.29: replaced the custom AVFoundation scanner (AVCaptureSession + AVCaptureMetadataOutput) with
/// VisionKit's DataScannerViewController (iOS 16+, the system scanner UI).
/// The camera permission check (AVCaptureDevice.requestAccess) and the denied message are unchanged.
enum QRScanner {
    private static var completion: ((String?) -> Void)?

    static func present(completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard let presenter = topViewController() else {
                qlog(.warning, "QRScan", "No presenting ViewController found")
                completion(nil)
                return
            }
            self.completion = completion
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    let vc = QRScannerController()
                    vc.cameraDenied = !granted
                    vc.onFinish = { text in
                        self.completion?(text)
                        self.completion = nil
                    }
                    let nav = UINavigationController(rootViewController: vc)
                    nav.modalPresentationStyle = .fullScreen
                    presenter.present(nav, animated: true)
                }
            }
        }
    }
}

final class QRScannerController: UIViewController, DataScannerViewControllerDelegate {
    var onFinish: ((String?) -> Void)?
    var cameraDenied = false

    private var scanner: DataScannerViewController?
    private var finished = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        title = PsText.shared.addByQr
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel, target: self, action: #selector(cancelTapped))

        if cameraDenied {
            showMessage(Self.deniedText)
            return
        }
        // isSupported: whether the device supports it (all iOS 26 devices should)
        // isAvailable: whether it's usable now, given camera permission and restrictions (Screen Time etc.)
        guard DataScannerViewController.isSupported else {
            qlog(.warning, "QRScan", "DataScanner is not supported on this device")
            showMessage("This device can't scan QR codes.\nUse \"Enter manually\" instead.")
            return
        }
        guard DataScannerViewController.isAvailable else {
            qlog(.warning, "QRScan", "DataScanner is unavailable (camera permission or restrictions)")
            showMessage(Self.deniedText)
            return
        }

        let vc = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        vc.delegate = self
        addChild(vc)
        vc.view.frame = view.bounds
        vc.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(vc.view)
        vc.didMove(toParent: self)
        scanner = vc
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard let scanner, !finished, !scanner.isScanning else { return }
        do {
            try scanner.startScanning()
            qlog(.debug, "QRScan", "scanning started")
        } catch {
            qlog(.warning, "QRScan", "startScanning failed: \(error)")
            showMessage(Self.deniedText)
        }
    }

    private static let deniedText = "PeerSync doesn't have access to the camera.\nAllow it in the Settings app, or use \"Enter manually\" instead."

    private func showMessage(_ text: String) {
        let label = UILabel()
        label.text = text
        label.textColor = .white
        label.numberOfLines = 0
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
        ])
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if let scanner, scanner.isScanning { scanner.stopScanning() }
        if !finished {
            finished = true
            onFinish?(nil)
        }
    }

    @objc private func cancelTapped() {
        dismiss(animated: true)
    }

    // MARK: - DataScannerViewControllerDelegate

    func dataScanner(_ dataScanner: DataScannerViewController,
                     didAdd addedItems: [RecognizedItem],
                     allItems: [RecognizedItem]) {
        guard !finished else { return }
        for item in addedItems {
            guard case .barcode(let barcode) = item, let text = barcode.payloadStringValue else { continue }
            finished = true
            qlog(.debug, "QRScan", "qr found")
            dataScanner.stopScanning()
            onFinish?(text)
            dismiss(animated: true)
            return
        }
    }

    func dataScanner(_ dataScanner: DataScannerViewController,
                     becameUnavailableWithError error: DataScannerViewController.ScanningUnavailable) {
        qlog(.warning, "QRScan", "DataScanner became unavailable: \(error)")
        showMessage(Self.deniedText)
    }
}
