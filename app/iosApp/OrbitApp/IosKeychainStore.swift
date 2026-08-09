import ComposeApp
import Foundation
import Security

/** Native Keychain adapter for the shared App session store. */
final class IosKeychainStore: NSObject, IosKeychainExecutor {
    private static let servicePrefix = "com.nexusflow.app.secure"

    func read(namespace: String, key: String) -> IosKeychainReadResult {
        var query = itemQuery(namespace: namespace, key: key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data,
                  let value = String(data: data, encoding: .utf8) else {
                return IosKeychainReadResult(status: .failure, value: nil)
            }
            return IosKeychainReadResult(status: .success, value: value)
        case errSecItemNotFound:
            return IosKeychainReadResult(status: .notFound, value: nil)
        default:
            return IosKeychainReadResult(status: .failure, value: nil)
        }
    }

    func write(namespace: String, key: String, value: String) -> IosKeychainOperationResult {
        guard let data = value.data(using: .utf8) else {
            return IosKeychainOperationResult(status: .failure)
        }

        let query = itemQuery(namespace: namespace, key: key)
        let updateStatus = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        switch updateStatus {
        case errSecSuccess:
            return IosKeychainOperationResult(status: .success)
        case errSecItemNotFound:
            var attributes = query
            attributes[kSecValueData as String] = data
            attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            return operationResult(for: SecItemAdd(attributes as CFDictionary, nil))
        default:
            return IosKeychainOperationResult(status: .failure)
        }
    }

    func remove(namespace: String, key: String) -> IosKeychainOperationResult {
        operationResult(for: SecItemDelete(itemQuery(namespace: namespace, key: key) as CFDictionary))
    }

    func clear(namespace: String) -> IosKeychainOperationResult {
        operationResult(for: SecItemDelete(namespaceQuery(namespace) as CFDictionary))
    }

    private func itemQuery(namespace: String, key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service(for: namespace),
            kSecAttrAccount as String: key,
        ]
    }

    private func namespaceQuery(_ namespace: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service(for: namespace),
        ]
    }

    private func service(for namespace: String) -> String {
        "\(Self.servicePrefix).\(namespace)"
    }

    private func operationResult(for status: OSStatus) -> IosKeychainOperationResult {
        switch status {
        case errSecSuccess:
            IosKeychainOperationResult(status: .success)
        case errSecItemNotFound:
            IosKeychainOperationResult(status: .notFound)
        default:
            IosKeychainOperationResult(status: .failure)
        }
    }
}
