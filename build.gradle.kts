plugins {
    id("com.skillsjars.gradle-plugin") version "0.2.0"
}

skillsjars {
    outputDir.set(layout.projectDirectory.dir(".kiro/skills"))
}

dependencies {
    skill("com.jamesward:skills:0.0.27")
}
