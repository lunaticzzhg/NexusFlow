@testable import OrbitApp
import ComposeApp
import XCTest

final class IosKeychainStoreTests: XCTestCase {
    func testWriteReadOverwriteAndRemove() {
        let store = IosKeychainStore()
        let namespace = "test_\(UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: ""))"
        let key = "session_v1"
        defer { _ = store.clear(namespace: namespace) }

        XCTAssertEqual(store.read(namespace: namespace, key: key).status, .notFound)
        XCTAssertEqual(store.write(namespace: namespace, key: key, value: "first").status, .success)
        XCTAssertEqual(store.read(namespace: namespace, key: key).value, "first")

        XCTAssertEqual(store.write(namespace: namespace, key: key, value: "second").status, .success)
        XCTAssertEqual(store.read(namespace: namespace, key: key).value, "second")

        XCTAssertEqual(store.remove(namespace: namespace, key: key).status, .success)
        XCTAssertEqual(store.read(namespace: namespace, key: key).status, .notFound)
    }
}
