package fr.dossierfacile.common.mapper;

import fr.dossierfacile.common.entity.Document;
import fr.dossierfacile.common.entity.UserApi;
import fr.dossierfacile.common.model.apartment_sharing.DocumentModel;

class ApplicationFullMapperTest implements AuthenticityStatusMappingTest {

    @Override
    public DocumentModel mapDocument(Document document) {
        ApplicationFullMapperImpl mapper = new ApplicationFullMapperImpl();
        mapper.setCategoriesMapper(new VersionedCategoriesMapper());
        return mapper.toDocumentModel(document, UserApi.builder().version(4).build());
    }

}