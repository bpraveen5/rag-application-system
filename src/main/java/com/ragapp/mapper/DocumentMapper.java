package com.ragapp.mapper;

import com.ragapp.dto.DocumentDto;
import com.ragapp.entity.Document;
import org.mapstruct.*;

import java.util.List;

/**
 * MapStruct mapper between {@link Document} entity and DTOs.
 * Uses component model = "spring" for Spring DI integration.
 */
@Mapper(
    componentModel = "spring",
    unmappedTargetPolicy = ReportingPolicy.IGNORE,
    nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface DocumentMapper {

    @Mapping(target = "id",               source = "id")
    @Mapping(target = "filename",         source = "filename")
    @Mapping(target = "originalFilename", source = "originalFilename")
    @Mapping(target = "contentType",      source = "contentType")
    @Mapping(target = "fileSize",         source = "fileSize")
    @Mapping(target = "status",           source = "status")
    @Mapping(target = "version",          source = "version")
    @Mapping(target = "chunkCount",       source = "chunkCount")
    @Mapping(target = "metadata",         source = "metadata")
    @Mapping(target = "uploadDate",       source = "uploadDate")
    @Mapping(target = "indexedAt",        source = "indexedAt")
    @Mapping(target = "errorMessage",     source = "errorMessage")
    DocumentDto.DocumentResponse toResponse(Document document);

    List<DocumentDto.DocumentResponse> toResponseList(List<Document> documents);
}
