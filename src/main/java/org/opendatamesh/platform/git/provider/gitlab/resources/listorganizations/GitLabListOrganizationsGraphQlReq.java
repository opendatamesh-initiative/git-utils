package org.opendatamesh.platform.git.provider.gitlab.resources.listorganizations;

import java.util.HashMap;
import java.util.Map;

/**
 * GraphQL request listing the groups where the current user can create projects.
 * <p>
 * {@code permissionScope: CREATE_PROJECTS} is backed by GitLab's {@code Groups::AcceptingProjectCreationsFinder}
 * (GitLab 15.11+), the same query GitLab's "New project" page uses. Unlike {@code GET /groups?min_access_level=30},
 * it also returns groups reached through group sharing (and their subgroups), and it respects each group's
 * "Roles allowed to create projects" setting.
 */
public class GitLabListOrganizationsGraphQlReq {

    static final String QUERY = "query($first: Int!, $after: String) {"
            + " currentUser {"
            + " groups(permissionScope: CREATE_PROJECTS, first: $first, after: $after) {"
            + " nodes { id name fullPath webUrl }"
            + " pageInfo { hasNextPage endCursor }"
            + " } } }";

    // HashMap rather than Map.of: "after" is null on the first page.
    private final Map<String, Object> variables = new HashMap<>();

    public GitLabListOrganizationsGraphQlReq(int first, String after) {
        variables.put("first", first);
        variables.put("after", after);
    }

    public String getQuery() {
        return QUERY;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }
}
