package com.nexusflow.backend.bootstrap

enum class BackendRuntimeProfile {
    Production,
    Test,
    ;

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): BackendRuntimeProfile =
            if (environment["ORBIT_RUNTIME_PROFILE"] == "test") Test else Production
    }
}
