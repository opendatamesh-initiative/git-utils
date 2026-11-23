package org.opendatamesh.platform.git.provider.azure.resources.getrepository;

import org.opendatamesh.platform.git.model.RepositoryOwnerType;
import org.opendatamesh.platform.git.provider.azure.AzureRefNames;
import org.opendatamesh.platform.git.model.Repository;
import org.opendatamesh.platform.git.model.RepositoryVisibility;

public abstract class AzureGetRepositoryMapper {

    public static Repository toInternalModel(AzureGetRepositoryRepositoryRes repoRes, String projectId) {
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
                RepositoryVisibility.PRIVATE
        );
    }
}

