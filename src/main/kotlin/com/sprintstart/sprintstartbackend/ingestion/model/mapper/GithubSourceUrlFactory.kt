package com.sprintstart.sprintstartbackend.ingestion.model.mapper

object GithubSourceUrlFactory {
    fun buildCommitUrl(repositoryOwner: String, repositoryName: String, sha: String?) =
        sha?.let {
            "https://github.com/$repositoryOwner/$repositoryName/commit/$sha"
        }

    fun buildFileUrl(repositoryOwner: String, repositoryName: String, sha: String) =
        "https://github.com/$repositoryOwner/$repositoryName/blob/$sha/"
}
