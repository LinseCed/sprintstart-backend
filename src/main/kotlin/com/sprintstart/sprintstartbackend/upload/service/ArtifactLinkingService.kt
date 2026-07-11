package com.sprintstart.sprintstartbackend.upload.service

import com.sprintstart.sprintstartbackend.upload.model.entity.LinkedImage
import com.sprintstart.sprintstartbackend.upload.model.entity.UploadedArtifact
import com.sprintstart.sprintstartbackend.upload.repository.LinkedImageRepository
import org.springframework.stereotype.Service
import java.nio.file.Paths

@Service
class ArtifactLinkingService(
    private val linkedImageRepository: LinkedImageRepository,
    private val extractor: MarkdownImageReferenceExtractor,
) {
    fun linkMarkdownImages(
        markdownArtifacts: List<Pair<UploadedArtifact, String>>,
        uploadedArtifactsByFilename: Map<String, UploadedArtifact>,
    ): Set<LinkedImage> {
        val linkedImages = mutableSetOf<LinkedImage>()
        markdownArtifacts.forEach { (artifact, markdownContent) ->

            val imagePaths = extractor.extract(markdownContent)

            imagePaths.forEach { imagePath ->

                val normalizedFilename =
                    Paths
                        .get(imagePath)
                        .fileName
                        .toString()

                val imageArtifact =
                    uploadedArtifactsByFilename[normalizedFilename]
                        ?: return@forEach

                val linkedImage = LinkedImage(
                    markdownArtifact = artifact,
                    originalPath = imagePath,
                    imageArtifact = imageArtifact,
                )

                linkedImageRepository.save(linkedImage)

                linkedImages.add(linkedImage)
            }
        }
        return linkedImages.toSet()
    }
}
