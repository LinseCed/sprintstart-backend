package com.sprintstart.sprintstartbackend.ingestion.listener.upload

import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus
import com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.service.IngestionRunLifeCycleService
import com.sprintstart.sprintstartbackend.ingestion.service.UploadIngestionRunService
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadBatchDeletionFinishedEvent
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadBatchFinishedEvent
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadStartedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
internal class UploadIngestionRunLifecycleListener(
    private val ingestionRunLifeCycleService: IngestionRunLifeCycleService,
    private val uploadIngestionRunService: UploadIngestionRunService,
) {
    @EventListener
    fun on(
        event: UploadStartedEvent,
    ) {
        ingestionRunLifeCycleService
            .startRun(
                transactionId = event.transactionId,
                sourceSystem = SourceSystem.UPLOAD,
                status = IngestionRunStatus.RUNNING,
            )
    }

    @EventListener
    fun on(
        event: UploadBatchFinishedEvent,
    ) {
        uploadIngestionRunService.finishUploadIngestionRun(event)
    }

    @EventListener
    fun on(
        event: UploadBatchDeletionFinishedEvent,
    ) {
        uploadIngestionRunService.finishUploadDeletionIngestionRun(event)
    }
}
