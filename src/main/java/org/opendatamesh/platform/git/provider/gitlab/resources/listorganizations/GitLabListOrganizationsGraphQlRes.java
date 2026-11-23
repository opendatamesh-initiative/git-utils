package org.opendatamesh.platform.git.provider.gitlab.resources.listorganizations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Response of {@link GitLabListOrganizationsGraphQlReq}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitLabListOrganizationsGraphQlRes {
    private Data data;
    private List<Error> errors;

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

    public List<Error> getErrors() {
        return errors;
    }

    public void setErrors(List<Error> errors) {
        this.errors = errors;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {
        private CurrentUser currentUser;

        public CurrentUser getCurrentUser() {
            return currentUser;
        }

        public void setCurrentUser(CurrentUser currentUser) {
            this.currentUser = currentUser;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrentUser {
        private GroupConnection groups;

        public GroupConnection getGroups() {
            return groups;
        }

        public void setGroups(GroupConnection groups) {
            this.groups = groups;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GroupConnection {
        private List<Group> nodes;
        private PageInfo pageInfo;

        public List<Group> getNodes() {
            return nodes;
        }

        public void setNodes(List<Group> nodes) {
            this.nodes = nodes;
        }

        public PageInfo getPageInfo() {
            return pageInfo;
        }

        public void setPageInfo(PageInfo pageInfo) {
            this.pageInfo = pageInfo;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Group {
        /**
         * Global ID, e.g. {@code gid://gitlab/Group/123}.
         */
        private String id;
        private String name;
        private String fullPath;
        private String webUrl;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getFullPath() {
            return fullPath;
        }

        public void setFullPath(String fullPath) {
            this.fullPath = fullPath;
        }

        public String getWebUrl() {
            return webUrl;
        }

        public void setWebUrl(String webUrl) {
            this.webUrl = webUrl;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PageInfo {
        private boolean hasNextPage;
        private String endCursor;

        public boolean isHasNextPage() {
            return hasNextPage;
        }

        public void setHasNextPage(boolean hasNextPage) {
            this.hasNextPage = hasNextPage;
        }

        public String getEndCursor() {
            return endCursor;
        }

        public void setEndCursor(String endCursor) {
            this.endCursor = endCursor;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Error {
        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
