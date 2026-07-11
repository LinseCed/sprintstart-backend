package com.sprintstart.sprintstartbackend.upload.external

import com.nimbusds.jose.util.StandardCharset
import com.sprintstart.sprintstartbackend.upload.repository.UploadedArtifactRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Service
class UploadedArtifactReaderImpl(private val uploadedArtifactRepository: UploadedArtifactRepository )
    : UploadedArtifactReader{
    override fun readText(artifactId: UUID): String {
        val uploadedArtifact = uploadedArtifactRepository.findByIdOrNull(artifactId)
        uploadedArtifact?: throw IllegalArgumentException("Artifact with id $artifactId not found")

        return Files.readString(Path.of(uploadedArtifact.storagePath), StandardCharset.UTF_8)
    }

}