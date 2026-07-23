package com.sprintstart.sprintstartbackend.connectors.jira.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "jira_issues")
class JiraIssue(
    @Id
	val id: String,
    @Column(name = "last_update")
    val lastUpdate: Instant = Instant.now()
)
