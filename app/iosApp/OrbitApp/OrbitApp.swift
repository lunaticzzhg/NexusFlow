import ComposeApp
import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    private let systemUiGateway = IosSystemUiGateway()
    private let keychainStore = IosKeychainStore()
    private lazy var googleSignInHost = IosGoogleSignInHost(gateway: systemUiGateway)

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?,
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = MainViewControllerKt.mainViewController(
            systemUiGateway: systemUiGateway,
            keychainExecutor: keychainStore,
        )
        window.makeKeyAndVisible()
        self.window = window
        if let presenter = window.rootViewController {
            googleSignInHost.attach(presenter: presenter)
        }
        return true
    }

    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:],
    ) -> Bool {
        googleSignInHost.handle(url: url)
    }

    func applicationWillTerminate(_ application: UIApplication) {
        googleSignInHost.detach()
    }
}
