package com.sprintstart.sprintstartbackend.user.model.entity

import com.sprintstart.sprintstartbackend.user.external.enums.SkillStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "sprintstart_skills")
class Skill(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true)
    var name: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    var projectRole: ProjectRole,
    @Column(nullable = true)
    var description: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SkillStatus = SkillStatus.ACTIVE,
)
