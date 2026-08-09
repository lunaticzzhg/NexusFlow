import ComposeApp
import GoogleSignIn
import UIKit

/** Owns one UIKit window's Google Sign-In presentation and callback lifetime. */
final class IosGoogleSignInHost: NSObject, IosGoogleSignInExecutor {
    private weak var presenter: UIViewController?
    private let gateway: IosSystemUiGateway
    private var activeRequestID: SystemUiRequestId?

    init(gateway: IosSystemUiGateway) {
        self.gateway = gateway
    }

    func attach(presenter: UIViewController) {
        self.presenter = presenter
        gateway.attach(executor: self)
    }

    func detach() {
        presenter = nil
        activeRequestID = nil
        gateway.detach()
    }

    func requestGoogleSignIn(request: GoogleSignInRequest) {
        guard activeRequestID == nil else {
            gateway.complete(result: GoogleSignInResultUnavailable(id: request.id))
            return
        }
        guard let presenter,
              let clientID = configuredValue(for: "GIDClientID"),
              let serverClientID = configuredValue(for: "GIDServerClientID") else {
            gateway.complete(result: GoogleSignInResultUnavailable(id: request.id))
            return
        }

        activeRequestID = request.id
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(
            clientID: clientID,
            serverClientID: serverClientID,
        )
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { [weak self] result, error in
            guard let self, self.activeRequestID == request.id else { return }
            self.activeRequestID = nil

            if let error {
                let signInError = error as NSError
                let signInResult: GoogleSignInResult =
                    signInError.code == GIDSignInError.canceled.rawValue
                        ? GoogleSignInResultCancelled(id: request.id)
                        : GoogleSignInResultFailed(id: request.id)
                self.gateway.complete(result: signInResult)
                return
            }

            guard let idToken = result?.user.idToken?.tokenString, !idToken.isEmpty else {
                self.gateway.complete(result: GoogleSignInResultFailed(id: request.id))
                return
            }
            self.gateway.complete(result: GoogleSignInResultSuccess(id: request.id, idToken: idToken))
        }
    }

    func cancelGoogleSignIn(requestId: SystemUiRequestId) {
        guard activeRequestID == requestId else { return }
        activeRequestID = nil
    }

    func handle(url: URL) -> Bool {
        GIDSignIn.sharedInstance.handle(url)
    }

    private func configuredValue(for key: String) -> String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String else { return nil }
        let trimmedValue = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedValue.isEmpty || trimmedValue.hasPrefix("$(") ? nil : trimmedValue
    }
}
