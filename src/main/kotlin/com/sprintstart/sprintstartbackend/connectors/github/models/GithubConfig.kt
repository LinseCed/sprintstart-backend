package com.sprintstart.sprintstartbackend.connectors.github.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapsId
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "gh_configs")
data class GithubConfig(
    @Id
    var id: UUID = UUID.randomUUID(),
    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "repository_id")
    var repository: GithubRepositoryConnection,
    @Column(name = "auto_update", nullable = false)
    var autoUpdate: Boolean = false,
    @Column(nullable = false)
    var schedule: String = "0 0 2 * * *"
)

