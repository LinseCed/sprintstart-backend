package com.sprintstart.sprintstartbackend.user.model.entity

import com.sprintstart.sprintstartbackend.user.external.enums.GithubLoginSource
import com.sprintstart.sprintstartbackend.user.external.enums.Role
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sprintstart_users")
class User(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true, updatable = false)
    val authId: String,
    @Column(nullable = false)
    var username: String,
    @Column(nullable = true)
    var email: String?,
    @Column(nullable = false)
    var firstname: String,
    @Column(nullable = false)
    var lastname: String,
    @Column(nullable = false)
    var enabled: Boolean = true,
    @Column(nullable = true)
    var profileIcon: String? = null,
    @Column(nullable = false)
    var hasCompletedOnboarding: Boolean = false,
    // Stamped by SessionActivityService on authenticated request traffic; used to detect an idle
    // gap past the configured threshold as a stand-in for a real login/session boundary.
    @Column(name = "last_seen_at", nullable = true)
    var lastSeenAt: Instant? = null,
    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(
        name = "user_roles",
        joinColumns = [JoinColumn(name = "id")],
    )
    @Column(name = "role", nullable = false)
    val roles: MutableSet<Role> = mutableSetOf(),
    @Column(nullable = true)
    var avatarUrl: String? = null,
    // The GitHub account this user contributes as. Artifact verification attributes a submitted
    // pull request to a hire by comparing its author against this, so it is what makes the
    // highest-rigor tier attributable at all -- see GithubLoginSource for how far it can be
    // trusted. Null until the user (or a PM) fills it in.
    @Column(name = "github_login", nullable = true, unique = true)
    var githubLogin: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "github_login_source", nullable = true)
    var githubLoginSource: GithubLoginSource? = null,
    // When the user opted in to having their existing work in the project's connected repositories
    // used to calibrate their skill assessment. Null means no consent -- the default, and what
    // revoking returns it to. Consent is the gate; the derived signal itself lives in
    // GithubHistoryPrior and is deleted on revocation.
    @Column(name = "github_seeding_consent_at", nullable = true)
    var githubSeedingConsentAt: Instant? = null,
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_projects",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "project_id")],
    )
    var projects: MutableSet<Project> = mutableSetOf(),
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_project_roles",
        joinColumns = [
            jakarta.persistence.JoinColumn(
                name = "user_id",
                foreignKey = jakarta.persistence.ForeignKey(
                    name = "fk_upr_user_id",
                    foreignKeyDefinition = "FOREIGN KEY (user_id) REFERENCES sprintstart_users ON DELETE CASCADE",
                ),
            ),
        ],
        inverseJoinColumns = [
            jakarta.persistence.JoinColumn(
                name = "role_id",
                foreignKey = jakarta.persistence.ForeignKey(
                    name = "fk_upr_role_id",
                    foreignKeyDefinition = "FOREIGN KEY (role_id) REFERENCES " +
                        "sprintstart_project_roles ON DELETE CASCADE",
                ),
            ),
        ],
    )
    @org.hibernate.annotations.BatchSize(size = 50)
    var projectRoles: MutableSet<ProjectRole> = mutableSetOf(),
)
