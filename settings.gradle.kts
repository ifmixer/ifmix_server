plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "ifmix-server"

include("core-api")
include("core-common")
include("core-job")
