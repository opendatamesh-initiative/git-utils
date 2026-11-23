package org.opendatamesh.platform.git.provider.azure.resources.createrepository;

import org.opendatamesh.platform.git.model.RepositoryOwnerType;
import org.opendatamesh.platform.git.provider.azure.AzureRefNames;
import org.opendatamesh.platform.git.model.Repository;
import org.opendatamesh.platform.git.model.RepositoryVisibility;

public abstract class AzureCreateRepositoryMapper {

    public static Repository toInternalModel(AzureCreateRepositoryRepositoryRes repoRes, String projectId) {
        if (repoRes == null) {
            return null;
        }

        return new Repository(
                repoRes.getId(),
                repoRes.getName(),
                repoRes.getDescription(),
                repoRes.getRemoteUrl(),
                null,
                AzureRefNames.toShortBranchName(repoRes.getDefaultBranch()),
                RepositoryOwnerType.ORGANIZATION,
                        projectId,
                RepositoryVisibility.PRIVATE // Azure DevOps repos are always private
        );
    }

    public static AzureCreateRepositoryReq fromInternalModel(Repository repository) {
        if (repository == null) {
            return null;
        }

        AzureCreateRepositoryReq request = new AzureCreateRepositoryReq();
        request.setName(repository.getName());

        AzureCreateRepositoryProjectReferenceReq projectRef = new AzureCreateRepositoryProjectReferenceReq();
        projectRef.setId(repository.getOwnerId());
        request.setProject(projectRef);

        return request;
    }
}

