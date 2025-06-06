import hexdebug.libs

plugins {
    id("hexdebug.conventions.platform")
}

architectury {
    fabric()
}

hexdebugModDependencies {
    filesMatching.add("fabric.mod.json")

    anyVersion = "*"
    mapVersions {
        replace(",", " ")
        replace(Regex("""\s+"""), " ")
        replace(Regex("""\[(\S+)"""), ">=$1")
        replace(Regex("""(\S+)\]"""), "<=$1")
        replace(Regex("""\](\S+)"""), ">$1")
        replace(Regex("""(\S+)\["""), "<$1")
    }

    requires("architectury-api")
    requires("cloth-config")
    requires(curseforge = "hexcasting", modrinth = "hex-casting")
    requires("ioticblocks")

    requires("fabric-api")
    requires("fabric-language-kotlin")

    optional("modmenu")
}

dependencies {
    modApi(libs.fabric.api)
    modImplementation(libs.fabric.loader)

    modImplementation(libs.kotlin.fabric)

    modApi(libs.architectury.fabric) {
        // Fix for the "two fabric loaders" loading crash
        exclude(group = "net.fabricmc", module = "fabric-loader")
    }

    modApi(libs.hexcasting.fabric) {
        // If not excluded here, calls a nonexistent method and crashes the dev client
        exclude(module = "phosphor")
    }
    modLocalRuntime(libs.paucal.fabric)
    modLocalRuntime(libs.patchouli.fabric)
    modLocalRuntime(libs.cardinalComponents)
    modLocalRuntime(libs.serializationHooks)
    modLocalRuntime(libs.trinkets)
    modLocalRuntime(libs.inline.fabric)

    libs.mixinExtras.fabric.also {
        localRuntime(it)
        include(it)
        annotationProcessor(it)
    }

    modApi(libs.clothConfig.fabric) {
        exclude(group = "net.fabricmc.fabric-api")
    }
    modImplementation(libs.modMenu)

    libs.bundles.lsp4j.also {
        api(it)
        include(it)
    }

    libs.bundles.ktor.also {
        implementation(it)
        include(it)
    }

    modLocalRuntime(libs.devAuth.fabric)

    modApi(libs.ioticblocks.fabric)
}

publishMods {
    modLoaders.add("quilt")

    // this fails if we do it for all projects, since the tag already exists :/
    // see https://github.com/modmuss50/mod-publish-plugin/issues/3
    github {
        accessToken = System.getenv("GITHUB_TOKEN") ?: ""
        repository = System.getenv("GITHUB_REPOSITORY") ?: ""
        commitish = System.getenv("GITHUB_SHA") ?: ""

        type = STABLE
        displayName = "v${project.version}"
        tagName = "v${project.version}"
    }
}

tasks {
    remapJar {
        injectAccessWidener.set(true)
    }

    named("publishGithub") {
        dependsOn(
            project(":Common").tasks.remapJar,
            project(":Forge").tasks.remapJar,
        )

        // we need to do this here so that it waits until Forge is already configured
        // otherwise tasks.remapJar doesn't exist yet
        publishMods {
            github {
                additionalFiles.from(
                    project(":Common").tasks.remapJar.get().archiveFile,
                    project(":Forge").tasks.remapJar.get().archiveFile,
                )
            }
        }
    }
}
