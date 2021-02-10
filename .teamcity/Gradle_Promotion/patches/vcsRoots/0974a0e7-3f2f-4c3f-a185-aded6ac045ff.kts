package Gradle_Promotion.patches.vcsRoots

import jetbrains.buildServer.configs.kotlin.v2019_2.*
import jetbrains.buildServer.configs.kotlin.v2019_2.ui.*
import jetbrains.buildServer.configs.kotlin.v2019_2.vcs.GitVcsRoot

/*
This patch script was generated by TeamCity on settings change in UI.
To apply the patch, change the vcsRoot with uuid = '0974a0e7-3f2f-4c3f-a185-aded6ac045ff' (id = 'Gradle_Promotion__master_')
accordingly, and delete the patch script.
*/
changeVcsRoot(uuid("0974a0e7-3f2f-4c3f-a185-aded6ac045ff")) {
    val expected = GitVcsRoot({
        uuid = "0974a0e7-3f2f-4c3f-a185-aded6ac045ff"
        id("Gradle_Promotion__master_")
        name = "Gradle Promotion"
        url = "https://github.com/gradle/gradle-promote.git"
        branch = "master"
        agentGitPath = "%env.TEAMCITY_GIT_PATH%"
        useMirrors = false
        authMethod = password {
            userName = "bot-teamcity"
            password = "%github.bot-teamcity.token%"
        }
    })

    check(this == expected) {
        "Unexpected VCS root settings"
    }

    (this as GitVcsRoot).apply {
        authMethod = password {
            userName = "bot-teamcity"
            password = "credentialsJSON:ecc6ec89-b940-4699-a466-cec87f0285da"
        }
    }

}
