rootProject.name = "eia-document-expert"

includeBuild("../eia-platform") {
    dependencySubstitution {
        substitute(module("com.eia:libs")).using(project(":libs"))
    }
}
