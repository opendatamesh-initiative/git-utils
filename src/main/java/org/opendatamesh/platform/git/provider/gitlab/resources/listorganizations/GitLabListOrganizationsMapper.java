package org.opendatamesh.platform.git.provider.gitlab.resources.listorganizations;

import org.opendatamesh.platform.git.model.Organization;

public abstract class GitLabListOrganizationsMapper {

    public static Organization toInternalModel(GitLabListOrganizationsGroupRes groupRes) {
        if (groupRes == null) {
            return null;
        }

        return new Organization(
                String.valueOf(groupRes.getId()),
                groupRes.getName(),
                groupRes.getWebUrl()
        );
    }

    /**
     * Maps a GraphQL group. Its global ID ({@code gid://gitlab/Group/123}) is reduced to the numeric ID that the
     * REST API (and so the rest of this provider) uses. Returns null when the ID can't be parsed.
     */
    public static Organization toInternalModel(GitLabListOrganizationsGraphQlRes.Group group) {
        if (group == null || group.getId() == null) {
            return null;
        }
        String id = group.getId().substring(group.getId().lastIndexOf('/') + 1);
        if (id.isEmpty() || !id.chars().allMatch(Character::isDigit)) {
            return null;
        }

        return new Organization(id, group.getName(), group.getWebUrl());
    }
}

