package com.sprintstart.sprintstartbackend.ingestion.listener.upload

import com.sprintstart.sprintstartbackend.ingestion.model.mapper.UploadArtifactMapper
import com.sprintstart.sprintstartbackend.ingestion.service.provider.UploadArtifactProviderService
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.ArtifactUploadedEvent
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadFileDeletedEvent
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
            uploadArtifactMapper.toCommand(event),
        )
    }

    @EventListener
    fun on(
        event: UploadFileDeletedEvent,
    ) {
        uploadArtifactProviderService.deleteArtifact(
            transactionId = event.transactionId,
            uploadArtifactId = event.uploadArtifactId,
        )
    }
}
