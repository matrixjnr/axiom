plugins { id("axiom.java-library") }

dependencies {
    api(project(":axiom-core"))
    // Generated service stubs bring the gRPC API to an application; Netty stays shaded and internal.
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.java)
    testImplementation(project(":axiom-test"))
    testImplementation(libs.grpc.inprocess)
}
