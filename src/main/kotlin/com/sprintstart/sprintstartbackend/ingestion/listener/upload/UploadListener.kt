package com.sprintstart.sprintstartbackend.ingestion.listener.upload

import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus
import com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.model.mapper.UploadArtifactMapper
import com.sprintstart.sprintstartbackend.ingestion.service.ArtifactIngestionService
import com.sprintstart.sprintstartbackend.ingestion.service.provider.UploadArtifactProviderService
import com.sprintstart.sprintstartbackend.upload.external.events.ArtifactUploadedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
internal class UploadListener(
    private val uploadArtifactMapper: UploadArtifactMapper,
    private val uploadArtifactProviderService: UploadArtifactProviderService,

    ) {
    @EventListener
    fun on(
        event: ArtifactUploadedEvent,
    ) {
        uploadArtifactProviderService.persistArtifact(
            uploadArtifactProviderService.persistArtifact(uploadArtifactMapper.toCommand(event))
        )

    }

}
