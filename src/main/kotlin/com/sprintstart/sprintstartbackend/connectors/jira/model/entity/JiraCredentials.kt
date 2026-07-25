package com.sprintstart.sprintstartbackend.connectors.jira.model.entity

import com.sprintstart.sprintstartbackend.shared.crypto.SymmetricEncryptedStringConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "jira_credentials")
class JiraCredentials(
    @Id
    @Column(name = "user_email")
    var userEmail: String,
    @Convert(converter = SymmetricEncryptedStringConverter::class)
    @Column(name = "api_key", nullable = false, unique = true)
    var apiKey: String,
)
