package com.nexusflow.backend.bootstrap

enum class BackendRuntimeProfile {
    Production,
    Test,
    ;

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): BackendRuntimeProfile =
            when (val value = environment["ORBIT_RUNTIME_PROFILE"]?.trim()) {
                null -> Production
                "" -> error("ORBIT_RUNTIME_PROFILE must not be blank")
                "local", "prod", "production" -> Production
                "test" -> Test
                else -> error("ORBIT_RUNTIME_PROFILE must be one of local, test, prod, production")
            }
    }
}
